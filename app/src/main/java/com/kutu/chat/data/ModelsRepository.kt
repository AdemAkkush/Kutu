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
            settings.update { heal(it, models) }
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
        private val NON_CHAT = listOf(
            "embed", "rerank", "reward", "retriever", "nvclip", "clip", "parse", "paddleocr", "whisper", "tts",
            "guard", "safety", "topic-control", "deplot", "kosmos", "fuyu", "neva", "vila", "calibration", "synthetic-video",
        )

        val PREFERRED_CHAT = listOf("google/gemma-4-31b-it", "minimaxai/minimax-m3.1-flash", "nvidia/nemotron-3-ultra-550b-a55b", "poolside/laguna-xs-2.1")
        val PREFERRED_BACKGROUND = listOf("nvidia/nemotron-3.5-bolt-e4b", "poolside/laguna-xs-2.1", "minimaxai/minimax-m3.1-flash")

        /**
         * Seçili sohbet/arka plan modeli listede yoksa ya da çalışmıyorsa çalışan bir modele geçirir.
         * (NVIDIA modelleri sık sık kaldırıyor; eski bir seçim uygulamayı bozmamalı.)
         */
        fun heal(s: AppSettings, models: List<String>): AppSettings {
            if (models.isEmpty()) return s
            fun ok(m: String) = m in models && m !in s.unavailableModels
            val chatModels = models.filter { isChatModel(it) && it !in s.unavailableModels }
            val chat = if (ok(s.defaultModel)) s.defaultModel
                else PREFERRED_CHAT.firstOrNull(::ok) ?: chatModels.firstOrNull() ?: s.defaultModel
            val background = if (ok(s.memoryModel)) s.memoryModel
                else PREFERRED_BACKGROUND.firstOrNull(::ok) ?: chat
            return s.copy(defaultModel = chat, memoryModel = background)
        }

        /** Sohbete uygun olmayan (embedding, rerank vb.) modelleri ayıklar. */
        fun isChatModel(id: String): Boolean {
            val lower = id.lowercase()
            return NON_CHAT.none { lower.contains(it) }
        }
    }
}
