package com.kutu.chat.domain

import com.kutu.chat.data.SettingsStore
import com.kutu.chat.data.api.ApiException
import com.kutu.chat.data.api.ChatRequest
import com.kutu.chat.data.api.NvidiaApi
import com.kutu.chat.data.api.StreamEvent
import com.kutu.chat.data.db.AppDatabase
import com.kutu.chat.data.db.ChatEntity
import com.kutu.chat.data.db.DEFAULT_CHAT_TITLE
import com.kutu.chat.data.db.MemoryEntity
import com.kutu.chat.data.db.MessageEntity
import com.kutu.chat.data.db.ROLE_ASSISTANT
import com.kutu.chat.data.db.ROLE_USER
import com.kutu.chat.data.db.STATUS_DONE
import com.kutu.chat.data.db.STATUS_ERROR
import com.kutu.chat.data.db.STATUS_STREAMING
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Şu anda akmakta olan bir yanıt. UI bunu veritabanındaki yer tutucu mesajın üstüne bindirir. */
data class Generation(
    val chatId: Long,
    val messageId: Long,
    val text: String,
    val reasoning: String,
)

sealed interface EngineEvent {
    data class MemoryChanged(val added: List<String>, val removed: Int) : EngineEvent
}

/**
 * Sohbetin tüm "beyni": yanıt üretimi (akış), kalıcılık, otomatik başlık, uzun sohbet özetleme ve
 * uzun süreli hafıza çıkarımı. Uygulama kapsamında çalıştığı için ekran değişse de üretim sürer.
 */
class ChatEngine(
    private val db: AppDatabase,
    private val api: NvidiaApi,
    private val settingsStore: SettingsStore,
    private val scope: CoroutineScope,
) {
    private val chats = db.chatDao()
    private val messages = db.messageDao()
    private val memories = db.memoryDao()

    private val _generations = MutableStateFlow<Map<Long, Generation>>(emptyMap())
    val generations: StateFlow<Map<Long, Generation>> = _generations.asStateFlow()

    private val _events = MutableSharedFlow<EngineEvent>(extraBufferCapacity = 16)
    val events: SharedFlow<EngineEvent> = _events.asSharedFlow()

    private val jobs = ConcurrentHashMap<Long, Job>()

    /** Uygulama süreci yanıt sırasında öldüyse yarım kalan mesajları düzeltir. */
    suspend fun recoverInterrupted() {
        for (m in messages.streaming()) {
            if (m.content.isBlank() && m.reasoning.isBlank()) {
                messages.delete(m.id)
            } else {
                messages.update(m.copy(status = STATUS_ERROR, error = "Yanıt yarıda kesildi."))
            }
        }
    }

    suspend fun createChat(model: String): Long {
        val now = System.currentTimeMillis()
        return chats.insert(ChatEntity(model = model, createdAt = now, updatedAt = now))
    }

    fun isGenerating(chatId: Long) = jobs[chatId]?.isActive == true

    fun send(chatId: Long, text: String) {
        val clean = text.trim()
        if (clean.isEmpty() || isGenerating(chatId)) return
        launchGeneration(chatId) {
            insertUserMessage(chatId, clean)
        }
    }

    /** Son asistan yanıtını silip aynı bağlamla yeniden üretir. */
    fun regenerate(chatId: Long) {
        if (isGenerating(chatId)) return
        launchGeneration(chatId) {
            val list = messages.list(chatId)
            val lastUser = list.lastOrNull { it.role == ROLE_USER } ?: return@launchGeneration
            val firstAssistantAfter = list.firstOrNull { it.id > lastUser.id }
            if (firstAssistantAfter != null) {
                invalidateSummaryIfNeeded(chatId, firstAssistantAfter.id)
                messages.deleteFrom(chatId, firstAssistantAfter.id)
            }
        }
    }

    /** Bir kullanıcı mesajını düzenler, ondan sonraki her şeyi siler ve yeniden yanıtlatır. */
    fun editAndResend(chatId: Long, messageId: Long, newText: String) {
        val clean = newText.trim()
        if (clean.isEmpty() || isGenerating(chatId)) return
        launchGeneration(chatId) {
            invalidateSummaryIfNeeded(chatId, messageId)
            messages.deleteFrom(chatId, messageId)
            insertUserMessage(chatId, clean)
        }
    }

    fun stop(chatId: Long) {
        jobs[chatId]?.cancel()
    }

    private suspend fun insertUserMessage(chatId: Long, text: String) {
        val now = System.currentTimeMillis()
        messages.insert(MessageEntity(chatId = chatId, role = ROLE_USER, content = text, createdAt = now))
        chats.touch(chatId, now)
        val chat = chats.get(chatId)
        if (chat != null && !chat.titleLocked && chat.title == DEFAULT_CHAT_TITLE) {
            chats.setTitle(chatId, text.replace(Regex("\\s+"), " ").take(40), false)
        }
    }

    private suspend fun invalidateSummaryIfNeeded(chatId: Long, deletedFromId: Long) {
        val chat = chats.get(chatId) ?: return
        if (chat.summary.isNotEmpty() && deletedFromId <= chat.summarizedUpTo) {
            chats.setSummary(chatId, "", 0)
        }
    }

    private fun launchGeneration(chatId: Long, prepare: suspend () -> Unit) {
        val job = scope.launch {
            prepare()
            runGeneration(chatId)
        }
        jobs[chatId] = job
        job.invokeOnCompletion { jobs.remove(chatId, job) }
    }

    private suspend fun runGeneration(chatId: Long) {
        val settings = settingsStore.settings.value
        val chat = chats.get(chatId) ?: return
        val history = messages.list(chatId)
        val mems = if (settings.memoryEnabled) memories.list() else emptyList()
        val context = ContextBuilder.build(settings, chat, mems, history)

        val placeholder = MessageEntity(
            chatId = chatId,
            role = ROLE_ASSISTANT,
            content = "",
            model = chat.model,
            status = STATUS_STREAMING,
            createdAt = System.currentTimeMillis(),
        )
        val messageId = messages.insert(placeholder)
        var current = placeholder.copy(id = messageId)

        val text = StringBuilder()
        val reasoning = StringBuilder()
        var lastUi = 0L
        var lastDb = 0L
        fun publish() {
            _generations.update { it + (chatId to Generation(chatId, messageId, text.toString(), reasoning.toString())) }
        }
        publish()

        var failure: String? = null
        try {
            val request = ChatRequest(
                model = chat.model,
                messages = context,
                stream = true,
                temperature = settings.temperature,
                maxTokens = settings.maxTokens,
            )
            api.streamChat(settings, request).collect { event ->
                if (event is StreamEvent.Chunk) {
                    text.append(event.content)
                    reasoning.append(event.reasoning)
                    val now = System.currentTimeMillis()
                    if (now - lastUi >= UI_THROTTLE_MS) {
                        lastUi = now
                        publish()
                    }
                    if (now - lastDb >= DB_THROTTLE_MS) {
                        lastDb = now
                        current = current.copy(content = text.toString(), reasoning = reasoning.toString())
                        messages.update(current)
                    }
                }
            }
        } catch (e: CancellationException) {
            withContext(NonCancellable) {
                finish(chatId, current, text.toString(), reasoning.toString(), error = null)
                _generations.update { it - chatId }
            }
            throw e
        } catch (e: ApiException) {
            failure = e.message ?: "Bilinmeyen hata"
            markUnavailableIfGone(e, chat.model)
        } catch (e: Exception) {
            failure = "Beklenmeyen hata: ${e.message ?: e.javaClass.simpleName}"
        }

        val finalText = text.toString()
        if (failure == null) {
            val split = splitThinking(finalText, reasoning.toString())
            failure = when {
                finalText.isBlank() && reasoning.isBlank() ->
                    "Model boş yanıt döndürdü. Başka bir model deneyin ya da tekrar üretin."
                split.answer.isBlank() ->
                    "Model yalnızca düşündü ve cevaba geçemedi (token sınırı dolmuş olabilir). " +
                        "Ayarlar'dan maksimum yanıt uzunluğunu artırın ya da düşünmeyen bir model seçin."
                else -> null
            }
        }
        finish(chatId, current, finalText, reasoning.toString(), failure)
        _generations.update { it - chatId }

        if (failure == null) {
            scope.launch { postProcess(chatId) }
        }
    }

    private suspend fun finish(chatId: Long, base: MessageEntity, text: String, reasoning: String, error: String?) {
        if (error == null && text.isBlank() && reasoning.isBlank()) {
            messages.delete(base.id)
            return
        }
        messages.update(
            base.copy(
                content = text,
                reasoning = reasoning,
                status = if (error == null) STATUS_DONE else STATUS_ERROR,
                error = error.orEmpty(),
            ),
        )
        chats.touch(chatId, System.currentTimeMillis())
    }

    // ---- Arka plan işleri: başlık, hafıza, özet ---------------------------------------------

    private suspend fun postProcess(chatId: Long) {
        runCatching { generateTitle(chatId) }
        runCatching { extractMemory(chatId) }
        runCatching { summarizeIfNeeded(chatId) }
    }

    /** Arka plan modeli çalışmıyorsa sohbet modeline düşer; 404/410 alan model işaretlenir. */
    private suspend fun background(prompt: List<com.kutu.chat.data.api.ChatMessageDto>, maxTokens: Int): String {
        val settings = settingsStore.settings.value
        val preferred = settings.memoryModel.ifBlank { settings.defaultModel }
        val candidates = listOf(preferred, settings.defaultModel)
            .filter { it.isNotBlank() && it !in settings.unavailableModels }
            .distinct()
        var last: ApiException? = null
        for (model in candidates) {
            try {
                return api.complete(
                    settings,
                    ChatRequest(model = model, messages = prompt, stream = false, temperature = 0f, maxTokens = maxTokens),
                )
            } catch (e: ApiException) {
                markUnavailableIfGone(e, model)
                last = e
                if (e.code != 404 && e.code != 410) throw e
            }
        }
        throw last ?: ApiException("Kullanılabilir arka plan modeli yok.")
    }

    private fun markUnavailableIfGone(e: ApiException, model: String) {
        if (e.code == 404 || e.code == 410) {
            settingsStore.update { it.copy(unavailableModels = it.unavailableModels + model) }
        }
    }

    private suspend fun generateTitle(chatId: Long) {
        val chat = chats.get(chatId) ?: return
        if (chat.titleLocked) return
        val list = messages.list(chatId).filter { it.status == STATUS_DONE }
        if (list.size > 4) return
        val user = list.firstOrNull { it.role == ROLE_USER } ?: return
        val assistant = list.firstOrNull { it.role == ROLE_ASSISTANT } ?: return
        val title = MemoryLogic.cleanTitle(
            background(MemoryLogic.titleMessages(user.content, stripThinking(assistant.content)), 40),
        )
        if (title.isNotBlank()) {
            val fresh = chats.get(chatId) ?: return
            if (!fresh.titleLocked) chats.setTitle(chatId, title, true)
        }
    }

    private suspend fun extractMemory(chatId: Long) {
        val settings = settingsStore.settings.value
        if (!settings.memoryEnabled || !settings.autoMemory) return
        val list = messages.list(chatId).filter { it.status == STATUS_DONE }
        val lastAssistant = list.lastOrNull { it.role == ROLE_ASSISTANT } ?: return
        val lastUser = list.lastOrNull { it.role == ROLE_USER && it.id < lastAssistant.id } ?: return
        if (lastUser.content.trim().length < 8) return

        val existing = memories.list()
        val raw = background(
            MemoryLogic.extractionMessages(existing, lastUser.content, stripThinking(lastAssistant.content)),
            400,
        )
        val ops = MemoryLogic.parseOps(raw, existing)
        if (ops.isEmpty) return
        val now = System.currentTimeMillis()
        ops.remove.forEach { memories.delete(it) }
        ops.add.forEach {
            memories.insert(MemoryEntity(content = it, sourceChatId = chatId, createdAt = now, updatedAt = now))
        }
        _events.tryEmit(EngineEvent.MemoryChanged(ops.add, ops.remove.size))
    }

    private suspend fun summarizeIfNeeded(chatId: Long) {
        val settings = settingsStore.settings.value
        val chat = chats.get(chatId) ?: return
        val pending = messages.list(chatId).filter {
            it.id > chat.summarizedUpTo && it.status == STATUS_DONE && it.content.isNotBlank()
        }
        val tokens = pending.sumOf { TokenEstimator.estimate(it.content) }
        if (tokens < settings.contextTokens * SUMMARY_TRIGGER) return
        if (pending.size <= KEEP_RECENT + 1) return

        val toFold = pending.dropLast(KEEP_RECENT)
        val summary = stripThinking(background(MemoryLogic.summaryMessages(chat.summary, toFold), 700)).trim()
        if (summary.isBlank()) return
        chats.setSummary(chatId, summary, toFold.last().id)
    }

    private companion object {
        const val UI_THROTTLE_MS = 50L
        const val DB_THROTTLE_MS = 1500L
        const val SUMMARY_TRIGGER = 0.7
        const val KEEP_RECENT = 6
    }
}
