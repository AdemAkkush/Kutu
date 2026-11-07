package com.kutu.chat.domain

import com.kutu.chat.data.api.RivaVoices

enum class TtsEngine { DEVICE, NVIDIA }

/** AUTO: Türkçe ve Magpie'nin desteklemediği diller Chatterbox'a, diğerleri Magpie'ye gider. */
enum class TtsModel(val label: String, val functionName: String, val defaultFunctionId: String, val maxChars: Int) {
    AUTO("Otomatik", "", "", 350),
    /** Sunucu 500 karakterde hata verir (gerçek serviste ölçüldü); küçük bir güvenlik payı bırakılır. */
    CHATTERBOX("Chatterbox", "ai-chatterbox-multilingual-tts", "ddacc747-1269-4fab-bfd9-8f593dead106", 470),
    /** 6000 karakter tek istekte sorunsuz okundu; bu yüzden uzun parçalar kullanılır. */
    MAGPIE("Magpie", "ai-magpie-tts-multilingual", "877104f7-e885-42b9-8de8-f6e4c6303969", 2500),
}

/** Magpie'de çoğu dilde bulunan konuşmacılar (dile göre liste sunucudan doğrulanır). */
val MAGPIE_SPEAKERS = listOf("Aria", "Mia", "Sofia", "Louise", "Isabela", "Jason", "Leo", "Ray", "Diego", "Pascal")

/** Hangi metni hangi NVIDIA ses modeliyle, hangi dilde ve hangi sesle okuyacağımızı belirler. */
object TtsPlanner {
    data class Plan(val model: TtsModel, val language: String)

    val CHATTERBOX_LANGS = mapOf(
        "ar" to "ar-SA", "da" to "da-DK", "de" to "de-DE", "el" to "el-GR", "en" to "en-US", "es" to "es-ES", "fi" to "fi-FI",
        "fr" to "fr-FR", "he" to "he-IL", "hi" to "hi-IN", "it" to "it-IT", "ja" to "ja-JP", "ko" to "ko-KR", "ms" to "ms-MY",
        "nl" to "nl-NL", "nb" to "nb-NO", "no" to "nb-NO", "pl" to "pl-PL", "pt" to "pt-BR", "ru" to "ru-RU", "sv" to "sv-SE",
        "sw" to "sw-KE", "tr" to "tr-TR", "zh" to "zh-CN",
    )
    val MAGPIE_LANGS = mapOf(
        "en" to "en-US", "es" to "es-US", "fr" to "fr-FR", "de" to "de-DE", "zh" to "zh-CN", "vi" to "vi-VN", "it" to "it-IT",
        "hi" to "hi-IN", "ja" to "ja-JP", "ko" to "ko-KR", "ar" to "ar-XA", "pt" to "pt-BR",
    )

    private val STOPWORDS: Map<String, Set<String>> = mapOf(
        "tr" to setOf("ve", "bir", "bu", "için", "ile", "çok", "gibi", "ama", "olarak", "daha", "ne", "da", "de", "mi", "var", "yok", "ben", "sen", "biz", "nasıl", "merhaba", "evet", "hayır", "değil"),
        "en" to setOf("the", "and", "is", "are", "of", "to", "in", "that", "it", "with", "for", "this", "you", "your", "have", "not", "be", "can", "will", "hello", "what", "how", "i"),
        "es" to setOf("el", "la", "los", "las", "de", "que", "y", "en", "un", "una", "es", "por", "con", "para", "hola", "como", "pero", "muy"),
        "fr" to setOf("le", "la", "les", "des", "et", "est", "un", "une", "de", "du", "pour", "avec", "que", "bonjour", "dans", "pas", "vous"),
        "de" to setOf("der", "die", "das", "und", "ist", "nicht", "ein", "eine", "mit", "für", "ich", "sie", "zu", "den", "auf", "hallo"),
        "it" to setOf("il", "lo", "la", "che", "di", "e", "un", "una", "per", "con", "non", "sono", "ciao", "come", "della"),
        "pt" to setOf("o", "a", "os", "as", "de", "que", "e", "um", "uma", "para", "com", "não", "olá", "como", "uma"),
    )

    /** Komut dosyasına ve sık kullanılan sözcüklere bakarak dili (ISO-639-1) tahmin eder. */
    fun detectLanguage(text: String, deviceLanguage: String): String {
        val sample = text.take(600)
        var kana = 0; var han = 0; var hangul = 0; var arabic = 0; var hebrew = 0; var deva = 0; var cyr = 0; var greek = 0; var latin = 0
        for (ch in sample) {
            when (Character.UnicodeScript.of(ch.code)) {
                Character.UnicodeScript.HIRAGANA, Character.UnicodeScript.KATAKANA -> kana++
                Character.UnicodeScript.HAN -> han++
                Character.UnicodeScript.HANGUL -> hangul++
                Character.UnicodeScript.ARABIC -> arabic++
                Character.UnicodeScript.HEBREW -> hebrew++
                Character.UnicodeScript.DEVANAGARI -> deva++
                Character.UnicodeScript.CYRILLIC -> cyr++
                Character.UnicodeScript.GREEK -> greek++
                Character.UnicodeScript.LATIN -> latin++
                else -> {}
            }
        }
        val letters = kana + han + hangul + arabic + hebrew + deva + cyr + greek + latin
        if (letters == 0) return deviceLanguage
        val nonLatin = mapOf("ja" to kana, "ko" to hangul, "ar" to arabic, "he" to hebrew, "hi" to deva, "ru" to cyr, "el" to greek)
        if (kana > 0 && kana + han > letters / 5) return "ja"
        nonLatin.entries.filter { it.key != "ja" }.maxByOrNull { it.value }?.let { if (it.value > letters / 3) return it.key }
        if (han > letters / 3) return "zh"

        val words = Regex("[\\p{L}]+").findAll(sample.lowercase()).map { it.value }.toList()
        val scores = STOPWORDS.mapValues { (_, set) -> words.count { it in set } }.toMutableMap()
        if (Regex("[çğıöşüÇĞİÖŞÜ]").findAll(sample).count() >= 2) scores["tr"] = (scores["tr"] ?: 0) + 3
        val best = scores.maxByOrNull { it.value }
        return if (best != null && best.value >= 1 && scores.values.count { it == best.value } == 1) best.key else deviceLanguage
    }

    fun plan(text: String, deviceLanguage: String, preferred: TtsModel): Plan? {
        val lang = detectLanguage(text, deviceLanguage)
        val chatterbox = CHATTERBOX_LANGS[lang]?.let { Plan(TtsModel.CHATTERBOX, it) }
        val magpie = MAGPIE_LANGS[lang]?.let { Plan(TtsModel.MAGPIE, it) }
        return when (preferred) {
            TtsModel.CHATTERBOX -> chatterbox ?: magpie
            TtsModel.MAGPIE -> magpie ?: chatterbox
            TtsModel.AUTO -> if (lang == "tr") chatterbox else magpie ?: chatterbox
        }
    }

    /** Sunucudaki alt ses listesinden bu plan için gerçek ses adını üretir; yoksa null. */
    fun voiceName(plan: Plan, voices: RivaVoices, preferredSpeaker: String): String? = when (plan.model) {
        TtsModel.MAGPIE -> {
            val prefix = plan.language.uppercase() + "."
            val speakers = voices.subvoices.filter { it.startsWith(prefix) }.map { it.removePrefix(prefix) }
            val pick = preferredSpeaker.takeIf { it in speakers } ?: speakers.firstOrNull { '.' !in it } ?: speakers.firstOrNull()
            pick?.let { "${voices.voiceName}.${plan.language.uppercase()}.$it" }
        }
        else -> voices.subvoices.firstOrNull { it.startsWith(plan.language + ".") }?.let { "${voices.voiceName}.$it" }
    }
}
