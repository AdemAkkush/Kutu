package com.kutu.chat

import com.kutu.chat.data.AppSettings
import com.kutu.chat.data.api.ApiException
import com.kutu.chat.data.api.ChatMessageDto
import com.kutu.chat.data.api.ChatRequest
import com.kutu.chat.data.api.NvidiaApi
import com.kutu.chat.data.api.StreamEvent
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test

class NvidiaApiTest {
    private lateinit var server: MockWebServer
    private lateinit var settings: AppSettings
    private val api = NvidiaApi()
    private val request = ChatRequest("meta/llama", listOf(ChatMessageDto("user", "selam")), stream = true, temperature = 0.5f, maxTokens = 100)

    @Before fun setUp() {
        server = MockWebServer().also { it.start() }
        settings = AppSettings(apiKey = "nvapi-test", baseUrl = server.url("/v1").toString())
    }

    @After fun tearDown() = server.shutdown()

    @Test fun parseSseLineHandlesAllShapes() {
        assertNull(NvidiaApi.parseSseLine(": keep-alive"))
        assertNull(NvidiaApi.parseSseLine(""))
        assertEquals(StreamEvent.Done, NvidiaApi.parseSseLine("data: [DONE]"))
        assertEquals(StreamEvent.Chunk("Mer", ""), NvidiaApi.parseSseLine("""data: {"choices":[{"delta":{"content":"Mer"}}]}"""))
        assertEquals(StreamEvent.Chunk("", "hmm"), NvidiaApi.parseSseLine("""data: {"choices":[{"delta":{"reasoning_content":"hmm"}}]}"""))
        assertNull(NvidiaApi.parseSseLine("""data: {"choices":[{"delta":{"role":"assistant"}}]}"""))
        assertNull(NvidiaApi.parseSseLine("data: {bozuk"))
    }

    @Test fun streamsChunksAndSendsAuthAndBody() = runBlocking {
        val body = listOf(
            """data: {"choices":[{"delta":{"role":"assistant","content":""}}]}""",
            """data: {"choices":[{"delta":{"content":"Mer"}}]}""",
            """data: {"choices":[{"delta":{"content":"haba"}}]}""",
            "data: [DONE]",
            "",
        ).joinToString("\n\n")
        server.enqueue(MockResponse().setHeader("Content-Type", "text/event-stream").setBody(body))

        val events = api.streamChat(settings, request).toList()
        assertEquals("Merhaba", events.filterIsInstance<StreamEvent.Chunk>().joinToString("") { it.content })

        val recorded = server.takeRequest()
        assertEquals("/v1/chat/completions", recorded.path)
        assertEquals("Bearer nvapi-test", recorded.getHeader("Authorization"))
        val sent = recorded.body.readUtf8()
        assertTrue(sent, sent.contains("\"stream\":true"))
        assertTrue(sent, sent.contains("\"max_tokens\":100"))
        assertTrue(sent, sent.contains("\"model\":\"meta/llama\""))
    }

    @Test fun mapsHttpErrorsToReadableMessages() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(401).setBody("""{"status":401,"title":"Unauthorized","detail":"Authentication failed"}"""))
        try {
            api.streamChat(settings, request).toList()
            fail("ApiException bekleniyordu")
        } catch (e: ApiException) {
            assertEquals(401, e.code)
            assertTrue(e.message, e.message!!.contains("API anahtarınızı kontrol edin"))
            assertTrue(e.message, e.message!!.contains("Authentication failed"))
        }
    }

    @Test fun missingApiKeyFailsBeforeNetwork() = runBlocking {
        try {
            api.streamChat(settings.copy(apiKey = ""), request).toList()
            fail("ApiException bekleniyordu")
        } catch (e: ApiException) {
            assertTrue(e.message!!.contains("API anahtarı"))
        }
        assertEquals(0, server.requestCount)
    }

    @Test fun completeReturnsMessageContent() = runBlocking {
        server.enqueue(MockResponse().setBody("""{"choices":[{"message":{"role":"assistant","content":"Başlık"}}]}"""))
        val text = api.complete(settings, request.copy(stream = false))
        assertEquals("Başlık", text)
        assertTrue(server.takeRequest().body.readUtf8().contains("\"stream\":false"))
    }

    @Test fun listsModelsSortedAndDistinct() = runBlocking {
        server.enqueue(MockResponse().setBody("""{"object":"list","data":[{"id":"b/model"},{"id":"a/model"},{"id":"a/model"}]}"""))
        assertEquals(listOf("a/model", "b/model"), api.listModels(settings))
        assertEquals("/v1/models", server.takeRequest().path)
    }

    @Test fun extractsErrorMessageFromVariousShapes() {
        assertEquals("x", NvidiaApi.extractErrorMessage("""{"detail":"x"}"""))
        assertEquals("y", NvidiaApi.extractErrorMessage("""{"error":{"message":"y"}}"""))
        assertEquals("düz metin", NvidiaApi.extractErrorMessage("düz metin"))
        assertEquals("", NvidiaApi.extractErrorMessage(""))
    }
}
