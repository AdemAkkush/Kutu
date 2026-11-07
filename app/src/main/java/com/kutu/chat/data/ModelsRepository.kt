package com.kutu.chat.data

import com.kutu.chat.data.api.ApiException
import com.kutu.chat.data.api.NvidiaApi
import com.kutu.chat.domain.Capabilities
import com.kutu.chat.domain.CatalogEntry
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.serialization.encodeToString

data class ModelsState(
    val models: List<String> = emptyList(),
    val loading: Boolean = false,
    val error: String? = null,
    /** Küçük harfli model adı → katalog kaydı. Boşsa katalog alınamamıştır. */
    val catalog: Map<String, CatalogEntry> = emptyMap(),
)

/** NVIDIA Build model listesini önbellekler ve yeniler. */
class ModelsRepository(
    private val api: NvidiaApi,
    private val settings: SettingsStore,
) {
    private val _state = MutableStateFlow(
        ModelsState(models = settings.cachedModels(), catalog = decodeCatalog(settings.cachedCatalog())),
    )
    val state: StateFlow<ModelsState> = _state.asStateFlow()

    suspend fun refresh() {
        _state.update { it.copy(loading = true, error = null) }
        try {
            val models = api.listModels(settings.settings.value)
            settings.saveModels(models)
            val catalog = loadCatalog()
            settings.update { heal(it, models, catalog) }
            _state.value = ModelsState(models = models, catalog = catalog)
        } catch (e: ApiException) {
            _state.update { it.copy(loading = false, error = e.message) }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            _state.update { it.copy(loading = false, error = e.message ?: "Model listesi alınamadı") }
        }
    }

    /** Katalog alınamazsa (ağ, özel API adresi) önceki önbellek korunur; model listesi bundan etkilenmez. */
    private suspend fun loadCatalog(): Map<String, CatalogEntry> {
        if (!settings.settings.value.baseUrl.contains("nvidia.com")) return emptyMap()
        return try {
            val entries = api.fetchCatalog()
            if (entries.isEmpty()) _state.value.catalog
            else {
                settings.saveCatalog(NvidiaApi.JSON.encodeToString(entries))
                entries.associateBy { it.name }
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            _state.value.catalog
        }
    }

    companion object {
        fun decodeCatalog(json: String): Map<String, CatalogEntry> =
            if (json.isBlank()) emptyMap()
            else runCatching { NvidiaApi.JSON.decodeFromString<List<CatalogEntry>>(json).associateBy { it.name } }
                .getOrDefault(emptyMap())

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
        fun heal(s: AppSettings, models: List<String>, catalog: Map<String, CatalogEntry> = emptyMap()): AppSettings {
            if (models.isEmpty()) return s
            // Katalogda olmayan modeller büyük olasılıkla kaldırılmıştır (404/410 verenlerle örtüşüyor).
            fun ok(m: String) = m in models && m !in s.unavailableModels &&
                (catalog.isEmpty() || Capabilities.lookup(m, catalog) != null)
            val chatModels = models.filter { isChatModel(it) && ok(it) }
                .sortedByDescending { Capabilities.lookup(it, catalog)?.popularity ?: 0 }
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
