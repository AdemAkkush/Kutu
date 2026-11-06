package com.kutu.chat

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.exifinterface.media.ExifInterface
import androidx.test.core.app.ApplicationProvider
import com.kutu.chat.data.AttachmentException
import com.kutu.chat.data.AttachmentStore
import com.kutu.chat.data.Speaker
import com.kutu.chat.data.api.ChatMessageDto
import com.kutu.chat.data.api.ChatRequest
import com.kutu.chat.data.api.NvidiaApi
import com.kutu.chat.data.db.ChatEntity
import com.kutu.chat.data.db.MemoryEntity
import com.kutu.chat.data.db.MessageEntity
import com.kutu.chat.data.db.PersonaEntity
import com.kutu.chat.data.db.TemplateEntity
import com.kutu.chat.domain.ATT_IMAGE
import com.kutu.chat.domain.ATT_TEXT
import com.kutu.chat.domain.Attachment
import com.kutu.chat.domain.AttachmentSource
import com.kutu.chat.domain.Attachments
import com.kutu.chat.domain.ContextBuilder
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], application = KutuApp::class)
class FeaturesTest {
    private val container get() = ApplicationProvider.getApplicationContext<KutuApp>().container
    private val store get() = container.attachments

    // ---- Çok kipli mesaj biçimi ----

    @Test fun imagesAreSerializedAsOpenAiContentParts() {
        val req = ChatRequest("m", listOf(ChatMessageDto("user", "ne var?", listOf("data:image/jpeg;base64,AAA")), ChatMessageDto("assistant", "düz")), stream = true)
        val json = NvidiaApi.JSON.encodeToString(req)
        assertTrue(json, json.contains("""{"role":"user","content":[{"type":"text","text":"ne var?"},{"type":"image_url","image_url":{"url":"data:image/jpeg;base64,AAA"}}]}"""))
        assertTrue(json, json.contains("""{"role":"assistant","content":"düz"}"""))
    }

    // ---- Bağlam: ekler ----

    private class FakeSource : AttachmentSource {
        override fun imageDataUrl(a: Attachment) = "data:image/jpeg;base64,${a.name}"
        override fun text(a: Attachment) = if (a.name == "bos.txt") null else "icerik-${a.name}"
    }

    private fun msg(id: Long, role: String, text: String, atts: List<Attachment> = emptyList()) =
        MessageEntity(id = id, chatId = 1, role = role, content = text, createdAt = id, attachments = Attachments.encode(atts))

    @Test fun contextInlinesTextFilesAndSendsOnlyRecentImages() {
        val chat = ChatEntity(id = 1, model = "m", createdAt = 0, updatedAt = 0)
        val old = Attachment(ATT_IMAGE, "eski.jpg", "f1.jpg")
        val recent = Attachment(ATT_IMAGE, "yeni.jpg", "f2.jpg")
        val file = Attachment(ATT_TEXT, "kod.kt", "f3.txt")
        val history = listOf(
            msg(1, "user", "ilk", listOf(old)), msg(2, "assistant", "a"),
            msg(3, "user", "b"), msg(4, "assistant", "c"),
            msg(5, "user", "", listOf(recent, file)),
        )
        val out = ContextBuilder.usableMessages(chat, history, FakeSource())
        assertTrue(out[0].images.isEmpty())
        assertTrue(out[0].content, out[0].content.contains("[Görsel: eski.jpg]"))
        val last = out.last()
        assertEquals(listOf("data:image/jpeg;base64,yeni.jpg"), last.images)
        assertTrue(last.content, last.content.contains("[Ek dosya: kod.kt]") && last.content.contains("icerik-kod.kt"))
    }

    @Test fun imageOnlyMessageGetsDefaultPrompt() {
        val chat = ChatEntity(id = 1, model = "m", createdAt = 0, updatedAt = 0)
        val out = ContextBuilder.usableMessages(chat, listOf(msg(1, "user", "", listOf(Attachment(ATT_IMAGE, "a.jpg", "f.jpg")))), FakeSource())
        assertEquals("Ekli görseli incele.", out.single().content)
        assertEquals(1, out.single().images.size)
    }

    // ---- Dosya içe aktarma ----

    private fun tempFile(name: String, bytes: ByteArray) = File(container.app.cacheDir, name).apply { writeBytes(bytes) }

    @Test fun textFileIsImportedAndBinaryRejected() = runBlocking {
        val ok = store.importText(Uri.fromFile(tempFile("not.kt", "fun main() = println(\"merhaba\")".toByteArray())))
        assertEquals(ATT_TEXT, ok.type)
        assertEquals("fun main() = println(\"merhaba\")", store.text(ok))

        try {
            store.importText(Uri.fromFile(tempFile("x.bin", byteArrayOf(1, 2, 0, 3, 4))))
            fail("AttachmentException bekleniyordu")
        } catch (e: AttachmentException) {
            assertTrue(e.message, e.message!!.contains("metin dosyası değil"))
        }
    }

    @Test fun longTextFileIsTruncated() = runBlocking {
        val big = "satır\n".repeat(60_000).toByteArray()
        val a = store.importText(Uri.fromFile(tempFile("buyuk.txt", big)))
        val text = store.text(a)!!
        assertTrue(text.length < big.size)
        assertTrue(text.endsWith("[… dosya çok uzun olduğu için burada kesildi]"))
    }

    @Test fun largeRotatedImageIsDownscaledAndOrientedUpright() = runBlocking {
        val bmp = Bitmap.createBitmap(3000, 1500, Bitmap.Config.ARGB_8888).apply { eraseColor(0xFF2266CC.toInt()) }
        val src = File(container.app.cacheDir, "foto.jpg")
        src.outputStream().use { bmp.compress(Bitmap.CompressFormat.JPEG, 90, it) }
        ExifInterface(src.path).apply { setAttribute(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_ROTATE_90.toString()); saveAttributes() }

        val a = store.importImage(Uri.fromFile(src))
        assertEquals(ATT_IMAGE, a.type)
        val out = BitmapFactory.decodeFile(store.file(a).path)
        // 3000x1500, 90° döndürüldü → dikey; uzun kenar 1536'ya indirildi.
        assertTrue("${out.width}x${out.height}", out.height > out.width)
        assertTrue("${out.width}x${out.height}", maxOf(out.width, out.height) <= AttachmentStore.MAX_IMAGE_SIDE)
        assertTrue(store.imageDataUrl(a)!!.startsWith("data:image/jpeg;base64,"))
    }

    @Test fun sweepRemovesOnlyUnreferencedFiles() {
        val keep = File(store.dir, "keep.txt").apply { writeText("a") }
        val drop = File(store.dir, "drop.txt").apply { writeText("b") }
        store.sweep(setOf("keep.txt"), minAgeMs = 0)
        assertTrue(keep.exists())
        assertFalse(drop.exists())

        val fresh = File(store.dir, "fresh.txt").apply { writeText("c") }
        store.sweep(emptySet())   // varsayılan: yeni dosyalara dokunma
        assertTrue(fresh.exists())
    }

    // ---- Sesli okuma metni ----

    @Test fun markdownIsReducedToSpeakableText() {
        val plain = Speaker.plainText("## Başlık\n\n**Kalın** ve `kod` için [bağlantı](https://x.com).\n\n```kotlin\nval a = 1\n```\n\n- madde bir\n- madde iki")
        assertFalse(plain, plain.contains("*") || plain.contains("`") || plain.contains("#") || plain.contains("https"))
        assertTrue(plain, plain.contains("kod bloğu"))
        assertTrue(plain, plain.contains("madde bir"))
        assertFalse(plain, plain.contains("val a"))
    }

    @Test fun longTextIsChunkedAtSentenceBoundaries() {
        val text = (1..200).joinToString(" ") { "Bu $it numaralı cümledir." }
        val chunks = Speaker.chunk(text, 500)
        assertTrue(chunks.size > 1)
        assertTrue(chunks.all { it.length <= 500 })
        assertEquals(text.replace(Regex("\\s+"), " "), chunks.joinToString(" ").replace(Regex("\\s+"), " "))
        assertTrue(Speaker.looksTurkish("Günaydın, nasılsın?"))
        assertFalse(Speaker.looksTurkish("Good morning"))
    }

    // ---- Yedekleme ----

    @Test fun backupRoundTripRestoresEverythingAndSecondImportIsIdempotent() = runBlocking {
        val db = container.db
        val file = File(store.dir, "ek-1.jpg").apply { writeBytes(byteArrayOf(9, 8, 7)) }
        val chatId = db.chatDao().insert(ChatEntity(title = "Yedek sohbeti", model = "m", pinned = true, persona = "💻 Kod", createdAt = 111, updatedAt = 222))
        val ids = (1..4).map {
            db.messageDao().insert(
                MessageEntity(
                    chatId = chatId, role = if (it % 2 == 1) "user" else "assistant", content = "mesaj $it", createdAt = it.toLong(),
                    attachments = if (it == 1) Attachments.encode(listOf(Attachment(ATT_IMAGE, "ek.jpg", file.name))) else "",
                ),
            )
        }
        db.chatDao().setSummary(chatId, "Özet", ids[1])
        db.memoryDao().insert(MemoryEntity(content = "Kullanıcının adı Adem.", createdAt = 1, updatedAt = 1))
        db.personaDao().insert(PersonaEntity(name = "Benim", emoji = "🧪", systemPrompt = "Test", createdAt = 1))
        db.templateDao().insert(TemplateEntity(title = "Şablon", text = "Metin", createdAt = 1))

        val zip = ByteArrayOutputStream().also { container.backup.export(it) }.toByteArray()

        // Her şeyi sil (yeni telefon gibi).
        db.chatDao().delete(chatId); db.memoryDao().deleteAll()
        db.personaDao().list().forEach { db.personaDao().delete(it.id) }
        db.templateDao().list().forEach { db.templateDao().delete(it.id) }
        file.delete()

        val first = container.backup.import(ByteArrayInputStream(zip))
        assertEquals(1, first.chats); assertEquals(4, first.messages); assertEquals(1, first.memories)
        assertEquals(1, first.personas); assertEquals(1, first.templates)

        val chat = db.chatDao().listAll().single()
        assertEquals("Yedek sohbeti", chat.title); assertTrue(chat.pinned); assertEquals("💻 Kod", chat.persona)
        val msgs = db.messageDao().list(chat.id)
        assertEquals(listOf("mesaj 1", "mesaj 2", "mesaj 3", "mesaj 4"), msgs.map { it.content })
        // Özet işaretçisi yeni id'lere taşınmış olmalı.
        assertEquals("Özet", chat.summary); assertEquals(msgs[1].id, chat.summarizedUpTo)
        // Ek dosyası geri yazılmış.
        val att = Attachments.parse(msgs[0].attachments).single()
        assertEquals(listOf<Byte>(9, 8, 7), store.file(att).readBytes().toList())

        val second = container.backup.import(ByteArrayInputStream(zip))
        assertEquals(0, second.chats); assertEquals(1, second.skippedChats)
        assertEquals(0, second.memories); assertEquals(0, second.personas); assertEquals(0, second.templates)
        assertEquals(1, db.chatDao().listAll().size)
    }

    @Test fun invalidBackupGivesClearError() = runBlocking {
        try {
            container.backup.import(ByteArrayInputStream("bu zip değil".toByteArray()))
            fail("BackupException bekleniyordu")
        } catch (e: com.kutu.chat.data.BackupException) {
            assertNotNull(e.message)
        }
    }
}
