package com.kutu.chat

import androidx.test.core.app.ApplicationProvider
import com.kutu.chat.data.db.ChatEntity
import com.kutu.chat.data.db.MessageEntity
import com.kutu.chat.data.db.STATUS_DONE
import com.kutu.chat.data.db.STATUS_ERROR
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = KutuApp::class)
class EngineTest {
    private val container get() = ApplicationProvider.getApplicationContext<KutuApp>().container
    private lateinit var server: MockWebServer
    private val requests = java.util.concurrent.CopyOnWriteArrayList<String>()
    @Volatile private var failStream = false
    @Volatile private var answer = "Cevap 1"

    @Before fun setUp() {
        server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val body = request.body.readUtf8()
                requests += body
                return when {
                    body.contains("\"stream\":true") && failStream ->
                        MockResponse().setResponseCode(429).setBody("""{"detail":"Too many requests"}""")
                    body.contains("\"stream\":true") -> MockResponse().setHeader("Content-Type", "text/event-stream").setBody(
                        """data: {"choices":[{"delta":{"content":"$answer"}}]}""" + "\n\ndata: [DONE]\n\n",
                    )
                    body.contains("You compress chat history") ->
                        MockResponse().setBody("""{"choices":[{"message":{"content":"ÖZET METNİ"}}]}""")
                    body.contains("long-term memory") ->
                        MockResponse().setBody("""{"choices":[{"message":{"content":"{\"add\":[],\"remove\":[]}"}}]}""")
                    else -> MockResponse().setBody("""{"choices":[{"message":{"content":"Başlık"}}]}""")
                }
            }
        }
        server.start()
        container.settings.update { it.copy(apiKey = "nvapi-test", baseUrl = server.url("/v1").toString()) }
    }

    @After fun tearDown() = server.shutdown()

    private fun until(timeoutMs: Long = 15_000, cond: () -> Boolean) {
        val end = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < end && !cond()) Thread.sleep(50)
        assertTrue("zaman aşımı", cond())
    }

    private fun newChat(): Long = runBlocking { container.engine.createChat("meta/llama-3.3-70b-instruct") }
    private fun messages(id: Long) = runBlocking { container.db.messageDao().list(id) }
    private fun idle(id: Long) = !container.engine.isGenerating(id)

    @Test fun regenerateReplacesLastAnswerAndEditResendsFromThatPoint() {
        val id = newChat()
        container.engine.send(id, "ilk soru")
        until { messages(id).size == 2 && idle(id) }
        assertEquals("Cevap 1", messages(id)[1].content)

        answer = "Cevap 2"
        container.engine.regenerate(id)
        until { messages(id).lastOrNull()?.content == "Cevap 2" && idle(id) }
        assertEquals(listOf("user", "assistant"), messages(id).map { it.role })

        answer = "Cevap 3"
        container.engine.editAndResend(id, messages(id)[0].id, "düzeltilmiş soru")
        until { messages(id).lastOrNull()?.content == "Cevap 3" && idle(id) }
        assertEquals(listOf("düzeltilmiş soru", "Cevap 3"), messages(id).map { it.content })
    }

    @Test fun apiErrorIsStoredOnTheMessageAndRetrySucceeds() {
        val id = newChat()
        failStream = true
        container.engine.send(id, "merhaba")
        until { messages(id).lastOrNull()?.status == STATUS_ERROR && idle(id) }
        val failed = messages(id).last()
        assertTrue(failed.error, failed.error.contains("429") && failed.error.contains("Too many requests"))

        failStream = false
        container.engine.regenerate(id)
        until { messages(id).lastOrNull()?.status == STATUS_DONE && idle(id) }
        assertEquals(listOf("merhaba", "Cevap 1"), messages(id).map { it.content })
    }

    @Test fun longChatIsSummarizedAndNextRequestUsesSummaryNotOldMessages() {
        container.settings.update { it.copy(contextTokens = 4000, autoMemory = false) }
        val id = runBlocking {
            val chatId = container.db.chatDao().insert(ChatEntity(model = "m", titleLocked = true, createdAt = 1, updatedAt = 1))
            for (i in 1..10) {
                container.db.messageDao().insert(
                    MessageEntity(chatId = chatId, role = if (i % 2 == 1) "user" else "assistant", content = "m$i " + "x".repeat(2000), createdAt = i.toLong()),
                )
            }
            chatId
        }
        container.engine.send(id, "devam")
        until { runBlocking { container.db.chatDao().get(id)!!.summary } == "ÖZET METNİ" }
        val chat = runBlocking { container.db.chatDao().get(id)!! }
        val all = messages(id)
        // 12 mesajın (10 + soru + cevap) son 6'sı ham kalır, önceki 6'sı özetlenir.
        assertEquals(all[5].id, chat.summarizedUpTo)

        // Sonraki istek özet + yalnızca özetlenmemiş mesajları taşımalı.
        answer = "Cevap 2"
        requests.clear()
        container.engine.send(id, "yeni soru")
        until { requests.any { it.contains("\"stream\":true") } }
        val req = requests.first { it.contains("\"stream\":true") }
        assertTrue(req.contains("ÖZET METNİ"))
        assertTrue(!req.contains("m1 xxx") && !req.contains("m6 xxx"))
        assertTrue(req.contains("m7 xxx"))
        assertTrue(req.contains("yeni soru"))
    }

    @Test fun deletingChatCascadesToMessages() {
        val id = newChat()
        container.engine.send(id, "merhaba")
        until { messages(id).size == 2 && idle(id) }
        runBlocking { container.db.chatDao().delete(id) }
        assertEquals(0, messages(id).size)
    }
}
