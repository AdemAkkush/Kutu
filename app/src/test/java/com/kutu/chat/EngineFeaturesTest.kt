package com.kutu.chat

import androidx.test.core.app.ApplicationProvider
import com.kutu.chat.data.db.STATUS_DONE
import com.kutu.chat.data.db.STATUS_ERROR
import com.kutu.chat.domain.ATT_IMAGE
import com.kutu.chat.domain.ATT_TEXT
import com.kutu.chat.domain.Attachment
import com.kutu.chat.domain.Versions
import java.io.File
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = KutuApp::class)
class EngineFeaturesTest {
    private val container get() = ApplicationProvider.getApplicationContext<KutuApp>().container
    private lateinit var server: MockWebServer
    private val requests = java.util.concurrent.CopyOnWriteArrayList<String>()
    @Volatile private var answer = "Cevap 1"
    @Volatile private var rejectMultimodal = false

    @Before fun setUp() {
        server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val body = request.body.readUtf8()
                requests += body
                return when {
                    body.contains("\"stream\":true") && rejectMultimodal && body.contains("image_url") ->
                        MockResponse().setResponseCode(400).setBody("""{"message":"Received multimodal data but multimodal processing is not enabled."}""")
                    body.contains("\"stream\":true") -> MockResponse().setHeader("Content-Type", "text/event-stream").setBody(
                        """data: {"choices":[{"delta":{"content":"$answer"}}]}""" + "\n\ndata: [DONE]\n\n",
                    )
                    body.contains("long-term memory") -> MockResponse().setBody("""{"choices":[{"message":{"content":"{\"add\":[],\"remove\":[]}"}}]}""")
                    else -> MockResponse().setBody("""{"choices":[{"message":{"content":"Başlık"}}]}""")
                }
            }
        }
        server.start()
        container.settings.update { it.copy(apiKey = "nvapi-test", baseUrl = server.url("/v1").toString(), autoMemory = false) }
    }

    @After fun tearDown() = server.shutdown()

    private fun until(timeoutMs: Long = 15_000, cond: () -> Boolean) {
        val end = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < end && !cond()) Thread.sleep(50)
        assertTrue("zaman aşımı", cond())
    }

    private fun newChat(systemPrompt: String = "", persona: String = ""): Long =
        runBlocking { container.engine.createChat("google/gemma-4-31b-it", systemPrompt, persona) }
    private fun messages(id: Long) = runBlocking { container.db.messageDao().list(id) }
    private fun idle(id: Long) = !container.engine.isGenerating(id)
    private fun streamRequests() = requests.filter { it.contains("\"stream\":true") }

    @Test fun imageAndTextAttachmentsReachTheModel() {
        val img = File(container.attachments.dir, "a.jpg").apply { writeBytes(byteArrayOf(1, 2, 3)) }
        val txt = File(container.attachments.dir, "b.txt").apply { writeText("GİZLİ-DOSYA-İÇERİĞİ") }
        val id = newChat()
        container.engine.send(
            id, "bunları incele",
            listOf(Attachment(ATT_IMAGE, "foto.jpg", img.name, "image/jpeg"), Attachment(ATT_TEXT, "not.txt", txt.name)),
        )
        until { messages(id).size == 2 && idle(id) }
        val req = streamRequests().single()
        assertTrue(req, req.contains("\"type\":\"image_url\"") && req.contains("data:image/jpeg;base64,AQID"))
        assertTrue(req, req.contains("GİZLİ-DOSYA-İÇERİĞİ") && req.contains("[Ek dosya: not.txt]"))
        assertEquals(2, com.kutu.chat.domain.Attachments.parse(messages(id)[0].attachments).size)
    }

    @Test fun attachmentOnlyMessageIsAllowedAndTitleFallsBackToFileName() {
        val img = File(container.attachments.dir, "c.jpg").apply { writeBytes(byteArrayOf(5)) }
        val id = newChat()
        container.engine.send(id, "", listOf(Attachment(ATT_IMAGE, "manzara.jpg", img.name)))
        until { messages(id).size == 2 && idle(id) }
        assertTrue(streamRequests().single().contains("Ekli görseli incele."))
    }

    @Test fun modelWithoutVisionGetsHelpfulHint() {
        rejectMultimodal = true
        val img = File(container.attachments.dir, "d.jpg").apply { writeBytes(byteArrayOf(5)) }
        val id = newChat()
        container.engine.send(id, "bak", listOf(Attachment(ATT_IMAGE, "x.jpg", img.name)))
        until { messages(id).lastOrNull()?.status == STATUS_ERROR && idle(id) }
        assertTrue(messages(id).last().error, messages(id).last().error.contains("görselleri desteklemiyor olabilir"))
    }

    @Test fun regenerateKeepsVersionsAndTheyCanBeSwitched() {
        val id = newChat()
        container.engine.send(id, "soru")
        until { messages(id).size == 2 && idle(id) }
        answer = "Cevap 2"
        container.engine.regenerate(id)
        until { messages(id).lastOrNull()?.content == "Cevap 2" && idle(id) }
        answer = "Cevap 3"
        container.engine.regenerate(id)
        until { messages(id).lastOrNull()?.content == "Cevap 3" && idle(id) }

        var m = messages(id).last()
        assertEquals(2, m.activeVersion)
        assertEquals(3, Versions.count(m))
        assertEquals(2, messages(id).size)

        runBlocking { container.engine.switchVersion(m.id, 0) }
        m = messages(id).last()
        assertEquals("Cevap 1", m.content); assertEquals(0, m.activeVersion)
        runBlocking { container.engine.switchVersion(m.id, 1) }
        assertEquals("Cevap 2", messages(id).last().content)
        runBlocking { container.engine.switchVersion(m.id, 2) }
        assertEquals("Cevap 3", messages(id).last().content)

        // Etkin sürüm sonraki isteğe taşınmalı.
        runBlocking { container.engine.switchVersion(m.id, 0) }
        requests.clear()
        answer = "Cevap 4"
        container.engine.send(id, "devam")
        until { streamRequests().isNotEmpty() && idle(id) }
        val req = streamRequests().first()
        assertTrue(req, req.contains("Cevap 1") && !req.contains("Cevap 2") && !req.contains("Cevap 3"))
    }

    @Test fun failedRegenerationReplacesInsteadOfAddingVersions() {
        val id = newChat()
        container.engine.send(id, "soru")
        until { messages(id).size == 2 && idle(id) }
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest) = MockResponse().setResponseCode(500).setBody("""{"detail":"boom"}""")
        }
        container.engine.regenerate(id)
        until { messages(id).lastOrNull()?.status == STATUS_ERROR && idle(id) }
        assertEquals(2, Versions.count(messages(id).last()))   // Cevap 1 + hatalı deneme

        container.engine.regenerate(id)   // hatalı deneme sürüm olarak biriktirilmez
        until { messages(id).lastOrNull()?.status == STATUS_ERROR && idle(id) }
        assertEquals(2, Versions.count(messages(id).last()))
    }

    @Test fun deleteMessageRemovesUserMessageTogetherWithItsReplyAndFiles() {
        val img = File(container.attachments.dir, "e.jpg").apply { writeBytes(byteArrayOf(7)) }
        val id = newChat()
        container.engine.send(id, "bir", listOf(Attachment(ATT_IMAGE, "e.jpg", img.name)))
        until { messages(id).size == 2 && idle(id) }
        container.engine.send(id, "iki")
        until { messages(id).size == 4 && idle(id) }

        runBlocking { container.engine.deleteMessage(id, messages(id)[0].id) }
        assertEquals(listOf("iki", "Cevap 1"), messages(id).map { it.content })
        assertFalse(img.exists())

        runBlocking { container.engine.deleteMessage(id, messages(id)[1].id) }   // yalnızca yanıt
        assertEquals(listOf("iki"), messages(id).map { it.content })
    }

    @Test fun deletingChatRemovesItsAttachmentFiles() {
        val img = File(container.attachments.dir, "f.jpg").apply { writeBytes(byteArrayOf(7)) }
        val id = newChat()
        container.engine.send(id, "x", listOf(Attachment(ATT_IMAGE, "f.jpg", img.name)))
        until { messages(id).size == 2 && idle(id) }
        runBlocking { container.engine.deleteChat(id) }
        assertFalse(img.exists())
        assertEquals(0, messages(id).size)
    }

    @Test fun editResendKeepsAttachments() {
        val img = File(container.attachments.dir, "g.jpg").apply { writeBytes(byteArrayOf(7)) }
        val id = newChat()
        container.engine.send(id, "eski", listOf(Attachment(ATT_IMAGE, "g.jpg", img.name)))
        until { messages(id).size == 2 && idle(id) }
        container.engine.editAndResend(id, messages(id)[0].id, "yeni metin")
        until { messages(id).size == 2 && messages(id)[0].content == "yeni metin" && idle(id) }
        assertEquals(1, com.kutu.chat.domain.Attachments.parse(messages(id)[0].attachments).size)
        assertTrue(img.exists())
        assertTrue(streamRequests().last().contains("image_url"))
    }

    @Test fun personaSystemPromptIsUsedForTheChat() {
        val id = newChat("Sen bir korsan kaptansın.", "🏴‍☠️ Korsan")
        container.engine.send(id, "merhaba")
        until { messages(id).size == 2 && idle(id) }
        assertTrue(streamRequests().single().contains("Sen bir korsan kaptansın."))
        assertEquals("🏴‍☠️ Korsan", runBlocking { container.db.chatDao().get(id)!!.persona })
        assertEquals(STATUS_DONE, messages(id).last().status)
    }
}
