package com.kutu.chat.ui.components

/** Sohbet yanıtlarında sık kullanılan Markdown alt kümesi için hafif bir blok ayrıştırıcı. */
sealed interface MdBlock {
    data class Heading(val level: Int, val text: String) : MdBlock
    data class Paragraph(val text: String) : MdBlock
    data class Code(val lang: String, val code: String, val closed: Boolean) : MdBlock
    data class ListItem(val indent: Int, val marker: String, val text: String) : MdBlock
    data class Quote(val text: String) : MdBlock
    data class Table(val header: List<String>, val rows: List<List<String>>) : MdBlock
    data object Rule : MdBlock
}

private val HEADING = Regex("^(#{1,6})\\s+(.*?)\\s*#*\\s*$")
private val RULE = Regex("^(\\*{3,}|-{3,}|_{3,})$")
private val LIST_ITEM = Regex("^(\\s*)([-*+]|\\d{1,3}[.)])\\s+(.*)$")
private val TABLE_SEP = Regex("^\\s*\\|?\\s*:?-{2,}:?\\s*(\\|\\s*:?-{2,}:?\\s*)*\\|?\\s*$")

fun parseMarkdown(source: String): List<MdBlock> {
    val lines = source.replace("\r\n", "\n").split('\n')
    val blocks = ArrayList<MdBlock>()
    val para = StringBuilder()

    fun flushParagraph() {
        if (para.isNotEmpty()) {
            blocks.add(MdBlock.Paragraph(para.toString().trimEnd()))
            para.clear()
        }
    }

    var i = 0
    while (i < lines.size) {
        val line = lines[i]
        val trimmed = line.trim()

        // Çitli kod bloğu
        if (trimmed.startsWith("```") || trimmed.startsWith("~~~")) {
            flushParagraph()
            val fence = trimmed.take(3)
            val lang = trimmed.drop(3).trim().substringBefore(' ')
            val code = ArrayList<String>()
            i++
            var closed = false
            while (i < lines.size) {
                if (lines[i].trim().startsWith(fence)) {
                    closed = true
                    i++
                    break
                }
                code.add(lines[i])
                i++
            }
            blocks.add(MdBlock.Code(lang, code.joinToString("\n"), closed))
            continue
        }

        if (trimmed.isEmpty()) {
            flushParagraph()
            i++
            continue
        }

        val heading = HEADING.matchEntire(trimmed)
        if (heading != null) {
            flushParagraph()
            blocks.add(MdBlock.Heading(heading.groupValues[1].length, heading.groupValues[2]))
            i++
            continue
        }

        if (RULE.matches(trimmed)) {
            flushParagraph()
            blocks.add(MdBlock.Rule)
            i++
            continue
        }

        // Tablo: başlık satırı + ayırıcı satırı
        if (trimmed.contains('|') && i + 1 < lines.size && TABLE_SEP.matches(lines[i + 1]) && lines[i + 1].contains('-')) {
            flushParagraph()
            val header = splitRow(trimmed)
            val rows = ArrayList<List<String>>()
            i += 2
            while (i < lines.size && lines[i].isNotBlank() && lines[i].contains('|')) {
                rows.add(splitRow(lines[i].trim()))
                i++
            }
            blocks.add(MdBlock.Table(header, rows))
            continue
        }

        if (trimmed.startsWith(">")) {
            flushParagraph()
            val quote = ArrayList<String>()
            while (i < lines.size && lines[i].trim().startsWith(">")) {
                quote.add(lines[i].trim().removePrefix(">").removePrefix(" "))
                i++
            }
            blocks.add(MdBlock.Quote(quote.joinToString("\n")))
            continue
        }

        val item = LIST_ITEM.matchEntire(line)
        if (item != null) {
            flushParagraph()
            val marker = item.groupValues[2]
            val shown = if (marker[0].isDigit()) marker else "•"
            blocks.add(MdBlock.ListItem(item.groupValues[1].length / 2, shown, item.groupValues[3]))
            i++
            continue
        }

        // Liste öğesinin girintili devam satırı
        val prev = blocks.lastOrNull()
        if (para.isEmpty() && prev is MdBlock.ListItem && line.startsWith("  ")) {
            blocks[blocks.size - 1] = prev.copy(text = prev.text + "\n" + trimmed)
            i++
            continue
        }

        if (para.isNotEmpty()) para.append('\n')
        para.append(trimmed)
        i++
    }
    flushParagraph()
    return blocks
}

private fun splitRow(row: String): List<String> =
    row.trim().removePrefix("|").removeSuffix("|").split('|').map { it.trim() }
