package com.kutu.chat.ui.components

import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.font.FontStyle
import com.kutu.chat.ui.theme.CodeColors

/** Çoğu dilde ortak olan anahtar sözcükler; dile özel ayrıştırıcı yerine hafif ve yeterince iyi bir yaklaşım. */
private val KEYWORDS = setOf(
    // Kotlin / Java / C benzeri
    "fun", "val", "var", "class", "object", "interface", "enum", "data", "sealed", "open", "abstract", "override", "private",
    "public", "protected", "internal", "static", "final", "const", "suspend", "inline", "companion", "import", "package",
    "return", "if", "else", "when", "for", "while", "do", "break", "continue", "try", "catch", "finally", "throw", "throws",
    "new", "this", "super", "null", "true", "false", "void", "int", "long", "float", "double", "boolean", "char", "byte",
    "short", "string", "switch", "case", "default", "extends", "implements", "instanceof", "is", "as", "in", "typealias",
    // JS / TS
    "function", "let", "async", "await", "export", "from", "of", "typeof", "undefined", "type", "namespace", "yield",
    // Python
    "def", "elif", "lambda", "pass", "with", "not", "and", "or", "None", "True", "False", "self", "global", "nonlocal",
    "raise", "except", "assert", "del",
    // Go / Rust / Swift
    "func", "struct", "impl", "trait", "mut", "pub", "use", "mod", "match", "loop", "fn", "go", "defer", "chan", "map",
    "range", "select", "guard", "protocol", "extension", "where", "unsafe", "crate", "dyn",
    // SQL (küçük harf karşılaştırılır)
    "select", "insert", "update", "delete", "create", "table", "join", "left", "right", "inner", "on", "group", "by",
    "order", "having", "limit", "values", "into", "set", "alter", "drop", "index", "primary", "key", "distinct", "union",
    // Kabuk
    "echo", "then", "fi", "done", "esac", "local", "exit",
)
private val HASH_COMMENT_LANGS = setOf("python", "py", "bash", "sh", "shell", "zsh", "yaml", "yml", "ruby", "rb", "r", "toml", "dockerfile", "makefile", "perl", "ini")
private val NO_HIGHLIGHT = setOf("text", "txt", "plain", "plaintext", "markdown", "md", "csv", "log", "output")

/** Kodu renklendirir: yorumlar, dizgeler, sayılar, anahtar sözcükler ve işlev çağrıları. */
fun highlightCode(code: String, lang: String, c: CodeColors): AnnotatedString {
    val language = lang.lowercase()
    if (language in NO_HIGHLIGHT || code.length > 40_000) return AnnotatedString(code)
    val hashComments = language in HASH_COMMENT_LANGS
    val slashComments = !hashComments || language.isEmpty()
    val sqlComments = language == "sql"
    val b = AnnotatedString.Builder(code)
    fun span(color: androidx.compose.ui.graphics.Color, from: Int, to: Int, italic: Boolean = false) =
        b.addStyle(SpanStyle(color = color, fontStyle = if (italic) FontStyle.Italic else null), from, to)

    var i = 0
    val n = code.length
    while (i < n) {
        val ch = code[i]
        when {
            // Satır yorumu
            (slashComments && ch == '/' && i + 1 < n && code[i + 1] == '/') ||
                (hashComments && ch == '#') || (sqlComments && ch == '-' && i + 1 < n && code[i + 1] == '-') -> {
                val end = code.indexOf('\n', i).let { if (it < 0) n else it }
                span(c.comment, i, end, italic = true); i = end
            }
            // Blok yorumu
            slashComments && ch == '/' && i + 1 < n && code[i + 1] == '*' -> {
                val end = code.indexOf("*/", i + 2).let { if (it < 0) n else it + 2 }
                span(c.comment, i, end, italic = true); i = end
            }
            // Dizge
            ch == '"' || ch == '\'' || ch == '`' -> {
                var j = i + 1
                while (j < n && code[j] != ch && (ch == '`' || code[j] != '\n')) { if (code[j] == '\\') j++; j++ }
                val end = minOf(n, j + 1)
                span(c.string, i, end); i = end
            }
            ch.isDigit() && (i == 0 || !(code[i - 1].isLetterOrDigit() || code[i - 1] == '_')) -> {
                var j = i
                while (j < n && (code[j].isLetterOrDigit() || code[j] == '.' || code[j] == '_')) j++
                span(c.number, i, j); i = j
            }
            ch.isLetter() || ch == '_' -> {
                var j = i
                while (j < n && (code[j].isLetterOrDigit() || code[j] == '_')) j++
                val word = code.substring(i, j)
                when {
                    word in KEYWORDS || (sqlComments && word.lowercase() in KEYWORDS) -> span(c.keyword, i, j)
                    j < n && code[j] == '(' -> span(c.function, i, j)
                }
                i = j
            }
            else -> i++
        }
    }
    return b.toAnnotatedString()
}
