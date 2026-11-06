package com.kutu.chat.data

import com.kutu.chat.data.api.ApiException
import com.kutu.chat.data.api.NvidiaApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

data class ModelsState(
    val models: List<String> = emptyList(),
    val loading: Boolean = false,
    val error: String? = null,
)

/** NVIDIA Build model listesini önbellekler ve yeniler. */
class ModelsRepository(
    private val api: NvidiaApi,
    private val settings: SettingsStore,
) {
    private val _state = MutableStateFlow(ModelsState(models = settings.cachedModels()))
    val state: StateFlow<ModelsState> = _state.asStateFlow()

    suspend fun refresh() {
        _state.update { it.copy(loading = true, error = null) }
        try {
            val models = api.listModels(settings.settings.value)
            settings.saveModels(models)
            _state.value = ModelsState(models = models)
        } catch (e: ApiException) {
            _state.update { it.copy(loading = false, error = e.message) }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            _state.update { it.copy(loading = false, error = e.message ?: "Model listesi alınamadı") }
        }
    }

    companion object {
        private val NON_CHAT = listOf("embed", "rerank", "reward", "retriever", "nvclip", "clip", "-parse", "paddleocr", "whisper", "tts", "riva")

        /** Sohbete uygun olmayan (embedding, rerank vb.) modelleri ayıklar. */
        fun isChatModel(id: String): Boolean {
            val lower = id.lowercase()
            return NON_CHAT.none { lower.contains(it) }
        }
    }
}
