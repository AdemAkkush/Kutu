package com.kutu.chat.ui

import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.kutu.chat.AppContainer
import com.kutu.chat.data.AppSettings
import com.kutu.chat.data.ModelsState
import com.kutu.chat.data.db.ChatEntity
import com.kutu.chat.data.db.MemoryEntity
import com.kutu.chat.data.db.MessageEntity
import com.kutu.chat.data.db.ROLE_ASSISTANT
import com.kutu.chat.data.AttachmentException
import com.kutu.chat.data.BackupException
import com.kutu.chat.data.db.PersonaEntity
import com.kutu.chat.data.db.TemplateEntity
import com.kutu.chat.domain.Attachment
import com.kutu.chat.domain.Attachments
import com.kutu.chat.domain.Personas
import com.kutu.chat.domain.PersonaDef
import com.kutu.chat.domain.TemplateDef
import com.kutu.chat.domain.toDef
import com.kutu.chat.domain.EngineEvent
import com.kutu.chat.domain.Generation
import com.kutu.chat.domain.splitThinking
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

private const val MAX_PENDING = 6

@OptIn(ExperimentalCoroutinesApi::class, FlowPreview::class)
class ChatViewModel(private val c: AppContainer) : ViewModel() {
    private val chatDao = c.db.chatDao()
    private val messageDao = c.db.messageDao()

    /** null = henüz oluşturulmamış yeni sohbet (ilk mesajda oluşturulur). */
    val currentChatId = MutableStateFlow<Long?>(null)
    val ready = MutableStateFlow(false)
    private val pendingModel = MutableStateFlow<String?>(null)

    var input by mutableStateOf("")

    /** Gönderilmeyi bekleyen ekler. */
    val pending = mutableStateListOf<Attachment>()
    var attachError by mutableStateOf<String?>(null)
    var attaching by mutableStateOf(false)

    var searchOpen by mutableStateOf(false)
    var searchQuery by mutableStateOf("")

    /** Henüz oluşturulmamış yeni sohbet için seçili kişilik. */
    val pendingPersona = MutableStateFlow<PersonaDef?>(null)

    val speaking: StateFlow<Long?> get() = c.speaker.speaking
    val speakerError: StateFlow<String?> get() = c.speaker.error
    fun clearSpeakerError() = c.speaker.clearError()

    val settings: StateFlow<AppSettings> = c.settings.settings
    val modelsState: StateFlow<ModelsState> = c.models.state
    val memoryEvents = c.engine.events

    val chat: StateFlow<ChatEntity?> = currentChatId
        .flatMapLatest { id -> if (id == null) flowOf(null) else chatDao.observe(id) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    val messages: StateFlow<List<MessageEntity>> = currentChatId
        .flatMapLatest { id -> if (id == null) flowOf(emptyList()) else messageDao.observe(id) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val generation: StateFlow<Generation?> = combine(currentChatId, c.engine.generations) { id, map ->
        id?.let { map[it] }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    val activeModel: StateFlow<String> = combine(chat, pendingModel, pendingPersona, c.settings.settings) { chat, picked, persona, s ->
        chat?.model ?: picked ?: persona?.model?.takeIf { it.isNotBlank() } ?: s.defaultModel
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), c.settings.settings.value.defaultModel)

    /** Hazır + kullanıcı kişilikleri / şablonları. */
    val personas: StateFlow<List<PersonaDef>> = c.db.personaDao().observeAll()
        .map { list -> Personas.builtIn + list.map { it.toDef() } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), Personas.builtIn)
    val templates: StateFlow<List<TemplateDef>> = c.db.templateDao().observeAll()
        .map { list -> Personas.builtInTemplates.map { TemplateDef(0, it.first, it.second) } + list.map { it.toDef() } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    init {
        viewModelScope.launch {
            // "Kaldığımız yerden devam": en son açık sohbeti geri yükle.
            val last = c.settings.settings.value.lastChatId
            val existing = if (last > 0) chatDao.get(last) else null
            currentChatId.value = (existing ?: chatDao.latest())?.id
            ready.value = true
        }
        // API anahtarı girilince/değişince model listesini (ve çalışan varsayılanları) otomatik yenile.
        viewModelScope.launch {
            c.settings.settings
                .map { it.apiKey.trim() }
                .distinctUntilChanged()
                .debounce(1200)
                .collect { key -> if (key.isNotBlank()) c.models.refresh() }
        }
    }

    fun refreshModels() {
        viewModelScope.launch { c.models.refresh() }
    }

    fun newChat() {
        currentChatId.value = null
        pendingModel.value = null
        pendingPersona.value = null
        discardPending()
        closeSearch()
        input = ""
    }

    /** Yeni bir sohbeti seçilen kişilikle başlatır (ilk mesajda oluşturulur). */
    fun startChatWith(persona: PersonaDef) {
        newChat()
        pendingPersona.value = persona
    }

    fun clearPersona() { pendingPersona.value = null }

    fun openChat(id: Long) {
        discardPending()
        closeSearch()
        currentChatId.value = id
        c.settings.update { it.copy(lastChatId = id) }
    }

    fun send() {
        val text = input.trim()
        if ((text.isEmpty() && pending.isEmpty()) || generation.value != null || attaching) return
        val files = pending.toList()
        input = ""
        pending.clear()
        attachError = null
        viewModelScope.launch {
            val id = currentChatId.value ?: run {
                val persona = pendingPersona.value
                c.engine.createChat(activeModel.value, persona?.systemPrompt.orEmpty(), persona?.label.orEmpty())
            }.also {
                currentChatId.value = it
                pendingPersona.value = null
                c.settings.update { s -> s.copy(lastChatId = it) }
            }
            c.engine.send(id, text, files)
        }
    }

    // ---- Ekler ----

    fun addImage(uri: Uri) = attach { c.attachments.importImage(uri) }
    fun addTextFile(uri: Uri) = attach { c.attachments.importText(uri) }

    private fun attach(load: suspend () -> Attachment) {
        if (pending.size >= MAX_PENDING) {
            attachError = "Bir mesaja en fazla $MAX_PENDING dosya eklenebilir."
            return
        }
        attaching = true
        attachError = null
        viewModelScope.launch {
            try {
                pending.add(load())
            } catch (e: AttachmentException) {
                attachError = e.message
            } catch (e: Exception) {
                attachError = "Dosya eklenemedi: ${e.message ?: e.javaClass.simpleName}"
            } finally {
                attaching = false
            }
        }
    }

    fun removePending(a: Attachment) {
        pending.remove(a)
        c.attachments.delete(listOf(a))
    }

    private fun discardPending() {
        c.attachments.delete(pending.toList())
        pending.clear()
        attachError = null
    }

    fun insertTemplate(text: String) {
        input = if (input.isBlank()) text else input.trimEnd() + "\n\n" + text
    }

    fun appendDictation(text: String) {
        input = if (input.isBlank()) text else input.trimEnd() + " " + text
    }

    // ---- Mesaj işlemleri ----

    fun deleteMessage(id: Long) {
        val chatId = currentChatId.value ?: return
        viewModelScope.launch { c.engine.deleteMessage(chatId, id) }
    }

    fun switchVersion(messageId: Long, target: Int) {
        viewModelScope.launch { c.engine.switchVersion(messageId, target) }
    }

    fun toggleSpeak(messageId: Long, text: String) = c.speaker.toggle(messageId, text)
    fun stopSpeaking() = c.speaker.stop()

    fun closeSearch() {
        searchOpen = false
        searchQuery = ""
    }

    fun stop() {
        currentChatId.value?.let { c.engine.stop(it) }
    }

    fun regenerate() {
        currentChatId.value?.let { c.engine.regenerate(it) }
    }

    fun editAndResend(messageId: Long, text: String) {
        currentChatId.value?.let { c.engine.editAndResend(it, messageId, text) }
    }

    fun selectModel(model: String) {
        val id = currentChatId.value
        if (id == null) pendingModel.value = model else viewModelScope.launch { chatDao.setModel(id, model) }
        c.settings.update { it.copy(defaultModel = model) }
    }

    fun toggleFavorite(model: String) {
        c.settings.update {
            it.copy(favoriteModels = if (model in it.favoriteModels) it.favoriteModels - model else it.favoriteModels + model)
        }
    }

    fun rename(title: String) {
        val id = currentChatId.value ?: return
        viewModelScope.launch { chatDao.setTitle(id, title.trim().ifBlank { "Yeni sohbet" }, true) }
    }

    fun setChatSystemPrompt(prompt: String) {
        val id = currentChatId.value ?: return
        viewModelScope.launch { chatDao.setSystemPrompt(id, prompt.trim()) }
    }

    fun deleteCurrent() {
        val id = currentChatId.value ?: return
        viewModelScope.launch {
            c.engine.deleteChat(id)
            currentChatId.value = null
        }
    }

    /** Sohbeti paylaşılabilir Markdown metnine çevirir. */
    fun exportMarkdown(): String {
        val title = chat.value?.title ?: "Sohbet"
        return buildString {
            append("# ").append(title).append("\n\n")
            messages.value.filter { it.content.isNotBlank() }.forEach { m ->
                val answer = if (m.role == ROLE_ASSISTANT) splitThinking(m.content, m.reasoning).answer else m.content
                append(if (m.role == ROLE_ASSISTANT) "**Asistan:**\n\n" else "**Ben:**\n\n")
                val files = Attachments.parse(m.attachments)
                if (files.isNotEmpty()) append("_Ekler: ").append(files.joinToString { it.name }).append("_\n\n")
                append(answer).append("\n\n")
            }
        }
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
class ChatListViewModel(private val c: AppContainer) : ViewModel() {
    val query = MutableStateFlow("")

    val chats: StateFlow<List<ChatEntity>> = query.flatMapLatest { q ->
        val trimmed = q.trim()
        if (trimmed.isEmpty()) c.db.chatDao().observeAll() else c.db.chatDao().search(trimmed)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    fun delete(id: Long, onDeleted: () -> Unit) {
        viewModelScope.launch {
            c.engine.deleteChat(id)
            onDeleted()
        }
    }

    fun rename(id: Long, title: String) {
        viewModelScope.launch { c.db.chatDao().setTitle(id, title.trim().ifBlank { "Yeni sohbet" }, true) }
    }

    fun togglePin(chat: ChatEntity) {
        viewModelScope.launch { c.db.chatDao().setPinned(chat.id, !chat.pinned) }
    }
}

class MemoryViewModel(private val c: AppContainer) : ViewModel() {
    private val dao = c.db.memoryDao()
    val memories: StateFlow<List<MemoryEntity>> = dao.observeAll()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val settings: StateFlow<AppSettings> = c.settings.settings

    fun add(text: String) {
        val clean = text.trim()
        if (clean.isEmpty()) return
        val now = System.currentTimeMillis()
        viewModelScope.launch { dao.insert(MemoryEntity(content = clean, createdAt = now, updatedAt = now)) }
    }

    fun edit(memory: MemoryEntity, text: String) {
        val clean = text.trim()
        if (clean.isEmpty()) return
        viewModelScope.launch { dao.update(memory.copy(content = clean, updatedAt = System.currentTimeMillis())) }
    }

    fun setEnabled(memory: MemoryEntity, enabled: Boolean) {
        viewModelScope.launch { dao.update(memory.copy(enabled = enabled)) }
    }

    fun delete(memory: MemoryEntity) {
        viewModelScope.launch { dao.delete(memory.id) }
    }

    fun clearAll() {
        viewModelScope.launch { dao.deleteAll() }
    }

    fun setMemoryEnabled(enabled: Boolean) = c.settings.update { it.copy(memoryEnabled = enabled) }
    fun setAutoMemory(enabled: Boolean) = c.settings.update { it.copy(autoMemory = enabled) }
}

class SettingsViewModel(private val c: AppContainer) : ViewModel() {
    val settings: StateFlow<AppSettings> = c.settings.settings
    val modelsState: StateFlow<ModelsState> = c.models.state

    var backupBusy by mutableStateOf(false)
    var backupStatus by mutableStateOf<String?>(null)

    fun exportBackup(uri: Uri) = runBackup("Yedek alındı.") {
        val out = c.app.contentResolver.openOutputStream(uri) ?: throw BackupException("Dosya yazılamadı.")
        out.use { c.backup.export(it) }
        "Yedek alındı."
    }

    fun importBackup(uri: Uri) = runBackup("") {
        val input = c.app.contentResolver.openInputStream(uri) ?: throw BackupException("Dosya açılamadı.")
        input.use { c.backup.import(it).summary }
    }

    private fun runBackup(@Suppress("UNUSED_PARAMETER") ok: String, block: suspend () -> String) {
        backupBusy = true
        backupStatus = null
        viewModelScope.launch {
            backupStatus = try {
                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { block() }
            } catch (e: BackupException) {
                "Hata: ${e.message}"
            } catch (e: Exception) {
                "Hata: ${e.message ?: e.javaClass.simpleName}"
            } finally {
                backupBusy = false
            }
        }
    }

    fun update(block: (AppSettings) -> AppSettings) = c.settings.update(block)

    fun refreshModels() {
        viewModelScope.launch { c.models.refresh() }
    }

    fun toggleFavorite(model: String) {
        c.settings.update {
            it.copy(favoriteModels = if (model in it.favoriteModels) it.favoriteModels - model else it.favoriteModels + model)
        }
    }
}

class PersonasViewModel(private val c: AppContainer) : ViewModel() {
    val personas: StateFlow<List<PersonaEntity>> = c.db.personaDao().observeAll()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val templates: StateFlow<List<TemplateEntity>> = c.db.templateDao().observeAll()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    fun savePersona(id: Long, name: String, emoji: String, prompt: String, model: String) {
        if (name.isBlank() || prompt.isBlank()) return
        viewModelScope.launch {
            val e = PersonaEntity(
                id = id, name = name.trim(), emoji = emoji.trim().ifBlank { "🤖" },
                systemPrompt = prompt.trim(), model = model.trim(), createdAt = System.currentTimeMillis(),
            )
            if (id == 0L) c.db.personaDao().insert(e)
            else c.db.personaDao().update(e.copy(createdAt = c.db.personaDao().list().firstOrNull { it.id == id }?.createdAt ?: e.createdAt))
        }
    }

    fun deletePersona(id: Long) { viewModelScope.launch { c.db.personaDao().delete(id) } }

    fun saveTemplate(id: Long, title: String, text: String) {
        if (title.isBlank() || text.isBlank()) return
        viewModelScope.launch {
            val e = TemplateEntity(id = id, title = title.trim(), text = text, createdAt = System.currentTimeMillis())
            if (id == 0L) c.db.templateDao().insert(e)
            else c.db.templateDao().update(e.copy(createdAt = c.db.templateDao().list().firstOrNull { it.id == id }?.createdAt ?: e.createdAt))
        }
    }

    fun deleteTemplate(id: Long) { viewModelScope.launch { c.db.templateDao().delete(id) } }
}
