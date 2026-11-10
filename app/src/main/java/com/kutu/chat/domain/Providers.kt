package com.kutu.chat.domain

/** Sohbet tamamlama (chat/completions) uç noktası OpenAI biçimiyle uyumlu bir model sağlayıcısı. */
data class ProviderDef(
    val id: String,
    val name: String,
    val baseUrl: String,
    /** API anahtarının alındığı sayfa. */
    val keyUrl: String,
    val keyHint: String,
    /** Arayüzde sağlayıcıyı ayırt eden renk (ARGB). */
    val color: Long,
    val note: String = "",
    /** Yanıt uzunluğu sınırı `max_completion_tokens` ile mi gönderilir? (Bazı sağlayıcılar `max_tokens`'ı reddeder.) */
    val completionTokensParam: Boolean = false,
)

/** Bir sağlayıcıdaki bir model. NVIDIA için [key] yalın model kimliğidir (eski kayıtlarla uyum). */
data class ModelRef(val provider: String, val model: String) {
    val key: String get() = if (provider == Providers.NVIDIA) model else "$provider:$model"
    /** Kısa görünen ad: "google/gemma-4-31b-it" → "gemma-4-31b-it". */
    val display: String get() = model.substringAfterLast('/').removePrefix("models/")

    companion object {
        fun parse(key: String): ModelRef {
            val prefix = key.substringBefore(':', "")
            return if (prefix.isNotEmpty() && Providers.byId(prefix) != null && prefix != Providers.NVIDIA) {
                ModelRef(prefix, key.substringAfter(':'))
            } else ModelRef(Providers.NVIDIA, key)
        }
    }
}

object Providers {
    const val NVIDIA = "nvidia"
    const val CUSTOM = "custom"

    val presets: List<ProviderDef> = listOf(
        ProviderDef(NVIDIA, "NVIDIA Build", "https://integrate.api.nvidia.com/v1", "https://build.nvidia.com/settings/api-keys", "nvapi-…", 0xFF76B900,
            "Ücretsiz katman, çok sayıda açık model. Yapay zekâ seslendirmesi de bu anahtarı kullanır."),
        ProviderDef("openrouter", "OpenRouter", "https://openrouter.ai/api/v1", "https://openrouter.ai/keys", "sk-or-…", 0xFF6467F2,
            "Tek anahtarla yüzlerce model (Claude, GPT, Gemini, Llama…); bazıları ücretsiz."),
        ProviderDef("groq", "Groq", "https://api.groq.com/openai/v1", "https://console.groq.com/keys", "gsk_…", 0xFFF55036,
            "Çok hızlı yanıt; ücretsiz katman."),
        ProviderDef("gemini", "Google Gemini", "https://generativelanguage.googleapis.com/v1beta/openai", "https://aistudio.google.com/apikey", "AIza…", 0xFF4285F4,
            "Google AI Studio anahtarı; ücretsiz katman."),
        ProviderDef("openai", "OpenAI", "https://api.openai.com/v1", "https://platform.openai.com/api-keys", "sk-…", 0xFF10A37F,
            "GPT modelleri.", completionTokensParam = true),
        ProviderDef("mistral", "Mistral", "https://api.mistral.ai/v1", "https://console.mistral.ai/api-keys", "…", 0xFFFA520F, "Mistral ve Codestral modelleri."),
        ProviderDef("deepseek", "DeepSeek", "https://api.deepseek.com/v1", "https://platform.deepseek.com/api_keys", "sk-…", 0xFF4D6BFE, "DeepSeek sohbet ve düşünen modelleri."),
        ProviderDef("xai", "xAI", "https://api.x.ai/v1", "https://console.x.ai", "xai-…", 0xFF9AA0A6, "Grok modelleri."),
        ProviderDef("together", "Together AI", "https://api.together.xyz/v1", "https://api.together.ai/settings/api-keys", "…", 0xFF0F6FFF, "Açık kaynak modeller."),
        ProviderDef("cerebras", "Cerebras", "https://api.cerebras.ai/v1", "https://cloud.cerebras.ai", "csk-…", 0xFFF15A29, "Çok hızlı çıkarım; ücretsiz katman."),
        ProviderDef(CUSTOM, "Özel / yerel sunucu", "", "", "isteğe bağlı", 0xFF8E8E93,
            "OpenAI uyumlu herhangi bir sunucu: Ollama, LM Studio, vLLM, kendi ağ geçidiniz…"),
    )

    private val byId = presets.associateBy { it.id }
    fun byId(id: String): ProviderDef? = byId[id]
    fun name(id: String): String = byId[id]?.name ?: id
}
