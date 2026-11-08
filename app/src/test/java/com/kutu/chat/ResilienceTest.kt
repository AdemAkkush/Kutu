package com.kutu.chat

import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import com.kutu.chat.data.api.ApiException
import com.kutu.chat.data.api.ChatMessageDto
import com.kutu.chat.data.api.ChatRequest
import com.kutu.chat.data.api.NvidiaApi
import com.kutu.chat.data.db.MessageEntity
import com.kutu.chat.data.db.STATUS_DONE
import com.kutu.chat.data.db.STATUS_ERROR
import com.kutu.chat.domain.ChatEngine
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okhttp3.mockwebserver.SocketPolicy
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowPowerManager

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = KutuApp::class)
class ResilienceTest {
    private val app get() = ApplicationProvider.getApplicationContext<KutuApp>()
    private val container get() = app.container
    private lateinit var server: MockWebServer
    private val streamBodies = java.util.concurrent.CopyOnWriteArrayList<String>()
    private val responses = java.util.concurrent.ConcurrentLinkedQueue<() -> MockResponse>()

    private fun sse(vararg parts: String, done: Boolean = true): String =
        parts.joinToString("") { """data: {"choices":[{"delta":{"content":${org.json.JSONObject.quote(it)}}}]}""" + "\n\n" } +
            (if (done) "data: {\"choices\":[{\"delta\":{},\"finish_reason\":\"stop\"}]}\n\ndata: [DONE]\n\n" else "")

    private fun stream(vararg parts: String, done: Boolean = true) = {
        MockResponse().setHeader("Content-Type", "text/event-stream").setBody(sse(*parts, done = done))
    }

    @Before fun setUp() {
        server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val body = request.body.readUtf8()
                if (body.contains("\"stream\":true")) {
                    streamBodies += body
                    return responses.poll()?.invoke() ?: MockResponse().setResponseCode(500).setBody("""{"detail":"kuyruk boş"}""")
                }
                return if (body.contains("long-term memory")) MockResponse().setBody("""{"choices":[{"message":{"content":"{\"add\":[],\"remove\":[]}"}}]}""")
                else MockResponse().setBody("""{"choices":[{"message":{"content":"Başlık"}}]}""")
            }
        }
        server.start()
        container.settings.update { it.copy(apiKey = "nvapi-test", baseUrl = server.url("/v1").toString(), autoMemory = false) }
        container.engine.retryDelaysMs = listOf(20, 20, 20)
    }

    @After fun tearDown() = server.shutdown()

    private fun until(timeoutMs: Long = 15_000, cond: () -> Boolean) {
        val end = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < end && !cond()) Thread.sleep(30)
        assertTrue("zaman aşımı", cond())
    }

    private fun newChat() = runBlocking { container.engine.createChat("google/gemma-4-31b-it") }
    private fun messages(id: Long) = runBlocking { container.db.messageDao().list(id) }
    private fun idle(id: Long) = !container.engine.isGenerating(id)

    // ---- Akışın tamamlanıp tamamlanmadığı ----

    @Test fun streamThatEndsWithoutDoneOrFinishReasonIsReportedAsDropped() = runBlocking {
        responses += stream("Mer", "haba", done = false)
        val settings = container.settings.settings.value
        try {
            NvidiaApi().streamChat(settings, ChatRequest("m", listOf(ChatMessageDto("user", "x")), stream = true)).toList()
            fail("ApiException bekleniyordu")
        } catch (e: ApiException) {
            assertTrue(e.message, e.retriable)
            assertTrue(e.message, e.message!!.contains("yarıda kesildi"))
        }
    }

    @Test fun finishReasonAloneCountsAsCompleteEvenWithoutDone() = runBlocking {
        val body = """data: {"choices":[{"delta":{"content":"Tamam"}}]}""" + "\n\n" + """data: {"choices":[{"delta":{},"finish_reason":"stop"}]}""" + "\n\n"
        responses += { MockResponse().setHeader("Content-Type", "text/event-stream").setBody(body) }
        val events = NvidiaApi().streamChat(container.settings.settings.value, ChatRequest("m", listOf(ChatMessageDto("user", "x")), stream = true)).toList()
        assertEquals("Tamam", events.filterIsInstance<com.kutu.chat.data.api.StreamEvent.Chunk>().joinToString("") { it.content })
    }

    @Test fun connectionResetIsRetriableButAuthErrorsAreNot() = runBlocking {
        responses += { MockResponse().setHeader("Content-Type", "text/event-stream").setBody(sse("a".repeat(5000), done = false)).setSocketPolicy(SocketPolicy.DISCONNECT_DURING_RESPONSE_BODY) }
        try {
            NvidiaApi().streamChat(container.settings.settings.value, ChatRequest("m", listOf(ChatMessageDto("user", "x")), stream = true)).toList(); fail()
        } catch (e: ApiException) { assertTrue(e.message, e.retriable) }
        responses += { MockResponse().setResponseCode(401).setBody("""{"detail":"bad key"}""") }
        try {
            NvidiaApi().streamChat(container.settings.settings.value, ChatRequest("m", listOf(ChatMessageDto("user", "x")), stream = true)).toList(); fail()
        } catch (e: ApiException) { assertFalse(e.retriable) }
        responses += { MockResponse().setResponseCode(503).setBody("""{"detail":"busy"}""") }
        try {
            NvidiaApi().streamChat(container.settings.settings.value, ChatRequest("m", listOf(ChatMessageDto("user", "x")), stream = true)).toList(); fail()
        } catch (e: ApiException) { assertTrue(e.retriable) }
    }

    // ---- Motor: otomatik yeniden deneme ----

    @Test fun droppedStreamContinuesWhereItStoppedWithoutLosingTheStartOfTheAnswer() {
        responses += stream("Merhaba ", "ben Kutu", done = false)          // yarıda kesilir
        responses += stream(", bugün sana yardımcı olacağım.")             // devam eder
        val id = newChat()
        container.engine.send(id, "selam")
        until { messages(id).size == 2 && idle(id) }
        val answer = messages(id).last()
        assertEquals(STATUS_DONE, answer.status)
        assertEquals("Merhaba ben Kutu, bugün sana yardımcı olacağım.", answer.content)
        assertEquals(2, streamBodies.size)
        // İkinci istek, o ana dek yazılanı asistan mesajı olarak + "devam et" yönergesini taşımalı.
        assertTrue(streamBodies[1], streamBodies[1].contains("Merhaba ben Kutu") && streamBodies[1].contains(ChatEngine.CONTINUE_PROMPT))
        assertFalse(streamBodies[0].contains(ChatEngine.CONTINUE_PROMPT))
    }

    @Test fun dropBeforeAnyTextRestartsTheRequestFromScratch() {
        repeat(2) { responses += { MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AFTER_REQUEST) } }   // OkHttp kendi içinde bir kez daha dener
        responses += stream("Merhaba!")
        val id = newChat()
        container.engine.send(id, "selam")
        until { messages(id).size == 2 && idle(id) }
        assertEquals("Merhaba!", messages(id).last().content)
        assertTrue("istek yeniden gönderilmeli: ${streamBodies.size}", streamBodies.size >= 3)
        assertFalse(streamBodies.last().contains(ChatEngine.CONTINUE_PROMPT))
    }

    @Test fun serverBusyIsRetriedAndAuthErrorIsNot() {
        responses += { MockResponse().setResponseCode(503).setBody("""{"detail":"busy"}""") }
        responses += stream("Hazırım.")
        val id = newChat()
        container.engine.send(id, "selam")
        until { messages(id).size == 2 && idle(id) }
        assertEquals("Hazırım.", messages(id).last().content)

        streamBodies.clear()
        responses += { MockResponse().setResponseCode(401).setBody("""{"detail":"bad key"}""") }
        container.engine.send(id, "tekrar")
        until { messages(id).last().status == STATUS_ERROR && idle(id) }
        assertEquals("401 yeniden denenmemeli", 1, streamBodies.size)
    }

    @Test fun whenRetriesRunOutThePartialAnswerIsKeptAndCanBeContinuedManually() {
        container.engine.retryDelaysMs = listOf(10)
        responses += stream("Yanıtın ilk yarısı", done = false)
        repeat(2) { responses += { MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AFTER_REQUEST) } }   // tek yeniden deneme de başarısız
        val id = newChat()
        container.engine.send(id, "uzun bir şey anlat")
        until { messages(id).lastOrNull()?.status == STATUS_ERROR && idle(id) }
        val broken = messages(id).last()
        assertEquals("Yanıtın ilk yarısı", broken.content)
        assertTrue(broken.error, broken.error.contains("Devam et"))

        responses += stream(" ve ikinci yarısı.")
        container.engine.continueMessage(id)
        until { messages(id).last().status == STATUS_DONE && idle(id) }
        assertEquals(2, messages(id).size)
        assertEquals("Yanıtın ilk yarısı ve ikinci yarısı.", messages(id).last().content)
        assertTrue(streamBodies.last().contains("Yanıtın ilk yarısı") && streamBodies.last().contains(ChatEngine.CONTINUE_PROMPT))
    }

    @Test fun continueDoesNothingWhenThereIsNothingToResume() {
        val id = newChat()
        container.engine.continueMessage(id)
        Thread.sleep(300)
        assertEquals(0, messages(id).size); assertTrue(streamBodies.isEmpty())
    }

    @Test fun anInterruptedAnswerFromAKilledProcessCanBeContinued() {
        val id = newChat()
        runBlocking {
            container.db.messageDao().insert(MessageEntity(chatId = id, role = "user", content = "soru", createdAt = 1))
            container.db.messageDao().insert(MessageEntity(chatId = id, role = "assistant", content = "Yarım kalan", status = 1, createdAt = 2))
            container.engine.recoverInterrupted()       // süreç öldükten sonraki açılış
        }
        assertEquals(STATUS_ERROR, messages(id).last().status)
        responses += stream(" ve devamı.")
        container.engine.continueMessage(id)
        until { messages(id).last().status == STATUS_DONE && idle(id) }
        assertEquals("Yarım kalan ve devamı.", messages(id).last().content)
    }

    // ---- Ön plan servisi ----

    @Test fun serviceRunsInTheForegroundHoldingWakeAndWifiLocksAndReleasesThem() {
        val controller = Robolectric.buildService(GenerationService::class.java).create().startCommand(0, 1)
        val service = controller.get()
        assertNotNull("ön plan bildirimi", shadowOf(service).lastForegroundNotification)
        assertEquals(GenerationService.NOTIFICATION_ID, shadowOf(service).lastForegroundNotificationId)
        val lock = ShadowPowerManager.getLatestWakeLock()
        assertTrue("uyku kilidi tutulmalı", lock.isHeld)
        controller.destroy()
        assertFalse("servis kapanınca kilit bırakılmalı", lock.isHeld)
    }

    @Test fun foregroundServiceStartsWhileGeneratingAndStopsAfterwards() {
        responses += { stream("Yanıt metni")().setBodyDelay(600, java.util.concurrent.TimeUnit.MILLISECONDS) }
        val id = newChat()
        container.engine.send(id, "selam")
        val shadow = shadowOf(app)
        until(10_000) { shadow.peekNextStartedService() != null }
        val started: Intent = shadow.nextStartedService
        assertEquals(GenerationService::class.java.name, started.component!!.className)
        until { messages(id).size == 2 && idle(id) && container.engine.activeJobs.value == 0 }
        var stopped: Intent? = null
        until(10_000) { stopped = stopped ?: shadow.nextStoppedService; stopped != null }
        assertEquals(GenerationService::class.java.name, stopped!!.component!!.className)
    }
}
