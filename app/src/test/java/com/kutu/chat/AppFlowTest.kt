package com.kutu.chat

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.core.app.ApplicationProvider
import com.kutu.chat.data.db.ChatEntity
import com.kutu.chat.data.db.MessageEntity
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Uygulamayı Robolectric içinde gerçekten açıp sahte bir NVIDIA sunucusuyla uçtan uca dener. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = KutuApp::class)
class AppFlowTest {
    private val container get() = ApplicationProvider.getApplicationContext<KutuApp>().container
    private lateinit var server: MockWebServer
    private val requests = mutableListOf<String>()

    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Before fun setUp() {
        server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val body = request.body.readUtf8()
                requests += "${request.path} $body"
                return when {
                    request.path!!.endsWith("/models") ->
                        MockResponse().setBody("""{"data":[{"id":"meta/llama-3.3-70b-instruct"},{"id":"nvidia/embed-qa-4"}]}""")
                    body.contains("\"stream\":true") -> MockResponse().setHeader("Content-Type", "text/event-stream").setBody(
                        listOf(
                            """data: {"choices":[{"delta":{"content":"Merhaba "}}]}""",
                            """data: {"choices":[{"delta":{"content":"**Adem**!"}}]}""",
                            "data: [DONE]", "",
                        ).joinToString("\n\n"),
                    )
                    body.contains("long-term memory") ->
                        MockResponse().setBody("""{"choices":[{"message":{"content":"{\"add\":[\"Kullanıcının adı Adem.\"],\"remove\":[]}"}}]}""")
                    else -> MockResponse().setBody("""{"choices":[{"message":{"content":"Selamlaşma"}}]}""")
                }
            }
        }
        server.start()
        container.settings.update { it.copy(apiKey = "nvapi-test", baseUrl = server.url("/v1").toString()) }
    }

    @After fun tearDown() = server.shutdown()

    @Test fun launchesAndShowsEmptyState() {
        compose.onNodeWithText("Merhaba 👋").assertIsDisplayed()
        compose.onNodeWithText("Mesaj yaz…").assertIsDisplayed()
    }

    @Test fun sendingMessageStreamsAnswerThenLearnsMemoryAndTitle() {
        compose.onNodeWithText("Mesaj yaz…").performTextInput("Merhaba, adım Adem")
        compose.onNodeWithContentDescription("Gönder").performClick()

        compose.waitUntil(15_000) {
            compose.onAllNodesWithTextCompat("Merhaba Adem!") || compose.onAllNodesWithTextCompat("Merhaba Adem")
        }

        // Arka plan işleri: başlık + hafıza.
        val db = container.db
        val deadline = System.currentTimeMillis() + 15_000
        while (System.currentTimeMillis() < deadline) {
            val mems = runBlocking { db.memoryDao().list() }
            val chat = runBlocking { db.chatDao().latest() }
            if (mems.isNotEmpty() && chat?.title == "Selamlaşma") break
            Thread.sleep(100)
        }
        val chat = runBlocking { db.chatDao().latest() }!!
        val msgs = runBlocking { db.messageDao().list(chat.id) }
        assertEquals(listOf("user", "assistant"), msgs.map { it.role })
        assertEquals("Merhaba **Adem**!", msgs[1].content)
        assertEquals("Selamlaşma", chat.title)
        assertEquals(listOf("Kullanıcının adı Adem."), runBlocking { db.memoryDao().list() }.map { it.content })
    }

    @Test fun nextChatReceivesStoredMemoryAndSummaryInSystemPrompt() {
        runBlocking {
            container.db.memoryDao().insert(
                com.kutu.chat.data.db.MemoryEntity(content = "Kullanıcı Kotlin geliştiricisi.", createdAt = 1, updatedAt = 1),
            )
            val id = container.db.chatDao().insert(ChatEntity(model = "m", summary = "Önceki konuşma: Android uygulaması.", createdAt = 1, updatedAt = 1))
            container.db.messageDao().insert(MessageEntity(chatId = id, role = "user", content = "eski", createdAt = 1))
            container.db.messageDao().insert(MessageEntity(chatId = id, role = "assistant", content = "eski cevap", createdAt = 2))
            container.engine.send(id, "devam edelim")
        }
        val deadline = System.currentTimeMillis() + 15_000
        while (System.currentTimeMillis() < deadline && requests.none { it.contains("\"stream\":true") }) Thread.sleep(100)
        val streamReq = requests.first { it.contains("\"stream\":true") }
        assertTrue(streamReq, streamReq.contains("Kullanıcı Kotlin geliştiricisi."))
        assertTrue(streamReq, streamReq.contains("Önceki konuşma: Android uygulaması."))
        assertTrue(streamReq, streamReq.contains("devam edelim"))
    }

    private fun androidx.compose.ui.test.junit4.AndroidComposeTestRule<*, *>.onAllNodesWithTextCompat(t: String) =
        onAllNodes(androidx.compose.ui.test.hasText(t, substring = true)).fetchSemanticsNodes().isNotEmpty()
}
