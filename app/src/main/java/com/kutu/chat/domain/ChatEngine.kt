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
import kotlinx.coroutines.delay
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
    /** Geçici durum bilgisi, örn. "Bağlantı koptu, yeniden deneniyor (1/3)…". */
    val notice: String = "",
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

    private val _activeJobs = MutableStateFlow(0)
    /** Çalışan üretim / arka plan işi sayısı. Sıfırdan büyükken uygulama ön plan servisiyle canlı tutulur. */
    val activeJobs: StateFlow<Int> = _activeJobs.asStateFlow()

    /** Bağlantı kopunca yeniden deneme öncesi bekleme süreleri (ms). Testlerde kısaltılır. */
    @Volatile var retryDelaysMs: List<Long> = listOf(1_500, 4_000, 9_000)

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

    suspend fun createChat(model: String, systemPrompt: String = "", persona: String = "", provider: String = Providers.NVIDIA): Long {
        val now = System.currentTimeMillis()
        return chats.insert(
            ChatEntity(model = model, provider = provider, systemPrompt = systemPrompt, persona = persona, createdAt = now, updatedAt = now),
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
    fun regenerate(chatId: Long, with: ModelRef? = null) {
        if (isGenerating(chatId)) return
        launchGeneration(chatId, model = with) {
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

    fun stopAll() {
        jobs.values.forEach { it.cancel() }
    }

    /** Hata ile yarıda kalmış son yanıtı, kaldığı yerden sürdürür. */
    fun continueMessage(chatId: Long) {
        if (isGenerating(chatId)) return
        launchGeneration(chatId, resumeLast = true) { null }
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

    private fun launchGeneration(chatId: Long, resumeLast: Boolean = false, model: ModelRef? = null, prepare: suspend () -> VersionCarry?) {
        _activeJobs.update { it + 1 }
        val job = scope.launch {
            val carry = prepare()
            val resumeId = if (resumeLast) {
                messages.list(chatId).lastOrNull()
                    ?.takeIf { it.role == ROLE_ASSISTANT && it.status == STATUS_ERROR && it.content.isNotBlank() }?.id
                    ?: return@launch
            } else null
            runGeneration(chatId, carry, resumeId, model)
        }
        jobs[chatId] = job
        job.invokeOnCompletion {
            jobs.remove(chatId, job)
            _activeJobs.update { it - 1 }
        }
    }

    /** [override]: yalnızca bu yanıt için başka bir model (ör. "başka modelle yanıtla"); sohbetin modeli değişmez. */
    private suspend fun runGeneration(chatId: Long, carry: VersionCarry?, resumeId: Long? = null, override: ModelRef? = null) {
        val chat = chats.get(chatId) ?: return
        val ref = override ?: ModelRef(chat.provider, chat.model)
        val provider = Providers.byId(ref.provider)
        // API istemcisi anahtarı ve adresi ayarlardan okur; seçilen sağlayıcınınkiler yerleştirilir.
        val settings = settingsStore.settings.value.forProvider(ref.provider)
        val startedAt = System.currentTimeMillis()
        var firstTokenMs = 0L
        // Sağlayıcılar parametrelerde ayrışır; 400 ile reddedilen parametre bir kez uyarlanıp yeniden denenir.
        var completionTokens = provider?.completionTokensParam == true
        var sendTemperature = true
        val history = messages.list(chatId)
        // Kaldığı yerden devam: yarım kalan yanıt bağlama "asistan" mesajı olarak eklenir, bağlamdan çıkarılır.
        val resumed = resumeId?.let { id -> history.firstOrNull { it.id == id } }
        val baseHistory = if (resumed != null) history.filter { it.id != resumed.id } else history
        val mems = if (settings.memoryEnabled) memories.list() else emptyList()
        val context = withContext(Dispatchers.IO) {
            ContextBuilder.build(settings, chat, mems, baseHistory, source = attachmentStore)
        }
        val hadImages = context.any { it.images.isNotEmpty() }

        var current: MessageEntity
        if (resumed != null) {
            current = resumed.copy(status = STATUS_STREAMING, error = "")
            messages.update(current)
        } else {
            val placeholder = MessageEntity(
                chatId = chatId,
                role = ROLE_ASSISTANT,
                content = "",
                model = ref.key,
                status = STATUS_STREAMING,
                createdAt = System.currentTimeMillis(),
                versions = carry?.json.orEmpty(),
                activeVersion = carry?.active ?: 0,
            )
            current = placeholder.copy(id = messages.insert(placeholder))
        }
        val messageId = current.id

        val text = StringBuilder(resumed?.content.orEmpty())
        val reasoning = StringBuilder(resumed?.reasoning.orEmpty())
        var notice = ""
        var lastUi = 0L
        var lastDb = 0L
        fun publish() {
            _generations.update { it + (chatId to Generation(chatId, messageId, text.toString(), reasoning.toString(), notice)) }
        }
        publish()

        var failure: String? = null
        try {
            var attempt = 0
            while (true) {
                try {
                    // Bu turda zaten yazılmış bir yanıt kısmı varsa model kaldığı yerden sürdürür; yoksa baştan başlar.
                    val soFar = splitThinking(text.toString(), "").answer
                    val requestMessages = if (soFar.isNotBlank()) {
                        context + ChatMessageDto("assistant", soFar) + ChatMessageDto("user", CONTINUE_PROMPT)
                    } else {
                        text.setLength(0); reasoning.setLength(0)
                        context
                    }
                    val request = ChatRequest(
                        model = ref.model,
                        messages = requestMessages,
                        stream = true,
                        temperature = settings.temperature.takeIf { sendTemperature },
                        maxTokens = settings.maxTokens.takeIf { !completionTokens },
                        maxCompletionTokens = settings.maxTokens.takeIf { completionTokens },
                    )
                    api.streamChat(settings, request).collect { event ->
                        if (event is StreamEvent.Chunk) {
                            if (notice.isNotEmpty()) notice = ""
                            if (firstTokenMs == 0L && (event.content.isNotEmpty() || event.reasoning.isNotEmpty())) {
                                firstTokenMs = System.currentTimeMillis() - startedAt
                            }
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
                    break
                } catch (e: ApiException) {
                    val msg = e.message.orEmpty().lowercase()
                    if (e.code == 400 && !completionTokens && "max_tokens" in msg && "max_completion_tokens" in msg) {
                        completionTokens = true; continue
                    }
                    if (e.code == 400 && sendTemperature && "temperature" in msg) {
                        sendTemperature = false; continue
                    }
                    // Geçici ağ sorunu (örn. uygulama arka plana atıldı, ağ değişti): bekleyip aynı yanıtı sürdür.
                    if (!e.retriable || attempt >= retryDelaysMs.size) throw e
                    val wait = retryDelaysMs[attempt]
                    attempt++
                    notice = "Bağlantı koptu, yeniden deneniyor ($attempt/${retryDelaysMs.size})…"
                    current = current.copy(content = text.toString(), reasoning = reasoning.toString())
                    messages.update(current)   // yarım yanıtı hemen kaydet; süreç ölse bile kaybolmaz
                    publish()
                    delay(wait)
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
            markUnavailableIfGone(e, ref.key)
            if (hadImages && e.code in IMAGE_REJECT_CODES) {
                val msg = e.message.orEmpty().lowercase()
                if ("multimodal" in msg || "image" in msg || "vision" in msg) {
                    settingsStore.update { it.copy(visionRejected = it.visionRejected + ref.key, visionConfirmed = it.visionConfirmed - ref.key) }
                }
                failure += "\n\nBu model görselleri desteklemiyor olabilir. Görsel destekli bir model seçin " +
                    "(örn. gemma-4-31b-it) ya da görseli kaldırıp tekrar deneyin."
            }
            if (e.retriable && splitThinking(text.toString(), "").answer.isNotBlank()) {
                failure += "\n\nYanıtın bir kısmı kaydedildi; “Devam et” ile kaldığı yerden sürdürebilirsiniz."
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
        current = current.copy(durationMs = System.currentTimeMillis() - startedAt, firstTokenMs = firstTokenMs)
        finish(chatId, current, finalText, reasoning.toString(), failure)
        _generations.update { it - chatId }

        if (failure == null && hadImages && ref.key !in settings.visionConfirmed) {
            settingsStore.update { it.copy(visionConfirmed = it.visionConfirmed + ref.key, visionRejected = it.visionRejected - ref.key) }
        }
        if (failure == null) {
            tracked { postProcess(chatId) }
        }
    }

    /** Arka plan işi çalıştığı sürece [activeJobs] > 0 olur; ön plan servisi buna göre açık kalır. */
    private fun tracked(block: suspend () -> Unit) {
        _activeJobs.update { it + 1 }
        scope.launch { try { block() } finally { _activeJobs.update { it - 1 } } }
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

    /**
     * Başlık/özet/hafıza için küçük modeli dener; anahtarı yoksa ya da model kaldırılmışsa
     * sohbetin kendi modeline düşer (o az önce yanıt verdiği için çalıştığı bilinir).
     */
    private suspend fun background(prompt: List<ChatMessageDto>, maxTokens: Int, chat: ChatEntity? = null): String {
        val base = settingsStore.settings.value
        val candidates = listOfNotNull(base.memoryRef, chat?.let { ModelRef(it.provider, it.model) }, base.defaultRef)
            .filter { it.model.isNotBlank() && it.key !in base.unavailableModels && base.forProvider(it.provider).apiKey.isNotBlank() }
            .distinct()
        var last: ApiException? = null
        for (ref in candidates) {
            val s = base.forProvider(ref.provider)
            var completionTokens = Providers.byId(ref.provider)?.completionTokensParam == true
            var temperature: Float? = 0f
            var tries = 0
            while (tries++ < 3) {
                try {
                    return api.complete(
                        s,
                        ChatRequest(
                            model = ref.model, messages = prompt, stream = false, temperature = temperature,
                            maxTokens = maxTokens.takeIf { !completionTokens }, maxCompletionTokens = maxTokens.takeIf { completionTokens },
                        ),
                    )
                } catch (e: ApiException) {
                    val msg = e.message.orEmpty().lowercase()
                    if (e.code == 400 && !completionTokens && "max_completion_tokens" in msg) { completionTokens = true; continue }
                    if (e.code == 400 && temperature != null && "temperature" in msg) { temperature = null; continue }
                    markUnavailableIfGone(e, ref.key)
                    last = e
                    break   // sıradaki adayı dene
                }
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
            background(MemoryLogic.titleMessages(userText, stripThinking(assistant.content)), 40, chat),
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
            chats.get(chatId),
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
        val summary = stripThinking(background(MemoryLogic.summaryMessages(chat.summary, toFold), 700, chat)).trim()
        if (summary.isBlank()) return
        chats.setSummary(chatId, summary, toFold.last().id)
    }

    companion object {
        const val UI_THROTTLE_MS = 50L
        const val DB_THROTTLE_MS = 1500L
        const val SUMMARY_TRIGGER = 0.7
        const val KEEP_RECENT = 6
        val IMAGE_REJECT_CODES = setOf(400, 415, 422)
        const val CONTINUE_PROMPT = "Yanıtın yarıda kesildi. Kaldığın yerden, daha önce yazdıklarını tekrarlamadan devam et."
    }
}
