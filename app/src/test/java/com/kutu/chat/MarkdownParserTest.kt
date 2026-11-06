package com.kutu.chat

import com.kutu.chat.ui.components.MdBlock
import com.kutu.chat.ui.components.parseMarkdown
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MarkdownParserTest {
    @Test fun parsesHeadingParagraphAndRule() {
        val b = parseMarkdown("# Başlık\n\nBir paragraf\nikinci satır\n\n---")
        assertEquals(MdBlock.Heading(1, "Başlık"), b[0])
        assertEquals(MdBlock.Paragraph("Bir paragraf\nikinci satır"), b[1])
        assertEquals(MdBlock.Rule, b[2])
    }

    @Test fun parsesFencedCodeWithLanguage() {
        val b = parseMarkdown("Önce\n```kotlin\nfun main() {}\n```\nSonra")
        assertEquals(MdBlock.Paragraph("Önce"), b[0])
        assertEquals(MdBlock.Code("kotlin", "fun main() {}", true), b[1])
        assertEquals(MdBlock.Paragraph("Sonra"), b[2])
    }

    @Test fun unclosedFenceWhileStreamingStillRendersAsCode() {
        val b = parseMarkdown("```python\nprint('merhaba')")
        val code = b.single() as MdBlock.Code
        assertEquals("print('merhaba')", code.code)
        assertFalse(code.closed)
    }

    @Test fun parsesNestedLists() {
        val b = parseMarkdown("- bir\n  - alt\n1. sıralı")
        assertEquals(MdBlock.ListItem(0, "•", "bir"), b[0])
        assertEquals(MdBlock.ListItem(1, "•", "alt"), b[1])
        assertEquals(MdBlock.ListItem(0, "1.", "sıralı"), b[2])
    }

    @Test fun parsesTable() {
        val b = parseMarkdown("| Ad | Yaş |\n|---|---|\n| Ali | 30 |\n| Ayşe | 25 |")
        val t = b.single() as MdBlock.Table
        assertEquals(listOf("Ad", "Yaş"), t.header)
        assertEquals(2, t.rows.size)
        assertEquals(listOf("Ayşe", "25"), t.rows[1])
    }

    @Test fun parsesQuote() {
        val b = parseMarkdown("> alıntı\n> devam")
        assertEquals(MdBlock.Quote("alıntı\ndevam"), b.single())
    }

    @Test fun boldListItemIsNotMistakenForRule() {
        val b = parseMarkdown("**kalın** metin")
        assertTrue(b.single() is MdBlock.Paragraph)
    }
}
