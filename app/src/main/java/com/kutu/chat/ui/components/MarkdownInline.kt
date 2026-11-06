package com.kutu.chat.ui.components

import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withLink
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.graphics.Color

data class InlineStyles(
    val codeBackground: Color,
    val linkColor: Color,
)

/** `kod`, **kalın**, *italik*, ~~üstü çizili~~, [bağlantı](url) ve çıplak URL'leri destekler. */
fun buildInline(text: String, styles: InlineStyles): AnnotatedString =
    AnnotatedString.Builder().also { appendInline(it, text, styles) }.toAnnotatedString()

private fun appendInline(out: AnnotatedString.Builder, text: String, styles: InlineStyles) {
    var i = 0
    val n = text.length
    val plain = StringBuilder()
    fun flush() {
        if (plain.isNotEmpty()) {
            out.append(plain.toString())
            plain.clear()
        }
    }

    while (i < n) {
        val c = text[i]

        // Kaçış
        if (c == '\\' && i + 1 < n && text[i + 1] in "\\`*_{}[]()#+-.!~|>") {
            plain.append(text[i + 1])
            i += 2
            continue
        }

        // Satır içi kod
        if (c == '`') {
            val end = text.indexOf('`', i + 1)
            if (end > i) {
                flush()
                out.withStyle(
                    SpanStyle(fontFamily = FontFamily.Monospace, background = styles.codeBackground),
                ) { append(text.substring(i + 1, end)) }
                i = end + 1
                continue
            }
        }

        // Kalın
        if ((text.startsWith("**", i) || text.startsWith("__", i))) {
            val marker = text.substring(i, i + 2)
            val end = text.indexOf(marker, i + 2)
            if (end > i + 2) {
                flush()
                out.withStyle(SpanStyle(fontWeight = FontWeight.Bold)) {
                    appendInline(this, text.substring(i + 2, end), styles)
                }
                i = end + 2
                continue
            }
        }

        // Üstü çizili
        if (text.startsWith("~~", i)) {
            val end = text.indexOf("~~", i + 2)
            if (end > i + 2) {
                flush()
                out.withStyle(SpanStyle(textDecoration = TextDecoration.LineThrough)) {
                    appendInline(this, text.substring(i + 2, end), styles)
                }
                i = end + 2
                continue
            }
        }

        // İtalik (* ya da _). snake_case ve "2 * 3" gibi durumları bozmamak için sınır denetimi yapılır.
        if ((c == '*' || c == '_') && i + 1 < n && !text[i + 1].isWhitespace() && text[i + 1] != c) {
            val prevOk = i == 0 || !text[i - 1].isLetterOrDigit() || c == '*'
            if (prevOk) {
                var end = text.indexOf(c, i + 1)
                while (end > 0 && (text[end - 1].isWhitespace() || (c == '_' && end + 1 < n && text[end + 1].isLetterOrDigit()))) {
                    end = text.indexOf(c, end + 1)
                }
                if (end > i + 1) {
                    flush()
                    out.withStyle(SpanStyle(fontStyle = FontStyle.Italic)) {
                        appendInline(this, text.substring(i + 1, end), styles)
                    }
                    i = end + 1
                    continue
                }
            }
        }

        // [metin](url)
        if (c == '[') {
            val close = text.indexOf("](", i + 1)
            if (close > i) {
                val urlEnd = text.indexOf(')', close + 2)
                if (urlEnd > close) {
                    val label = text.substring(i + 1, close)
                    val url = text.substring(close + 2, urlEnd).trim()
                    if (url.startsWith("http://") || url.startsWith("https://") || url.startsWith("mailto:")) {
                        flush()
                        out.withLink(linkAnnotation(url, styles)) { appendInline(this, label, styles) }
                        i = urlEnd + 1
                        continue
                    }
                }
            }
        }

        // Çıplak URL
        if ((c == 'h') && (text.startsWith("http://", i) || text.startsWith("https://", i)) &&
            (i == 0 || text[i - 1].isWhitespace() || text[i - 1] in "([")
        ) {
            var end = i
            while (end < n && !text[end].isWhitespace() && text[end] != ')' && text[end] != '>') end++
            var url = text.substring(i, end)
            val trailing = url.takeLastWhile { it in ".,;:!?\"'" }
            url = url.dropLast(trailing.length)
            if (url.length > 8) {
                flush()
                out.withLink(linkAnnotation(url, styles)) { append(url) }
                i += url.length
                continue
            }
        }

        plain.append(c)
        i++
    }
    flush()
}

private fun linkAnnotation(url: String, styles: InlineStyles) = LinkAnnotation.Url(
    url,
    TextLinkStyles(SpanStyle(color = styles.linkColor, textDecoration = TextDecoration.Underline)),
)
