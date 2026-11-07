package com.kutu.chat.domain

import kotlin.math.ln

/**
 * Uzun belgelerden, kullanıcının sorusuyla en ilgili bölümleri seçer (hafif bir anahtar sözcük araması).
 * Belge bütçeye sığıyorsa olduğu gibi döner. Türkçe ekleri tolere etmek için sözcükler 5 harfli köke indirgenir.
 */
object DocExcerpt {
    data class Result(val text: String, val trimmed: Boolean, val shownChars: Int, val totalChars: Int)

    private const val CHUNK = 1500
    private val STOP = setOf(
        "ve", "bir", "bu", "şu", "için", "ile", "mi", "mı", "mu", "mü", "ne", "nasıl", "neden", "ama", "veya", "gibi",
        "the", "and", "for", "with", "that", "this", "what", "how", "are", "was", "bana", "bunu", "şunu", "olan", "daha", "çok",
    )
    private val MARKER = Regex("^(---\\s*(Sayfa|Slayt)\\s+\\d+\\s*---|##\\s*Sayfa:.*)$")

    fun select(text: String, query: String, maxChars: Int): Result {
        if (text.length <= maxChars) return Result(text, false, text.length, text.length)
        val chunks = split(text)
        val stems = terms(query)
        val scores = DoubleArray(chunks.size)
        if (stems.isNotEmpty()) {
            val tokens = chunks.map { tokenSet(it.body) }
            for (stem in stems) {
                val matches = tokens.map { set -> if (stem.exact) stem.value in set else set.any { it.startsWith(stem.value) } }
                val df = matches.count { it }
                if (df == 0) continue
                val idf = ln(1.0 + chunks.size.toDouble() / (1 + df))
                matches.forEachIndexed { i, hit ->
                    if (hit) scores[i] += idf * minOf(5, countHits(chunks[i].body, stem)).toDouble().coerceAtLeast(1.0)
                }
            }
        }
        val chosen = LinkedHashSet<Int>()
        var used = 0
        fun add(i: Int): Boolean {
            val cost = chunks[i].body.length + chunks[i].label.length + 4
            if (i in chosen || (used + cost > maxChars && chosen.isNotEmpty())) return false
            chosen += i; used += cost; return true
        }
        add(0)   // Başlık / ilk satırlar (Excel'de sütun başlıkları) her zaman dahil
        val ranked = scores.withIndex().filter { it.value > 0 }.sortedByDescending { it.value }.map { it.index }
        if (ranked.isEmpty()) {
            for (i in chunks.indices) if (!add(i)) break          // eşleşme yok: belgenin başı
        } else {
            for (i in ranked) add(i)
            // Kalan boşluğa seçilenlerin komşularını ekle (bağlam).
            for (i in ranked) { if (i + 1 < chunks.size) add(i + 1); if (i > 0) add(i - 1) }
        }
        val out = StringBuilder()
        var prev = -2
        for (i in chosen.sorted()) {
            if (prev >= 0 && i != prev + 1) out.append("\n[… atlandı …]\n")
            if (chunks[i].label.isNotEmpty() && !chunks[i].body.trimStart().startsWith("---") && !chunks[i].body.trimStart().startsWith("##")) {
                out.append('(').append(chunks[i].label).append(")\n")
            }
            out.append(chunks[i].body).append('\n')
            prev = i
        }
        val shown = out.toString().trimEnd()
        return Result(shown, true, shown.length, text.length)
    }

    private class Chunk(val body: String, val label: String)
    private class Term(val value: String, val exact: Boolean)

    private fun split(text: String): List<Chunk> {
        val chunks = ArrayList<Chunk>()
        val buf = StringBuilder()
        var label = ""
        var bufLabel = ""
        fun flush() { if (buf.isNotEmpty()) { chunks.add(Chunk(buf.toString().trimEnd(), bufLabel)); buf.setLength(0) } }
        for (line in text.lineSequence()) {
            if (MARKER.matches(line.trim())) label = line.trim().trim('-', '#', ' ')
            if (buf.isEmpty()) bufLabel = label
            buf.append(line).append('\n')
            if (buf.length >= CHUNK) flush()
        }
        flush()
        return chunks.ifEmpty { listOf(Chunk(text, "")) }
    }

    private fun words(s: String): List<String> =
        Regex("[\\p{L}\\p{N}]+").findAll(s.lowercase()).map { it.value }.toList()

    private fun terms(query: String): List<Term> = words(query)
        .filter { it !in STOP && (it.length >= 3 || it.any(Char::isDigit)) }
        .distinct()
        .map { w -> if (w.all(Char::isDigit)) Term(w, true) else Term(w.take(5), false) }
        .take(12)

    private fun tokenSet(body: String): Set<String> = words(body).toHashSet()

    private fun countHits(body: String, t: Term): Int =
        words(body).count { if (t.exact) it == t.value else it.startsWith(t.value) }
}
