package com.kutu.chat.domain

import java.time.LocalDate
import kotlinx.serialization.Serializable
import java.time.format.DateTimeFormatter

/** Bir modelin güçlü olduğu alanlar. Model seçicide filtre ve rozet olarak gösterilir. */
enum class Cap(val label: String, val emoji: String) {
    CHAT("Sohbet", "💬"),
    REASONING("Düşünen", "🧠"),
    VISION("Görsel", "👁"),
    CODE("Kod", "💻"),
    TRANSLATE("Çeviri", "🌍"),
    DOCUMENT("Belge", "📄"),
    FAST("Hızlı", "⚡"),
    AGENT("Ajan", "🛠"),
    SAFETY("Güvenlik", "🛡"),
    EMBED("Gömme", "🔎"),
}

/** NVIDIA API kataloğundaki (build.nvidia.com) tek bir uç nokta kaydı. */
@Serializable
data class CatalogEntry(
    val name: String,
    val display: String,
    val publisher: String,
    val labels: List<String>,
    /** Kaldırılma tarihi (ISO, yyyy-MM-dd) ya da boş. */
    val deprecation: String,
    /** Son ayki API çağrı sayısı (popülerlik). */
    val popularity: Long,
)

/** Bir model kimliği için birleştirilmiş bilgi. */
data class ModelMeta(
    val id: String,
    val display: String,
    val caps: Set<Cap>,
    /** Katalogda var mı? Katalog hiç yüklenmediyse null. Katalogda olmayan modeller büyük olasılıkla kaldırılmıştır. */
    val inCatalog: Boolean?,
    val deprecation: LocalDate?,
    val popularity: Long,
) {
    fun has(c: Cap) = c in caps
    val chatCapable get() = Cap.SAFETY !in caps && Cap.EMBED !in caps
}

object Capabilities {
    private val LABEL_MAP: Map<Cap, Set<String>> = mapOf(
        Cap.REASONING to setOf("reasoning", "thinking"),
        Cap.VISION to setOf(
            "image-to-text", "visual qa", "vlm", "vision language model", "multimodal", "multimodal moe", "omni",
            "visual grounding", "image captioning", "visual question answering", "vision assistant", "video understanding",
        ),
        Cap.CODE to setOf("coding", "agentic coding"),
        Cap.TRANSLATE to setOf("translation", "text translation", "neural machine translation"),
        Cap.DOCUMENT to setOf("document parsing", "optical character recognition", "ocr", "table extraction", "text and table extraction"),
        Cap.FAST to setOf("fast"),
        Cap.AGENT to setOf("agent", "agentic", "tool use", "agentic ai", "long-running agents"),
        Cap.SAFETY to setOf(
            "safety and moderation", "llm safety", "content safety", "guardrail", "content moderation", "dialogue safety",
            "nemoguard", "multilingual guard model", "llm security", "prompt injection",
        ),
        Cap.EMBED to setOf("text-to-embedding", "embedding", "reranking", "nemotron retriever", "nemo retriever"),
        Cap.CHAT to setOf("chat", "text-to-text", "large language models", "language generation"),
    )

    fun fromLabels(labels: Collection<String>): Set<Cap> {
        val lower = labels.map { it.trim().lowercase() }.toSet()
        return LABEL_MAP.filterValues { keys -> keys.any { it in lower } }.keys
    }

    private val ID_RULES: List<Pair<Cap, Regex>> = listOf(
        Cap.VISION to Regex("vision|-vl\\b|-vl-|vila|neva|fuyu|kosmos|deplot|llava|pixtral|paligemma|omni|cosmos-reason|gemma-[34]|gpt-4o|gpt-4\\.1|gpt-5|claude|gemini|grok-[4-9]|qwen.*vl|llama-4"),
        Cap.CODE to Regex("code|coder|starcoder|codestral|codellama|devstral"),
        Cap.REASONING to Regex("reason|think|\\br1\\b|-r1-|qwq|gpt-oss|nemotron-3|\\bo[134]\\b|o[134]-|gpt-5|magistral"),
        Cap.TRANSLATE to Regex("translate"),
        Cap.DOCUMENT to Regex("parse|ocr"),
        Cap.FAST to Regex("flash|nano|lightning|bolt|mini\\b|small|-xs-|e4b|-2b|-3b|-4b|-7b|-8b"),
        Cap.SAFETY to Regex("guard|safety|topic-control|content-safety"),
        Cap.EMBED to Regex("embed|rerank|reward|retriever|nvclip|clip\\b"),
    )

    /**
     * Gerçek API'de resim göndererek doğrulanmış sonuçlar (katalog etiketleri bunları her zaman içermiyor).
     * Uygulama ayrıca kullanım sırasında kendi öğrendiklerini ekler.
     */
    val VERIFIED_VISION = setOf(
        "google/gemma-4-31b-it", "meta/llama-3.2-90b-vision-instruct",
        "nvidia/nemotron-3.5-super-vl-120b-a12b", "minimaxai/minimax-m3.1-flash",
    )
    val VERIFIED_NO_VISION = setOf("poolside/laguna-xs-2.1")

    /** Katalogda bulunamayan modeller için kimlik adından tahmin. */
    fun fromId(id: String): Set<Cap> {
        val lower = id.lowercase()
        return ID_RULES.filter { (_, re) -> re.containsMatchIn(lower) }.map { it.first }.toSet()
    }

    /**
     * Katalog etiketleri + kimlik tahmini + uygulamanın öğrendiği (resim gönderince başarılı/başarısız) bilgi.
     * Güvenlik/gömme modelleri sohbet modeli sayılmaz.
     */
    fun describe(
        id: String,
        catalog: Map<String, CatalogEntry>,
        visionConfirmed: Set<String> = emptySet(),
        visionRejected: Set<String> = emptySet(),
        /** Öğrenilmiş kümelerdeki anahtar (NVIDIA dışı sağlayıcılarda "sağlayıcı:model"). */
        key: String = id,
    ): ModelMeta {
        val entry = lookup(id, catalog)
        val labelCaps = entry?.let { fromLabels(it.labels) }.orEmpty()
        val idCaps = fromId(id)
        // Katalog varsa onun etiketine güvenilir; yalnızca eksik alanlar kimlikten tamamlanır.
        val caps = LinkedHashSet<Cap>()
        caps += labelCaps
        if (entry == null || labelCaps.isEmpty()) caps += idCaps
        else caps += idCaps.filter { it == Cap.FAST || it == Cap.CODE || it == Cap.VISION }   // etiketi eksik ama adı belli olanlar
        if (id in VERIFIED_VISION) caps += Cap.VISION
        if (id in VERIFIED_NO_VISION) caps -= Cap.VISION
        if (key in visionConfirmed) caps += Cap.VISION
        if (key in visionRejected) caps -= Cap.VISION
        if (Cap.SAFETY !in caps && Cap.EMBED !in caps && Cap.CHAT !in caps) caps += Cap.CHAT
        return ModelMeta(
            id = id,
            display = entry?.display?.takeIf { it.isNotBlank() } ?: id.substringAfterLast('/'),
            caps = caps,
            inCatalog = if (catalog.isEmpty()) null else entry != null,
            deprecation = entry?.deprecation?.takeIf { it.isNotBlank() }?.let { runCatching { LocalDate.parse(it) }.getOrNull() },
            popularity = entry?.popularity ?: 0,
        )
    }

    /** Model kimliğinin ("yayıncı/ad") katalogdaki karşılığı; ad büyük/küçük harf duyarsız eşleşir. */
    fun lookup(id: String, catalog: Map<String, CatalogEntry>): CatalogEntry? {
        if (catalog.isEmpty()) return null
        return catalog[id.substringAfter('/').lowercase()]
    }

    /** Görev için en iyi seçenek: canlı, kullanılabilir, yakında kaldırılmayacak, en popüler. */
    fun best(
        cap: Cap,
        models: List<ModelMeta>,
        unavailable: Set<String>,
        today: LocalDate = LocalDate.now(),
    ): ModelMeta? = models
        .filter { cap in it.caps && it.chatCapable && it.id !in unavailable && it.inCatalog != false }
        .filter { it.deprecation == null || it.deprecation.isAfter(today.plusDays(30)) }
        .maxByOrNull { it.popularity }

    private val DATE_FMT = DateTimeFormatter.ofPattern("d.MM.yyyy")
    fun formatDate(d: LocalDate): String = d.format(DATE_FMT)
}
