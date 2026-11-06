package com.kutu.chat.domain

/** Modelin "düşünce" kısmını (reasoning) cevaptan ayırır. */
data class ThinkSplit(
    val thinking: String,
    val answer: String,
    /** Düşünme hâlâ sürüyor (kapanış etiketi henüz gelmedi). */
    val thinkingOpen: Boolean,
)

private const val OPEN = "<think>"
private const val CLOSE = "</think>"

/**
 * [raw] içindeki `<think>…</think>` bloklarını ve ayrıca API'nin `reasoning_content`
 * alanından gelen [reasoningField] metnini birleştirip ayırır.
 */
fun splitThinking(raw: String, reasoningField: String = ""): ThinkSplit {
    val open = raw.indexOf(OPEN)
    val close = raw.indexOf(CLOSE)
    val thinking: String
    val answer: String
    var openThinking = false
    when {
        open >= 0 && close > open -> {
            thinking = raw.substring(open + OPEN.length, close)
            answer = raw.substring(0, open) + raw.substring(close + CLOSE.length)
        }
        open >= 0 -> {
            thinking = raw.substring(open + OPEN.length)
            answer = raw.substring(0, open)
            openThinking = true
        }
        close >= 0 -> {
            // Bazı modeller açılış etiketini şablona gömer; yalnızca kapanış etiketi gelir.
            thinking = raw.substring(0, close)
            answer = raw.substring(close + CLOSE.length)
        }
        else -> {
            thinking = ""
            answer = raw
        }
    }
    val allThinking = listOf(reasoningField, thinking).filter { it.isNotBlank() }.joinToString("\n\n")
    return ThinkSplit(
        thinking = allThinking.trim(),
        answer = answer.trim(),
        thinkingOpen = openThinking || (answer.isBlank() && reasoningField.isNotBlank() && raw.isBlank()),
    )
}

/** Geçmiş asistan mesajlarını modele geri gönderirken düşünce kısmını atar. */
fun stripThinking(content: String): String = splitThinking(content).answer
