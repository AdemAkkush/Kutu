package com.kutu.chat.domain

import com.kutu.chat.data.AttachmentStore
import com.kutu.chat.data.SettingsStore
import com.kutu.chat.data.api.ApiException
import com.kutu.chat.data.api.ChatMessageDto
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
import kotlinx.coroutines.Dispatchers
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

/** Yeniden üretimde yeni yer tutucuya aktarılan sürüm listesi. */
private data class VersionCarry(val json: String, val active: Int)

/**
 * Sohbetin tüm "beyni": yanıt üretimi (akış), kalıcılık, otomatik başlık, uzun sohbet özetleme ve
 * uzun süreli hafıza çıkarımı. Uygulama kapsamında çalıştığı için ekran değişse de üretim sürer.
 */
class ChatEngine(
    private val db: AppDatabase,
    private val api: NvidiaApi,
    private val settingsStore: SettingsStore,
    private val attachmentStore: AttachmentStore,
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

    /** Uygulama süreci yanıt sırasında öldüyse yarım kalan mesajları düzeltir; sahipsiz ek dosyalarını siler. */
    suspend fun recoverInterrupted() {
        for (m in messages.streaming()) {
            if (m.content.isBlank() && m.reasoning.isBlank()) {
                messages.delete(m.id)
            } else {
                messages.update(m.copy(status = STATUS_ERROR, error = "Yanıt yarıda kesildi."))
            }
        }
        runCatching {
            val referenced = messages.allAttachmentJson().flatMap { Attachments.parse(it) }.map { it.file }.toSet()
            attachmentStore.sweep(referenced)
        }
    }

    suspend fun createChat(model: String, systemPrompt: String = "", persona: String = ""): Long {
        val now = System.currentTimeMillis()
        return chats.insert(
            ChatEntity(model = model, systemPrompt = systemPrompt, persona = persona, createdAt = now, updatedAt = now),
        )
    }

    suspend fun deleteChat(chatId: Long) {
        stop(chatId)
        val files = messages.attachmentJsonOfChat(chatId).flatMap { Attachments.parse(it) }
        chats.delete(chatId)
        attachmentStore.delete(files)
    }

    fun isGenerating(chatId: Long) = jobs[chatId]?.isActive == true

    fun send(chatId: Long, text: String, attachments: List<Attachment> = emptyList()) {
        val clean = text.trim()
        if ((clean.isEmpty() && attachments.isEmpty()) || isGenerating(chatId)) return
        launchGeneration(chatId) {
            insertUserMessage(chatId, clean, attachments)
            null
        }
    }

    /**
     * Son asistan yanıtını yeniden üretir. Eski yanıt silinmez, sürüm olarak saklanır
     * (hatalı denemeler hariç) ve kullanıcı sürümler arasında gezinebilir.
     */
    fun regenerate(chatId: Long) {
        if (isGenerating(chatId)) return
        launchGeneration(chatId) {
            val list = messages.list(chatId)
            val lastUser = list.lastOrNull { it.role == ROLE_USER } ?: return@launchGeneration null
            val old = list.firstOrNull { it.id > lastUser.id && it.role == ROLE_ASSISTANT }
                ?: return@launchGeneration null
            val versions = Versions.parse(old.versions).toMutableList()
            val active = old.activeVersion.coerceIn(0, maxOf(0, versions.lastIndex))
            if (versions.isEmpty()) versions.add(Versions.snapshot(old)) else versions[active] = Versions.snapshot(old)
            if (old.status == STATUS_ERROR) versions.removeAt(if (versions.size > 1) active else 0)
            versions.add(Version())
            invalidateSummaryIfNeeded(chatId, old.id)
            messages.deleteFrom(chatId, old.id)
            VersionCarry(Versions.encode(versions), versions.lastIndex)
        }
    }

    /** Bir kullanıcı mesajını düzenler (ekleri korunur), ondan sonraki her şeyi siler ve yeniden yanıtlatır. */
    fun editAndResend(chatId: Long, messageId: Long, newText: String) {
        val clean = newText.trim()
        if (isGenerating(chatId)) return
        launchGeneration(chatId) {
            val original = messages.get(messageId) ?: return@launchGeneration null
            val kept = Attachments.parse(original.attachments)
            if (clean.isEmpty() && kept.isEmpty()) return@launchGeneration null
            invalidateSummaryIfNeeded(chatId, messageId)
            // Sonraki mesajların (yeniden üretim sürümleri dahil) ekleri sahipsiz kalmasın.
            val later = messages.list(chatId).filter { it.id > messageId }
                .flatMap { Attachments.parse(it.attachments) }
            messages.deleteFrom(chatId, messageId)
            attachmentStore.delete(later)
            insertUserMessage(chatId, clean, kept)
            null
        }
    }

    /** Tek bir mesajı siler. Kullanıcı mesajıysa hemen ardındaki yanıtı da siler. */
    suspend fun deleteMessage(chatId: Long, messageId: Long) {
        if (isGenerating(chatId)) return
        val list = messages.list(chatId)
        val index = list.indexOfFirst { it.id == messageId }
        if (index < 0) return
        val doomed = mutableListOf(list[index])
        if (list[index].role == ROLE_USER) {
            list.getOrNull(index + 1)?.takeIf { it.role == ROLE_ASSISTANT }?.let { doomed.add(it) }
        }
        invalidateSummaryIfNeeded(chatId, doomed.minOf { it.id })
        doomed.forEach { messages.delete(it.id) }
        attachmentStore.delete(doomed.flatMap { Attachments.parse(it.attachments) })
    }

    /** Asistan yanıtının başka bir sürümünü etkin yapar. */
    suspend fun switchVersion(messageId: Long, target: Int) {
        val m = messages.get(messageId) ?: return
        if (isGenerating(m.chatId)) return
        val versions = Versions.parse(m.versions).toMutableList()
        if (target !in versions.indices) return
        val active = m.activeVersion.coerceIn(0, versions.lastIndex)
        if (target == active) return
        versions[active] = Versions.snapshot(m)
        messages.update(
            Versions.apply(m, versions[target]).copy(versions = Versions.encode(versions), activeVersion = target),
        )
    }

    fun stop(chatId: Long) {
        jobs[chatId]?.cancel()
    }

    private suspend fun insertUserMessage(chatId: Long, text: String, attachments: List<Attachment>) {
        val now = System.currentTimeMillis()
        messages.insert(
            MessageEntity(
                chatId = chatId, role = ROLE_USER, content = text, createdAt = now,
                attachments = Attachments.encode(attachments),
            ),
        )
        chats.touch(chatId, now)
        val chat = chats.get(chatId)
        if (chat != null && !chat.titleLocked && chat.title == DEFAULT_CHAT_TITLE) {
            val seed = text.ifBlank { attachments.firstOrNull()?.name.orEmpty() }
            if (seed.isNotBlank()) chats.setTitle(chatId, seed.replace(Regex("\\s+"), " ").take(40), false)
        }
    }

    private suspend fun invalidateSummaryIfNeeded(chatId: Long, deletedFromId: Long) {
        val chat = chats.get(chatId) ?: return
        if (chat.summary.isNotEmpty() && deletedFromId <= chat.summarizedUpTo) {
            chats.setSummary(chatId, "", 0)
        }
    }

    private fun launchGeneration(chatId: Long, prepare: suspend () -> VersionCarry?) {
        val job = scope.launch {
            val carry = prepare()
            runGeneration(chatId, carry)
        }
        jobs[chatId] = job
        job.invokeOnCompletion { jobs.remove(chatId, job) }
    }

    private suspend fun runGeneration(chatId: Long, carry: VersionCarry?) {
        val settings = settingsStore.settings.value
        val chat = chats.get(chatId) ?: return
        val history = messages.list(chatId)
        val mems = if (settings.memoryEnabled) memories.list() else emptyList()
        val context = withContext(Dispatchers.IO) {
            ContextBuilder.build(settings, chat, mems, history, source = attachmentStore)
        }
        val hadImages = context.any { it.images.isNotEmpty() }

        val placeholder = MessageEntity(
            chatId = chatId,
            role = ROLE_ASSISTANT,
            content = "",
            model = chat.model,
            status = STATUS_STREAMING,
            createdAt = System.currentTimeMillis(),
            versions = carry?.json.orEmpty(),
            activeVersion = carry?.active ?: 0,
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
            if (hadImages && e.code in IMAGE_REJECT_CODES) {
                failure += "\n\nBu model görselleri desteklemiyor olabilir. Görsel destekli bir model seçin " +
                    "(örn. gemma-4-31b-it) ya da görseli kaldırıp tekrar deneyin."
            }
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
            val versions = Versions.parse(base.versions)
            if (versions.size > 1) {
                // Boş kalan yeni sürümü at, önceki sürüme dön.
                val rest = versions.toMutableList().also { it.removeAt(base.activeVersion.coerceIn(0, it.lastIndex)) }
                val target = (base.activeVersion - 1).coerceIn(0, rest.lastIndex)
                messages.update(Versions.apply(base, rest[target]).copy(versions = Versions.encode(rest), activeVersion = target))
            } else {
                messages.delete(base.id)
            }
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
    private suspend fun background(prompt: List<ChatMessageDto>, maxTokens: Int): String {
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
        val userText = user.content.ifBlank { Attachments.parse(user.attachments).joinToString { it.name } }
        val title = MemoryLogic.cleanTitle(
            background(MemoryLogic.titleMessages(userText, stripThinking(assistant.content)), 40),
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
            it.id > chat.summarizedUpTo && it.status == STATUS_DONE && (it.content.isNotBlank() || it.attachments.isNotBlank())
        }
        val tokens = pending.sumOf { TokenEstimator.estimate(it) }
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
        val IMAGE_REJECT_CODES = setOf(400, 415, 422)
    }
}
