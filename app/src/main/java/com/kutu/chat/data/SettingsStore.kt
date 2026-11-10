package com.kutu.chat.data

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.kutu.chat.domain.ModelRef
import com.kutu.chat.domain.Providers
import com.kutu.chat.domain.TtsEngine
import com.kutu.chat.domain.TtsModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

const val DEFAULT_BASE_URL = "https://integrate.api.nvidia.com/v1"
const val DEFAULT_MODEL = "google/gemma-4-31b-it"
const val DEFAULT_MEMORY_MODEL = "nvidia/nemotron-3.5-bolt-e4b"

val DEFAULT_SYSTEM_PROMPT = """
Sen yardımsever, dürüst ve bilgili bir yapay zeka asistanısın.
- Kullanıcı hangi dilde yazıyorsa o dilde yanıt ver (varsayılan Türkçe).
- Yanıtlarında gerektiğinde Markdown kullan: başlıklar, listeler, tablolar ve dil etiketli kod blokları.
- Emin olmadığın şeyleri uydurma; bilmiyorsan açıkça söyle.
- Kısa sorulara kısa, karmaşık sorulara ayrıntılı ve adım adım yanıt ver.
""".trimIndent()

enum class ThemeMode { SYSTEM, LIGHT, DARK }

data class AppSettings(
    val apiKey: String = "",
    val baseUrl: String = DEFAULT_BASE_URL,
    val defaultModel: String = DEFAULT_MODEL,
    /** Başlık, özet ve hafıza çıkarımı gibi arka plan işleri için kullanılan (küçük/hızlı) model. */
    val memoryModel: String = DEFAULT_MEMORY_MODEL,
    val systemPrompt: String = DEFAULT_SYSTEM_PROMPT,
    val temperature: Float = 0.7f,
    val maxTokens: Int = 4096,
    /** Modele gönderilecek bağlamın yaklaşık token bütçesi. Aşılırsa eski mesajlar özetlenir. */
    val contextTokens: Int = 16000,
    /** Kayıtlı hafızayı sohbetlere ekle. */
    val memoryEnabled: Boolean = true,
    /** Sohbetten kalıcı bilgileri otomatik çıkar ve hafızaya yaz. */
    val autoMemory: Boolean = true,
    val theme: ThemeMode = ThemeMode.SYSTEM,
    val favoriteModels: Set<String> = emptySet(),
    /** 404/410 dönen (kaldırılmış ya da hesaba kapalı) modeller. Seçicide en sona atılır. */
    val unavailableModels: Set<String> = emptySet(),
    /** Resimli istek başarıyla yanıtlanan / reddedilen modeller (uygulamanın öğrendiği görsel desteği). */
    val visionConfirmed: Set<String> = emptySet(),
    val visionRejected: Set<String> = emptySet(),
    val lastChatId: Long = 0,
    /** Yanıtları sesli okuma: cihazın kendi sesi ya da NVIDIA yapay zekâ sesi. */
    val ttsEngine: TtsEngine = TtsEngine.NVIDIA,
    val ttsModel: TtsModel = TtsModel.AUTO,
    /** Magpie için tercih edilen konuşmacı (örn. Aria). */
    val ttsVoice: String = "Aria",
    /** Konuşma hızı (1.0 = normal) ve ses tonu (1.0 = normal); iki motor için de geçerli. */
    val ttsSpeed: Float = 1.0f,
    val ttsPitch: Float = 1.0f,
    /** NVIDIA motoru seçiliyken Türkçe metinleri cihazın (çok sesli) motoruyla oku. */
    val ttsTurkishOnDevice: Boolean = false,
    /** Cihaz motoru için seçilen ses adı (boşsa varsayılan). */
    val ttsDeviceVoice: String = "",
    /** NVIDIA dışındaki sağlayıcıların API anahtarları (sağlayıcı kimliği → anahtar). NVIDIA anahtarı [apiKey]'dir. */
    val providerKeys: Map<String, String> = emptyMap(),
    /** "Özel / yerel sunucu" sağlayıcısının adresi. */
    val customBaseUrl: String = "",
    /** Varsayılan sohbet ve arka plan modellerinin sağlayıcıları. */
    val defaultProvider: String = Providers.NVIDIA,
    val memoryProvider: String = Providers.NVIDIA,
    /** Renkleri duvar kâğıdından al (Android 12+). Kapalıyken Kutu'nun kendi renkleri kullanılır. */
    val dynamicColor: Boolean = false,
) {
    fun keyFor(provider: String): String = if (provider == Providers.NVIDIA) apiKey else providerKeys[provider].orEmpty()

    fun baseUrlFor(provider: String): String = when (provider) {
        Providers.NVIDIA -> baseUrl
        Providers.CUSTOM -> customBaseUrl
        else -> Providers.byId(provider)?.baseUrl.orEmpty()
    }

    /** Kullanılabilir (anahtarı / adresi girilmiş) sağlayıcılar, tanım sırasıyla. */
    val configuredProviders: List<String>
        get() = Providers.presets.map { it.id }.filter {
            if (it == Providers.CUSTOM) customBaseUrl.isNotBlank() else keyFor(it).isNotBlank()
        }

    /** Aynı ayarlar, ama [apiKey]/[baseUrl] seçilen sağlayıcınınkiyle değiştirilmiş (API istemcisi bunları okur). */
    fun forProvider(provider: String): AppSettings = copy(
        // Yerel sunucular çoğunlukla anahtar istemez; boş başlık yerine zararsız bir değer gönderilir.
        apiKey = keyFor(provider).ifBlank { if (provider == Providers.CUSTOM && customBaseUrl.isNotBlank()) "local" else "" },
        baseUrl = baseUrlFor(provider),
    )

    val defaultRef: ModelRef get() = ModelRef(defaultProvider, defaultModel)
    val memoryRef: ModelRef get() = ModelRef(memoryProvider, memoryModel)
}

/**
 * Ayarları saklar. API anahtarı Android Keystore destekli şifreli depoda tutulur;
 * diğer ayarlar normal SharedPreferences içindedir.
 */
class SettingsStore(private val context: Context) {
    private val prefs: SharedPreferences = context.getSharedPreferences("settings", Context.MODE_PRIVATE)
    private val secure: SharedPreferences? = createSecurePrefs(context)
    private var memoryOnlyKey = ""
    private val memoryOnlyKeys = HashMap<String, String>()

    private val _settings = MutableStateFlow(load())
    val settings: StateFlow<AppSettings> = _settings.asStateFlow()

    @Synchronized
    fun update(block: (AppSettings) -> AppSettings) {
        val next = block(_settings.value)
        save(next)
        _settings.value = next
    }

    fun cachedModels(provider: String = Providers.NVIDIA): List<String> =
        prefs.getString(modelsKey(provider), "").orEmpty().split('\n').filter { it.isNotBlank() }

    private fun modelsKey(provider: String) = if (provider == Providers.NVIDIA) KEY_MODELS else "$KEY_MODELS.$provider"

    fun notificationsAsked(): Boolean = prefs.getBoolean("notificationsAsked", false)

    fun markNotificationsAsked() {
        prefs.edit().putBoolean("notificationsAsked", true).apply()
    }

    fun ttsFunctionId(model: String): String? = prefs.getString("ttsFn.$model", null)

    fun saveTtsFunctionId(model: String, id: String) {
        prefs.edit().putString("ttsFn.$model", id).apply()
    }

    fun cachedCatalog(): String = prefs.getString(KEY_CATALOG, "").orEmpty()

    fun saveCatalog(json: String) {
        prefs.edit().putString(KEY_CATALOG, json).apply()
    }

    fun saveModels(models: List<String>, provider: String = Providers.NVIDIA) {
        prefs.edit().putString(modelsKey(provider), models.joinToString("\n")).apply()
    }

    private fun load(): AppSettings {
        val d = AppSettings()
        return AppSettings(
            apiKey = readKey(),
            baseUrl = prefs.getString("baseUrl", d.baseUrl) ?: d.baseUrl,
            defaultModel = prefs.getString("defaultModel", d.defaultModel) ?: d.defaultModel,
            memoryModel = prefs.getString("memoryModel", d.memoryModel) ?: d.memoryModel,
            systemPrompt = prefs.getString("systemPrompt", d.systemPrompt) ?: d.systemPrompt,
            temperature = prefs.getFloat("temperature", d.temperature),
            maxTokens = prefs.getInt("maxTokens", d.maxTokens),
            contextTokens = prefs.getInt("contextTokens", d.contextTokens),
            memoryEnabled = prefs.getBoolean("memoryEnabled", d.memoryEnabled),
            autoMemory = prefs.getBoolean("autoMemory", d.autoMemory),
            theme = runCatching { ThemeMode.valueOf(prefs.getString("theme", "") ?: "") }.getOrDefault(d.theme),
            favoriteModels = prefs.getStringSet("favoriteModels", emptySet())?.toSet() ?: emptySet(),
            unavailableModels = prefs.getStringSet("unavailableModels", emptySet())?.toSet() ?: emptySet(),
            visionConfirmed = prefs.getStringSet("visionConfirmed", emptySet())?.toSet() ?: emptySet(),
            visionRejected = prefs.getStringSet("visionRejected", emptySet())?.toSet() ?: emptySet(),
            lastChatId = prefs.getLong("lastChatId", 0),
            ttsEngine = runCatching { TtsEngine.valueOf(prefs.getString("ttsEngine", "") ?: "") }.getOrDefault(d.ttsEngine),
            ttsModel = runCatching { TtsModel.valueOf(prefs.getString("ttsModel", "") ?: "") }.getOrDefault(d.ttsModel),
            ttsVoice = prefs.getString("ttsVoice", d.ttsVoice) ?: d.ttsVoice,
            ttsSpeed = prefs.getFloat("ttsSpeed", d.ttsSpeed),
            ttsPitch = prefs.getFloat("ttsPitch", d.ttsPitch),
            ttsTurkishOnDevice = prefs.getBoolean("ttsTurkishOnDevice", d.ttsTurkishOnDevice),
            ttsDeviceVoice = prefs.getString("ttsDeviceVoice", d.ttsDeviceVoice) ?: d.ttsDeviceVoice,
            providerKeys = Providers.presets.map { it.id }.filter { it != Providers.NVIDIA }
                .associateWith { readProviderKey(it) }.filterValues { it.isNotBlank() },
            customBaseUrl = prefs.getString("customBaseUrl", "") ?: "",
            defaultProvider = prefs.getString("defaultProvider", d.defaultProvider) ?: d.defaultProvider,
            memoryProvider = prefs.getString("memoryProvider", d.memoryProvider) ?: d.memoryProvider,
            dynamicColor = prefs.getBoolean("dynamicColor", d.dynamicColor),
        )
    }

    private fun save(s: AppSettings) {
        writeKey(s.apiKey)
        writeProviderKeys(s.providerKeys)
        prefs.edit()
            .putString("customBaseUrl", s.customBaseUrl)
            .putString("defaultProvider", s.defaultProvider)
            .putString("memoryProvider", s.memoryProvider)
            .putBoolean("dynamicColor", s.dynamicColor)
            .putString("baseUrl", s.baseUrl)
            .putString("defaultModel", s.defaultModel)
            .putString("memoryModel", s.memoryModel)
            .putString("systemPrompt", s.systemPrompt)
            .putFloat("temperature", s.temperature)
            .putInt("maxTokens", s.maxTokens)
            .putInt("contextTokens", s.contextTokens)
            .putBoolean("memoryEnabled", s.memoryEnabled)
            .putBoolean("autoMemory", s.autoMemory)
            .putString("theme", s.theme.name)
            .putStringSet("favoriteModels", s.favoriteModels)
            .putStringSet("unavailableModels", s.unavailableModels)
            .putStringSet("visionConfirmed", s.visionConfirmed)
            .putStringSet("visionRejected", s.visionRejected)
            .putLong("lastChatId", s.lastChatId)
            .putString("ttsEngine", s.ttsEngine.name)
            .putString("ttsModel", s.ttsModel.name)
            .putString("ttsVoice", s.ttsVoice)
            .putFloat("ttsSpeed", s.ttsSpeed)
            .putFloat("ttsPitch", s.ttsPitch)
            .putBoolean("ttsTurkishOnDevice", s.ttsTurkishOnDevice)
            .putString("ttsDeviceVoice", s.ttsDeviceVoice)
            .apply()
    }

    private fun readKey(): String = secure?.getString("apiKey", "").orEmpty().ifEmpty { memoryOnlyKey }

    private fun readProviderKey(id: String): String =
        secure?.getString("key.$id", "").orEmpty().ifEmpty { memoryOnlyKeys[id].orEmpty() }

    private fun writeProviderKeys(keys: Map<String, String>) {
        val ids = Providers.presets.map { it.id }.filter { it != Providers.NVIDIA }
        if (secure != null) {
            val e = secure.edit()
            ids.forEach { id -> keys[id]?.takeIf { it.isNotBlank() }?.let { e.putString("key.$id", it) } ?: e.remove("key.$id") }
            e.apply()
        } else {
            memoryOnlyKeys.clear(); memoryOnlyKeys.putAll(keys)
        }
    }

    private fun writeKey(key: String) {
        if (secure != null) secure.edit().putString("apiKey", key).apply() else memoryOnlyKey = key
    }

    private companion object {
        const val KEY_MODELS = "cachedModels"
        const val KEY_CATALOG = "cachedCatalog"

        fun createSecurePrefs(context: Context): SharedPreferences? {
            fun build() = EncryptedSharedPreferences.create(
                context,
                "secure_settings",
                MasterKey.Builder(context).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build(),
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
            )
            return try {
                build()
            } catch (e: Exception) {
                // Keystore anahtarı bozulduysa dosyayı sıfırlayıp bir kez daha dene.
                try {
                    context.deleteSharedPreferences("secure_settings")
                    build()
                } catch (e2: Exception) {
                    null
                }
            }
        }
    }
}
