package com.kutu.chat.data

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

const val DEFAULT_BASE_URL = "https://integrate.api.nvidia.com/v1"
const val DEFAULT_MODEL = "meta/llama-3.3-70b-instruct"
const val DEFAULT_MEMORY_MODEL = "meta/llama-3.1-8b-instruct"

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
    val lastChatId: Long = 0,
)

/**
 * Ayarları saklar. API anahtarı Android Keystore destekli şifreli depoda tutulur;
 * diğer ayarlar normal SharedPreferences içindedir.
 */
class SettingsStore(private val context: Context) {
    private val prefs: SharedPreferences = context.getSharedPreferences("settings", Context.MODE_PRIVATE)
    private val secure: SharedPreferences? = createSecurePrefs(context)
    private var memoryOnlyKey = ""

    private val _settings = MutableStateFlow(load())
    val settings: StateFlow<AppSettings> = _settings.asStateFlow()

    @Synchronized
    fun update(block: (AppSettings) -> AppSettings) {
        val next = block(_settings.value)
        save(next)
        _settings.value = next
    }

    fun cachedModels(): List<String> =
        prefs.getString(KEY_MODELS, "").orEmpty().split('\n').filter { it.isNotBlank() }

    fun saveModels(models: List<String>) {
        prefs.edit().putString(KEY_MODELS, models.joinToString("\n")).apply()
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
            lastChatId = prefs.getLong("lastChatId", 0),
        )
    }

    private fun save(s: AppSettings) {
        writeKey(s.apiKey)
        prefs.edit()
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
            .putLong("lastChatId", s.lastChatId)
            .apply()
    }

    private fun readKey(): String = secure?.getString("apiKey", "").orEmpty().ifEmpty { memoryOnlyKey }

    private fun writeKey(key: String) {
        if (secure != null) secure.edit().putString("apiKey", key).apply() else memoryOnlyKey = key
    }

    private companion object {
        const val KEY_MODELS = "cachedModels"

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
