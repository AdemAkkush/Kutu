package com.kutu.chat.data

import android.content.Context
import android.util.Xml
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.text.PDFTextStripper
import java.io.File
import java.io.InputStream
import java.math.MathContext
import java.time.LocalDate
import java.time.LocalTime
import java.util.zip.ZipFile
import org.xmlpull.v1.XmlPullParser

enum class DocKind { PDF, XLSX, DOCX, PPTX, LEGACY_OFFICE, TEXT }

data class Extracted(
    val text: String,
    val note: String,
    /** Metin katmanı yok (taranmış PDF); sayfalar resim olarak eklenmeli. */
    val scanned: Boolean = false,
    val pages: Int = 0,
)

/**
 * PDF, Excel (.xlsx), Word (.docx) ve PowerPoint (.pptx) dosyalarından düz metin çıkarır.
 * Office biçimleri ZIP + XML olduğundan ek bir kütüphane gerekmeden ayrıştırılır; PDF için PDFBox kullanılır.
 */
class DocumentExtractor(private val context: Context) {
    private var pdfReady = false

    fun detect(file: File): DocKind {
        val head = file.inputStream().use { it.readNBytes(8) }
        fun starts(vararg b: Int) = head.size >= b.size && b.indices.all { head[it] == b[it].toByte() }
        return when {
            starts(0x25, 0x50, 0x44, 0x46) -> DocKind.PDF                       // %PDF
            starts(0xD0, 0xCF, 0x11, 0xE0) -> DocKind.LEGACY_OFFICE             // eski .xls/.doc/.ppt
            starts(0x50, 0x4B, 0x03, 0x04) -> runCatching {
                ZipFile(file).use { z ->
                    when {
                        z.getEntry("xl/workbook.xml") != null -> DocKind.XLSX
                        z.getEntry("word/document.xml") != null -> DocKind.DOCX
                        z.getEntry("ppt/presentation.xml") != null -> DocKind.PPTX
                        else -> DocKind.TEXT
                    }
                }
            }.getOrDefault(DocKind.TEXT)
            else -> DocKind.TEXT
        }
    }

    fun extract(file: File, kind: DocKind): Extracted = when (kind) {
        DocKind.PDF -> pdf(file)
        DocKind.XLSX -> xlsx(file)
        DocKind.DOCX -> docx(file)
        DocKind.PPTX -> pptx(file)
        DocKind.LEGACY_OFFICE -> throw AttachmentException(
            "Eski Office biçimi (.xls/.doc/.ppt) desteklenmiyor. Dosyayı .xlsx / .docx / .pptx olarak kaydedip tekrar ekleyin.",
        )
        DocKind.TEXT -> throw AttachmentException("Bu dosya türü desteklenmiyor.")
    }

    // ---- PDF ----

    private fun pdf(file: File): Extracted {
        if (!pdfReady) {
            PDFBoxResourceLoader.init(context)
            pdfReady = true
        }
        val doc = try {
            PDDocument.load(file)
        } catch (e: Exception) {
            val msg = (e.javaClass.simpleName + " " + e.message).lowercase()
            throw AttachmentException(
                if ("password" in msg || "encrypt" in msg) "PDF parola korumalı; önce korumayı kaldırın."
                else "PDF okunamadı: ${e.message ?: e.javaClass.simpleName}",
            )
        }
        doc.use { d ->
            val pages = d.numberOfPages
            val stripper = PDFTextStripper()
            val sb = StringBuilder()
            var realChars = 0
            for (p in 1..minOf(pages, MAX_PDF_PAGES)) {
                stripper.startPage = p
                stripper.endPage = p
                val t = stripper.getText(d).trim()
                realChars += t.length
                sb.append("--- Sayfa ").append(p).append(" ---\n").append(t).append("\n\n")
                if (sb.length > MAX_CHARS) break
            }
            val scanned = realChars < 30 * minOf(pages, 10)
            val extra = if (pages > MAX_PDF_PAGES) " (ilk $MAX_PDF_PAGES sayfa)" else ""
            return Extracted(
                text = if (scanned) "" else clip(sb.toString()),
                note = "$pages sayfa$extra" + if (scanned) " · taranmış" else " · ${kilo(realChars)} karakter",
                scanned = scanned,
                pages = pages,
            )
        }
    }

    // ---- Word ----

    private fun docx(file: File): Extracted = ZipFile(file).use { zip ->
        val entry = zip.getEntry("word/document.xml") ?: throw AttachmentException("Word dosyası bozuk.")
        val sb = StringBuilder()
        val p = parser(zip.getInputStream(entry))
        var inText = false
        var inCell = false
        var ev = p.eventType
        while (ev != XmlPullParser.END_DOCUMENT) {
            when (ev) {
                XmlPullParser.START_TAG -> when (local(p)) {
                    "t" -> inText = true
                    "tab" -> sb.append('\t')
                    "br" -> sb.append(if (inCell) ' ' else '\n')
                    "tc" -> inCell = true
                }
                XmlPullParser.TEXT -> if (inText) sb.append(p.text)
                XmlPullParser.END_TAG -> when (local(p)) {
                    "t" -> inText = false
                    "p" -> sb.append(if (inCell) ' ' else '\n')   // hücre içinde paragraflar tek satırda kalır
                    "tc" -> { inCell = false; while (sb.isNotEmpty() && sb.last() == ' ') sb.setLength(sb.length - 1); sb.append(" | ") }
                    "tr" -> sb.append('\n')
                }
            }
            if (sb.length > MAX_CHARS) break
            ev = p.next()
        }
        val text = sb.toString().lines().joinToString("\n") { it.trimEnd().removeSuffix("|").trimEnd() }.replace(Regex("\n{3,}"), "\n\n").trim()
        Extracted(clip(text), "Word · ${kilo(text.length)} karakter")
    }

    // ---- PowerPoint ----

    private fun pptx(file: File): Extracted = ZipFile(file).use { zip ->
        val slides = zip.entries().asSequence().map { it.name }
            .mapNotNull { n -> Regex("ppt/slides/slide(\\d+)\\.xml").matchEntire(n)?.let { it.groupValues[1].toInt() to n } }
            .sortedBy { it.first }.toList()
        val sb = StringBuilder()
        for ((number, name) in slides) {
            sb.append("--- Slayt ").append(number).append(" ---\n")
            val p = parser(zip.getInputStream(zip.getEntry(name)))
            var inText = false
            var ev = p.eventType
            while (ev != XmlPullParser.END_DOCUMENT) {
                when (ev) {
                    XmlPullParser.START_TAG -> if (local(p) == "t") inText = true
                    XmlPullParser.TEXT -> if (inText) sb.append(p.text)
                    XmlPullParser.END_TAG -> when (local(p)) {
                        "t" -> inText = false
                        "p" -> sb.append('\n')
                    }
                }
                ev = p.next()
            }
            sb.append('\n')
            if (sb.length > MAX_CHARS) break
        }
        Extracted(clip(sb.toString().replace(Regex("\n{3,}"), "\n\n").trim()), "${slides.size} slayt · ${kilo(sb.length)} karakter")
    }

    // ---- Excel ----

    private fun xlsx(file: File): Extracted = ZipFile(file).use { zip ->
        val strings = sharedStrings(zip)
        val dateStyles = dateStyles(zip)
        val sheets = sheetList(zip)
        val sb = StringBuilder()
        var totalRows = 0
        var truncated = false
        for ((name, path) in sheets) {
            val entry = zip.getEntry(path) ?: continue
            val rows = ArrayList<String>()
            var maxCols = 0
            var empty = 0
            readSheet(zip.getInputStream(entry), strings, dateStyles) { cells ->
                if (cells.isEmpty()) { empty++; return@readSheet true }
                maxCols = maxOf(maxCols, cells.size)
                rows.add(cells.joinToString(",") { csv(it) })
                rows.size < MAX_SHEET_ROWS && sb.length + rows.sumOf { it.length } < MAX_CHARS
            }
            totalRows += rows.size
            sb.append("## Sayfa: ").append(name).append(" (").append(rows.size).append(" satır × ").append(maxCols).append(" sütun)\n")
            rows.forEach { sb.append(it).append('\n') }
            if (rows.size >= MAX_SHEET_ROWS) { sb.append("[… bu sayfa $MAX_SHEET_ROWS satırdan sonra kesildi]\n"); truncated = true }
            sb.append('\n')
            if (sb.length > MAX_CHARS) { truncated = true; break }
        }
        val note = "${sheets.size} sayfa · $totalRows satır" + if (truncated) " · kısaltıldı" else ""
        Extracted(clip(sb.toString().trim()), note)
    }

    private fun sheetList(zip: ZipFile): List<Pair<String, String>> {
        val wb = zip.getEntry("xl/workbook.xml") ?: throw AttachmentException("Excel dosyası bozuk.")
        val rels = HashMap<String, String>()
        zip.getEntry("xl/_rels/workbook.xml.rels")?.let { e ->
            val p = parser(zip.getInputStream(e))
            var ev = p.eventType
            while (ev != XmlPullParser.END_DOCUMENT) {
                if (ev == XmlPullParser.START_TAG && p.name == "Relationship") {
                    val id = p.getAttributeValue(null, "Id"); val target = p.getAttributeValue(null, "Target")
                    if (id != null && target != null) rels[id] = if (target.startsWith("/")) target.removePrefix("/") else "xl/$target"
                }
                ev = p.next()
            }
        }
        val out = ArrayList<Pair<String, String>>()
        val p = parser(zip.getInputStream(wb))
        var ev = p.eventType
        var idx = 0
        while (ev != XmlPullParser.END_DOCUMENT) {
            if (ev == XmlPullParser.START_TAG && p.name == "sheet") {
                idx++
                val name = p.getAttributeValue(null, "name") ?: "Sayfa$idx"
                var rid: String? = null
                for (i in 0 until p.attributeCount) if (p.getAttributeName(i).endsWith("id") && p.getAttributeName(i) != "sheetId") rid = p.getAttributeValue(i)
                out.add(name to (rid?.let { rels[it] } ?: "xl/worksheets/sheet$idx.xml"))
            }
            ev = p.next()
        }
        return out
    }

    private fun sharedStrings(zip: ZipFile): List<String> {
        val e = zip.getEntry("xl/sharedStrings.xml") ?: return emptyList()
        val out = ArrayList<String>()
        val p = parser(zip.getInputStream(e))
        val sb = StringBuilder()
        var inT = false; var inSi = false; var inPhonetic = false
        var ev = p.eventType
        while (ev != XmlPullParser.END_DOCUMENT) {
            when (ev) {
                XmlPullParser.START_TAG -> when (p.name) {
                    "si" -> { sb.setLength(0); inSi = true }
                    "t" -> if (inSi && !inPhonetic) inT = true
                    "rPh" -> inPhonetic = true
                }
                XmlPullParser.TEXT -> if (inT) sb.append(p.text)
                XmlPullParser.END_TAG -> when (p.name) {
                    "t" -> inT = false
                    "rPh" -> inPhonetic = false
                    "si" -> { out.add(sb.toString()); inSi = false }
                }
            }
            ev = p.next()
        }
        return out
    }

    /** Hücre biçim dizinlerinden hangileri tarih/saat biçimli? */
    private fun dateStyles(zip: ZipFile): Set<Int> {
        val e = zip.getEntry("xl/styles.xml") ?: return emptySet()
        val custom = HashMap<Int, String>()
        val xfFormat = ArrayList<Int>()
        val p = parser(zip.getInputStream(e))
        var inCellXfs = false
        var ev = p.eventType
        while (ev != XmlPullParser.END_DOCUMENT) {
            when (ev) {
                XmlPullParser.START_TAG -> when (p.name) {
                    "numFmt" -> p.getAttributeValue(null, "numFmtId")?.toIntOrNull()?.let { custom[it] = p.getAttributeValue(null, "formatCode").orEmpty() }
                    "cellXfs" -> inCellXfs = true
                    "xf" -> if (inCellXfs) xfFormat.add(p.getAttributeValue(null, "numFmtId")?.toIntOrNull() ?: 0)
                }
                XmlPullParser.END_TAG -> if (p.name == "cellXfs") inCellXfs = false
            }
            ev = p.next()
        }
        return xfFormat.withIndex().filter { (_, id) -> isDateFormat(id, custom[id]) }.map { it.index }.toSet()
    }

    private fun readSheet(
        input: InputStream,
        strings: List<String>,
        dateStyles: Set<Int>,
        onRow: (List<String>) -> Boolean,
    ) {
        val p = parser(input)
        var row = ArrayList<String>()
        var col = 0
        var type = ""; var style = 0; var raw = ""; var inline = StringBuilder()
        var inInline = false; var inV = false; var inInlineT = false
        var ev = p.eventType
        while (ev != XmlPullParser.END_DOCUMENT) {
            when (ev) {
                XmlPullParser.START_TAG -> when (p.name) {
                    "row" -> { row = ArrayList(); col = 0 }
                    "c" -> {
                        type = p.getAttributeValue(null, "t").orEmpty()
                        style = p.getAttributeValue(null, "s")?.toIntOrNull() ?: 0
                        raw = ""; inline = StringBuilder()
                        p.getAttributeValue(null, "r")?.let { ref ->
                            val target = columnIndex(ref)
                            while (row.size < target) row.add("")
                        }
                    }
                    "v" -> inV = true
                    "is" -> inInline = true
                    "t" -> if (inInline) inInlineT = true
                }
                XmlPullParser.TEXT -> {
                    if (inV) raw += p.text
                    if (inInlineT) inline.append(p.text)
                }
                XmlPullParser.END_TAG -> when (p.name) {
                    "v" -> inV = false
                    "t" -> inInlineT = false
                    "is" -> inInline = false
                    "c" -> {
                        val value = when (type) {
                            "s" -> raw.trim().toIntOrNull()?.let { strings.getOrNull(it) }.orEmpty()
                            "inlineStr" -> inline.toString()
                            "b" -> if (raw.trim() == "1") "TRUE" else "FALSE"
                            "str", "e" -> raw
                            else -> if (style in dateStyles) excelDate(raw) else number(raw)
                        }
                        row.add(value)
                    }
                    "row" -> {
                        while (row.isNotEmpty() && row.last().isEmpty()) row.removeAt(row.size - 1)
                        if (!onRow(row)) return
                    }
                }
            }
            ev = p.next()
        }
    }

    /** Ad alanı önekini (w:, a:) yok sayarak yerel adı verir; ayrıştırıcı ad alanlarını işlese de işlemese de çalışır. */
    private fun local(p: XmlPullParser): String = p.name.substringAfter(':')

    private fun parser(input: InputStream): XmlPullParser =
        Xml.newPullParser().apply { setInput(input, "UTF-8") }

    companion object {
        const val MAX_CHARS = 1_500_000
        const val MAX_PDF_PAGES = 400
        const val MAX_SHEET_ROWS = 20_000

        fun clip(s: String) = if (s.length > MAX_CHARS) s.take(MAX_CHARS) + "\n[… belge çok uzun olduğu için burada kesildi]" else s
        fun kilo(n: Int) = if (n >= 1000) "${n / 1000} bin" else "$n"

        /** "AB12" → 27 (sıfır tabanlı sütun dizini). */
        fun columnIndex(ref: String): Int {
            var n = 0
            for (ch in ref) { if (ch in 'A'..'Z') n = n * 26 + (ch - 'A' + 1) else break }
            return (n - 1).coerceAtLeast(0)
        }

        fun csv(v: String) = if (v.any { it == ',' || it == '"' || it == '\n' || it == '\r' }) "\"" + v.replace("\"", "\"\"").replace("\n", " ").replace("\r", "") + "\"" else v

        fun number(raw: String): String {
            val s = raw.trim()
            if (s.isEmpty()) return ""
            val bd = s.toBigDecimalOrNull() ?: return s
            return bd.round(MathContext(12)).stripTrailingZeros().toPlainString()
        }

        private val BUILTIN_DATE_IDS = (14..22).toSet() + (27..36).toSet() + (45..47).toSet() + (50..58).toSet()

        fun isDateFormat(id: Int, code: String?): Boolean {
            if (code == null) return id in BUILTIN_DATE_IDS
            // Tırnak ve köşeli parantez içini at, sonra tarih/saat harfi var mı bak.
            val stripped = code.replace(Regex("\"[^\"]*\""), "").replace(Regex("\\[[^]]*]"), "").lowercase()
            return stripped.any { it in "ymdhs" } && !stripped.contains("general")
        }

        /** Excel seri numarası → "2026-11-06" ya da "2026-11-06 14:30". */
        fun excelDate(raw: String): String {
            val serial = raw.trim().toDoubleOrNull() ?: return raw.trim()
            val days = serial.toLong()
            val frac = serial - days
            val seconds = Math.round(frac * 86400)
            val time = LocalTime.ofSecondOfDay(seconds.coerceIn(0, 86399))
            if (days <= 0) return "%02d:%02d".format(time.hour, time.minute)
            val date = LocalDate.of(1899, 12, 30).plusDays(days)
            return if (seconds == 0L) date.toString() else "$date %02d:%02d".format(time.hour, time.minute)
        }
    }
}
