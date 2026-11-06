package com.kutu.chat.ui

import androidx.compose.runtime.getValue
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
import com.kutu.chat.domain.EngineEvent
import com.kutu.chat.domain.Generation
import com.kutu.chat.domain.splitThinking
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

@OptIn(ExperimentalCoroutinesApi::class)
class ChatViewModel(private val c: AppContainer) : ViewModel() {
    private val chatDao = c.db.chatDao()
    private val messageDao = c.db.messageDao()

    /** null = henüz oluşturulmamış yeni sohbet (ilk mesajda oluşturulur). */
    val currentChatId = MutableStateFlow<Long?>(null)
    val ready = MutableStateFlow(false)
    private val pendingModel = MutableStateFlow<String?>(null)

    var input by mutableStateOf("")

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

    val activeModel: StateFlow<String> = combine(chat, pendingModel, c.settings.settings) { chat, pending, s ->
        chat?.model ?: pending ?: s.defaultModel
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), c.settings.settings.value.defaultModel)

    init {
        viewModelScope.launch {
            // "Kaldığımız yerden devam": en son açık sohbeti geri yükle.
            val last = c.settings.settings.value.lastChatId
            val existing = if (last > 0) chatDao.get(last) else null
            currentChatId.value = (existing ?: chatDao.latest())?.id
            ready.value = true
        }
        if (modelsState.value.models.isEmpty() && c.settings.settings.value.apiKey.isNotBlank()) {
            refreshModels()
        }
    }

    fun refreshModels() {
        viewModelScope.launch { c.models.refresh() }
    }

    fun newChat() {
        currentChatId.value = null
        pendingModel.value = null
        input = ""
    }

    fun openChat(id: Long) {
        currentChatId.value = id
        c.settings.update { it.copy(lastChatId = id) }
    }

    fun send() {
        val text = input.trim()
        if (text.isEmpty() || generation.value != null) return
        input = ""
        viewModelScope.launch {
            val id = currentChatId.value ?: c.engine.createChat(activeModel.value).also {
                currentChatId.value = it
                c.settings.update { s -> s.copy(lastChatId = it) }
            }
            c.engine.send(id, text)
        }
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
            c.engine.stop(id)
            chatDao.delete(id)
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
            c.engine.stop(id)
            c.db.chatDao().delete(id)
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
