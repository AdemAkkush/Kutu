package com.kutu.chat

import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import com.kutu.chat.data.AppSettings
import com.kutu.chat.data.AttachmentException
import com.kutu.chat.data.DocKind
import com.kutu.chat.data.DocumentExtractor
import com.kutu.chat.data.db.ChatEntity
import com.kutu.chat.data.db.MessageEntity
import com.kutu.chat.domain.ATT_TEXT
import com.kutu.chat.domain.Attachment
import com.kutu.chat.domain.AttachmentSource
import com.kutu.chat.domain.Attachments
import com.kutu.chat.domain.ContextBuilder
import com.kutu.chat.domain.DocExcerpt
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream
import com.tom_roush.pdfbox.pdmodel.font.PDType1Font
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = KutuApp::class)
class DocumentsTest {
    private val app get() = ApplicationProvider.getApplicationContext<KutuApp>()
    private val container get() = app.container
    private val extractor by lazy { DocumentExtractor(app) }

    private fun zip(name: String, entries: Map<String, String>): File {
        val f = File(app.cacheDir, name)
        ZipOutputStream(f.outputStream()).use { z ->
            entries.forEach { (n, body) -> z.putNextEntry(ZipEntry(n)); z.write(body.toByteArray()); z.closeEntry() }
        }
        return f
    }

    private val NS = "xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\""

    private fun sampleXlsx() = zip(
        "satis.xlsx",
        mapOf(
            "xl/workbook.xml" to """<workbook $NS xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships"><sheets><sheet name="Satışlar" sheetId="1" r:id="rId1"/><sheet name="Özet" sheetId="2" r:id="rId2"/></sheets></workbook>""",
            "xl/_rels/workbook.xml.rels" to """<Relationships><Relationship Id="rId1" Type="x" Target="worksheets/sheet1.xml"/><Relationship Id="rId2" Type="x" Target="worksheets/sheet2.xml"/></Relationships>""",
            "xl/sharedStrings.xml" to """<sst $NS><si><t>Ürün</t></si><si><t>Adet</t></si><si><t>Tarih</t></si><si><t>Elma</t></si><si><t>Armut, Kırmızı</t></si><si><r><t>Zengin </t></r><r><t>metin</t></r></si><si><t>Toplam</t></si></sst>""",
            "xl/styles.xml" to """<styleSheet $NS><numFmts count="1"><numFmt numFmtId="164" formatCode="yyyy\-mm\-dd\ hh:mm"/></numFmts><cellXfs count="3"><xf numFmtId="0"/><xf numFmtId="14"/><xf numFmtId="164"/></cellXfs></styleSheet>""",
            "xl/worksheets/sheet1.xml" to """<worksheet $NS><sheetData>
                <row r="1"><c r="A1" t="s"><v>0</v></c><c r="B1" t="s"><v>1</v></c><c r="C1" t="s"><v>2</v></c></row>
                <row r="2"><c r="A2" t="s"><v>3</v></c><c r="B2"><v>12.5</v></c><c r="C2" s="1"><v>46332</v></c></row>
                <row r="3"><c r="A3" t="s"><v>4</v></c><c r="C3" s="2"><v>46332.5625</v></c><c r="D3" t="b"><v>1</v></c></row>
                <row r="4"></row>
                <row r="5"><c r="A5" t="inlineStr"><is><t>satır içi</t></is></c><c r="B5"><v>0.30000000000000004</v></c><c r="C5" t="str"><f>A5&amp;"x"</f><v>sonuç</v></c><c r="D5" t="s"><v>5</v></c></row>
            </sheetData></worksheet>""",
            "xl/worksheets/sheet2.xml" to """<worksheet $NS><sheetData><row r="1"><c r="A1" t="s"><v>6</v></c><c r="B1"><f>SUM(B2:B3)</f><v>1250</v></c></row></sheetData></worksheet>""",
        ),
    )

    @Test fun xlsxIsConvertedToCsvWithSheetsDatesAndSparseCells() {
        val file = sampleXlsx()
        assertEquals(DocKind.XLSX, extractor.detect(file))
        val r = extractor.extract(file, DocKind.XLSX)
        val text = r.text
        assertTrue(text, text.contains("## Sayfa: Satışlar (4 satır × 4 sütun)"))
        assertTrue(text, text.contains("Ürün,Adet,Tarih"))
        assertTrue(text, text.contains("Elma,12.5,2026-11-06"))             // tarih biçimli sayı → tarih
        assertTrue(text, text.contains("\"Armut, Kırmızı\",,2026-11-06 13:30,TRUE"))   // boş hücre, saat, mantıksal, CSV tırnağı
        assertTrue(text, text.contains("satır içi,0.3,sonuç,Zengin metin"))  // inline string, yuvarlama, formül sonucu, zengin metin
        assertTrue(text, text.contains("## Sayfa: Özet (1 satır × 2 sütun)"))
        assertTrue(text, text.contains("Toplam,1250"))
        assertEquals("2 sayfa · 5 satır", r.note)
    }

    @Test fun docxKeepsParagraphsAndTables() {
        val body = """<w:document xmlns:w="w"><w:body>
            <w:p><w:r><w:t>Başlık</w:t></w:r></w:p>
            <w:p><w:r><w:t>İlk </w:t></w:r><w:r><w:t>paragraf</w:t></w:r></w:p>
            <w:tbl><w:tr><w:tc><w:p><w:r><w:t>Ad</w:t></w:r></w:p></w:tc><w:tc><w:p><w:r><w:t>Yaş</w:t></w:r></w:p></w:tc></w:tr>
            <w:tr><w:tc><w:p><w:r><w:t>Ali</w:t></w:r></w:p></w:tc><w:tc><w:p><w:r><w:t>30</w:t></w:r></w:p></w:tc></w:tr></w:tbl>
            </w:body></w:document>"""
        val f = zip("a.docx", mapOf("word/document.xml" to body))
        assertEquals(DocKind.DOCX, extractor.detect(f))
        val t = extractor.extract(f, DocKind.DOCX).text
        assertTrue(t, t.contains("Başlık\nİlk paragraf"))
        assertTrue(t, t.contains("Ad | Yaş"))
        assertTrue(t, t.contains("Ali | 30"))
    }

    @Test fun pptxSlidesAreOrderedNumerically() {
        fun slide(text: String) = """<p:sld xmlns:p="p" xmlns:a="a"><a:p><a:r><a:t>$text</a:t></a:r></a:p></p:sld>"""
        val f = zip("s.pptx", mapOf(
            "ppt/presentation.xml" to "<p/>", "ppt/slides/slide10.xml" to slide("onuncu"),
            "ppt/slides/slide2.xml" to slide("ikinci"), "ppt/slides/slide1.xml" to slide("birinci"),
        ))
        assertEquals(DocKind.PPTX, extractor.detect(f))
        val t = extractor.extract(f, DocKind.PPTX).text
        assertTrue(t, t.indexOf("birinci") < t.indexOf("ikinci") && t.indexOf("ikinci") < t.indexOf("onuncu"))
        assertTrue(t, t.contains("--- Slayt 10 ---"))
    }

    private fun pdf(vararg pages: String): File {
        PDFBoxResourceLoader.init(app)
        val f = File(app.cacheDir, "belge-${pages.size}-${pages.firstOrNull()?.length}.pdf")
        PDDocument().use { doc ->
            for (text in pages) {
                val page = PDPage(); doc.addPage(page)
                if (text.isNotEmpty()) PDPageContentStream(doc, page).use { cs ->
                    cs.beginText(); cs.setFont(PDType1Font.HELVETICA, 12f); cs.newLineAtOffset(50f, 700f); cs.showText(text); cs.endText()
                }
            }
            doc.save(f)
        }
        return f
    }

    @Test fun pdfTextIsExtractedPerPageWithMarkers() {
        val f = pdf("Invoice total 1250 USD for ACME corporation and its subsidiaries worldwide", "Second page about shipping and delivery conditions of the contract")
        assertEquals(DocKind.PDF, extractor.detect(f))
        val r = extractor.extract(f, DocKind.PDF)
        assertFalse(r.scanned)
        assertTrue(r.text, r.text.contains("--- Sayfa 1 ---") && r.text.contains("Invoice total 1250 USD"))
        assertTrue(r.text, r.text.indexOf("Sayfa 2") > r.text.indexOf("Invoice"))
        assertTrue(r.note, r.note.startsWith("2 sayfa"))
    }

    @Test fun pdfWithoutTextIsFlaggedAsScanned() {
        val r = extractor.extract(pdf("", ""), DocKind.PDF)
        assertTrue(r.scanned)
        assertTrue(r.note, r.note.contains("taranmış"))
    }

    @Test fun unsupportedFilesGiveClearErrors() {
        val ole = File(app.cacheDir, "eski.xls").apply { writeBytes(byteArrayOf(0xD0.toByte(), 0xCF.toByte(), 0x11, 0xE0.toByte(), 1, 2, 3, 4)) }
        assertEquals(DocKind.LEGACY_OFFICE, extractor.detect(ole))
        try { extractor.extract(ole, DocKind.LEGACY_OFFICE); fail() } catch (e: AttachmentException) { assertTrue(e.message, e.message!!.contains(".xlsx")) }
    }

    @Test fun importDocumentStoresExtractedTextWithNote() = runBlocking {
        val list = container.attachments.importDocument(Uri.fromFile(sampleXlsx()))
        val a = list.single()
        assertEquals(ATT_TEXT, a.type); assertEquals("satis.xlsx", a.name)
        assertEquals("2 sayfa · 5 satır", a.note)
        assertTrue(container.attachments.text(a)!!.contains("Elma,12.5,2026-11-06"))
    }

    @Test fun importDocumentOfBlankPdfExplainsScannedProblem() = runBlocking {
        try {
            container.attachments.importDocument(Uri.fromFile(pdf("", "", "")))
            fail("AttachmentException bekleniyordu")
        } catch (e: AttachmentException) {
            assertTrue(e.message, e.message!!.contains("taranmış"))
        }
    }

    // ---- Uzun belgeden ilgili bölümü seçme ----

    private fun bigDoc(): String = buildString {
        for (page in 1..120) {
            append("--- Sayfa $page ---\n")
            repeat(12) { append("Bu sayfada genel şirket politikaları ve prosedürleri anlatılmaktadır, ayrıntı yoktur. $page.$it\n") }
            if (page == 87) append("Fatura numarası 4711 olan siparişin ödeme vadesi 45 gündür ve gecikme faizi uygulanır.\n")
        }
    }

    @Test fun excerptReturnsWholeDocumentWhenItFits() {
        val r = DocExcerpt.select("kısa metin", "herhangi", 1000)
        assertFalse(r.trimmed); assertEquals("kısa metin", r.text)
    }

    @Test fun excerptFindsTheRelevantPageInAHugeDocument() {
        val doc = bigDoc()
        val r = DocExcerpt.select(doc, "Fatura 4711 ödeme vadesi kaç gün?", 8_000)
        assertTrue(r.trimmed)
        assertTrue(r.text.length <= 9_000)
        assertTrue(r.text, r.text.contains("Fatura numarası 4711"))
        assertTrue(r.text, r.text.contains("Sayfa 87"))
        assertTrue(r.text, r.text.contains("--- Sayfa 1 ---"))   // başlangıç her zaman var
        assertTrue(r.totalChars == doc.length)
    }

    @Test fun excerptToleratesTurkishSuffixesAndFallsBackToDocumentStart() {
        val doc = bigDoc()
        assertTrue(DocExcerpt.select(doc, "faturanın vadesi", 8_000).text.contains("Fatura numarası 4711"))
        val none = DocExcerpt.select(doc, "xyzzy qwerty", 4_000)
        assertTrue(none.text.startsWith("--- Sayfa 1 ---"))
        assertFalse(none.text.contains("4711"))
    }

    private class Source(val body: String) : AttachmentSource {
        override fun imageDataUrl(a: Attachment) = null
        override fun text(a: Attachment) = body
    }

    @Test fun contextUsesExcerptForLongAttachmentsAndAnswersTheLatestQuestion() {
        val chat = ChatEntity(id = 1, model = "m", createdAt = 0, updatedAt = 0)
        val att = Attachment(ATT_TEXT, "sozlesme.pdf", "f.txt")
        val history = listOf(
            MessageEntity(id = 1, chatId = 1, role = "user", content = "Bu sözleşmeyi incele", createdAt = 1, attachments = Attachments.encode(listOf(att))),
            MessageEntity(id = 2, chatId = 1, role = "assistant", content = "Tamam, inceledim.", createdAt = 2),
            MessageEntity(id = 3, chatId = 1, role = "user", content = "Fatura 4711 için vade kaç gün?", createdAt = 3),
        )
        val out = ContextBuilder.build(AppSettings(contextTokens = 8000), chat, emptyList(), history, source = Source(bigDoc()))
        val first = out[1].content
        assertTrue(first, first.contains("uzun belge: sorunuzla ilgili bölümler gösteriliyor"))
        assertTrue(first, first.contains("Fatura numarası 4711"))
        assertTrue("bağlam bütçeyi aşmamalı", first.length < 8000 * 3)
    }
}
