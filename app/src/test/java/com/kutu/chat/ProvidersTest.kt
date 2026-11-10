package com.kutu.chat

import androidx.compose.ui.graphics.Color
import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import com.kutu.chat.data.AppSettings
import com.kutu.chat.data.db.AppDatabase
import com.kutu.chat.data.db.ChatEntity
import com.kutu.chat.data.db.STATUS_DONE
import com.kutu.chat.domain.ModelRef
import com.kutu.chat.domain.Providers
import com.kutu.chat.domain.Versions
import com.kutu.chat.ui.chatlist.groupChats
import com.kutu.chat.ui.components.highlightCode
import com.kutu.chat.ui.theme.CodeColors
import java.time.LocalDate
import java.time.ZoneOffset
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
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

class ModelRefTest {
    @Test fun nvidiaKeysStayBareForOldRecords() {
        val ref = ModelRef(Providers.NVIDIA, "google/gemma-4-31b-it")
        assertEquals("google/gemma-4-31b-it", ref.key)
        assertEquals(ref, ModelRef.parse(ref.key))
        assertEquals("gemma-4-31b-it", ref.display)
    }

    @Test fun otherProvidersArePrefixedAndRoundTrip() {
        val ref = ModelRef("openrouter", "anthropic/claude-sonnet-4.5:free")
        assertEquals("openrouter:anthropic/claude-sonnet-4.5:free", ref.key)
        assertEquals(ref, ModelRef.parse(ref.key))
    }

    @Test fun unknownPrefixIsTreatedAsNvidiaModelId() {
        // NVIDIA kimliklerinde ':' olabilir; tanınmayan önek sağlayıcı sayılmaz.
        assertEquals(ModelRef(Providers.NVIDIA, "foo:bar"), ModelRef.parse("foo:bar"))
    }

    @Test fun providerIdsAreUniqueAndUrlsAreHttps() {
        assertEquals(Providers.presets.size, Providers.presets.map { it.id }.toSet().size)
        Providers.presets.filter { it.id != Providers.CUSTOM }.forEach {
            assertTrue(it.id, it.baseUrl.startsWith("https://") && !it.baseUrl.endsWith("/"))
        }
    }
}

class ProviderSettingsTest {
    private val s = AppSettings(apiKey = "nvapi-x", providerKeys = mapOf("groq" to "gsk_1", "openai" to " "), customBaseUrl = "http://10.0.2.2:11434/v1")

    @Test fun configuredProvidersNeedAKeyOrAnAddress() {
        assertEquals(listOf("nvidia", "groq", "custom"), s.configuredProviders)
        assertEquals(emptyList<String>(), AppSettings().configuredProviders)
    }

    @Test fun forProviderSwapsKeyAndBaseUrl() {
        val g = s.forProvider("groq")
        assertEquals("gsk_1", g.apiKey)
        assertEquals("https://api.groq.com/openai/v1", g.baseUrl)
        val n = s.forProvider("nvidia")
        assertEquals("nvapi-x", n.apiKey)
        assertEquals(s.baseUrl, n.baseUrl)
    }

    @Test fun localServerWithoutKeyStillGetsAPlaceholder() {
        val c = s.forProvider("custom")
        assertEquals("local", c.apiKey)
        assertEquals("http://10.0.2.2:11434/v1", c.baseUrl)
        assertEquals("", AppSettings().forProvider("custom").apiKey)
    }
}

class GroupChatsTest {
    private val zone = ZoneOffset.UTC
    private val today = LocalDate.of(2026, 11, 10)
    private fun chat(id: Long, daysAgo: Long, pinned: Boolean = false) = ChatEntity(
        id = id, model = "m", pinned = pinned, createdAt = 0,
        updatedAt = today.minusDays(daysAgo).atTime(12, 0).toInstant(zone).toEpochMilli(),
    )

    @Test fun groupsByAgeInFixedOrderWithPinnedFirst() {
        val groups = groupChats(listOf(chat(1, 40), chat(2, 0), chat(3, 1), chat(4, 5), chat(5, 20), chat(6, 3, pinned = true), chat(7, 0)), today, zone)
        assertEquals(listOf("Sabitlenenler", "Bugün", "Dün", "Son 7 gün", "Son 30 gün", "Daha eski"), groups.map { it.first })
        assertEquals(listOf(2L, 7L), groups[1].second.map { it.id })
        assertEquals(listOf(6L), groups[0].second.map { it.id })
    }

    @Test fun emptyGroupsAreOmitted() {
        assertEquals(listOf("Bugün"), groupChats(listOf(chat(1, 0)), today, zone).map { it.first })
    }
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class CodeHighlightTest {
    private val c = CodeColors(Color.Black, Color.Black, Color.White, Color.Red, Color.Green, Color.Gray, Color.Blue, Color.Yellow)
    private fun colorAt(code: String, lang: String, needle: String): Color? {
        val a = highlightCode(code, lang, c)
        val i = code.indexOf(needle)
        return a.spanStyles.lastOrNull { it.start <= i && i < it.end }?.item?.color
    }

    @Test fun textIsNeverAltered() {
        val code = "fun main() {\n    val s = \"merhaba // değil\" // yorum\n    return 42\n}"
        assertEquals(code, highlightCode(code, "kotlin", c).text)
    }

    @Test fun keywordsStringsCommentsAndNumbersAreColoured() {
        val code = "fun main() {\n    val s = \"merhaba // değil\" // yorum\n    return 42\n}"
        assertEquals(Color.Red, colorAt(code, "kotlin", "fun"))
        assertEquals(Color.Green, colorAt(code, "kotlin", "merhaba"))
        assertEquals(Color.Green, colorAt(code, "kotlin", "// değil"))   // dize içindeki // yorum değildir
        assertEquals(Color.Gray, colorAt(code, "kotlin", "yorum"))
        assertEquals(Color.Blue, colorAt(code, "kotlin", "42"))
    }

    @Test fun hashCommentsInPython() {
        assertEquals(Color.Gray, colorAt("x = 1  # not\n", "python", "not"))
    }

    @Test fun plainTextIsLeftAlone() {
        assertTrue(highlightCode("for if while", "text", c).spanStyles.isEmpty())
    }
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class Migration2To3Test {
    @get:Rule val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(), AppDatabase::class.java, emptyList(), FrameworkSQLiteOpenHelperFactory(),
    )

    @Test fun existingChatsBecomeNvidiaChatsAndMessagesGetStats() {
        helper.createDatabase("mig23", 2).apply {
            execSQL("INSERT INTO chats (id,title,titleLocked,model,systemPrompt,summary,summarizedUpTo,pinned,createdAt,updatedAt,persona) VALUES (7,'Eski',1,'google/gemma-4-31b-it','','',0,0,10,20,'')")
            execSQL("INSERT INTO messages (id,chatId,role,content,reasoning,model,status,error,createdAt,attachments,versions,activeVersion) VALUES (1,7,'assistant','selam','','m',0,'',11,'','',0)")
            close()
        }
        val db = helper.runMigrationsAndValidate("mig23", 3, true, AppDatabase.MIGRATION_2_3)
        db.query("SELECT provider, model FROM chats WHERE id = 7").use {
            it.moveToFirst()
            assertEquals("nvidia", it.getString(0))
            assertEquals("google/gemma-4-31b-it", it.getString(1))
        }
        db.query("SELECT content, durationMs, firstTokenMs FROM messages WHERE id = 1").use {
            it.moveToFirst()
            assertEquals("selam", it.getString(0))
            assertEquals(0L, it.getLong(1))
            assertEquals(0L, it.getLong(2))
        }
    }
}

/** Sağlayıcıya göre yönlendirme: sohbetin sağlayıcısının adresi ve anahtarı kullanılır. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = KutuApp::class)
class ProviderEngineTest {
    private val container get() = ApplicationProvider.getApplicationContext<KutuApp>().container
    private lateinit var nvidia: MockWebServer
    private lateinit var other: MockWebServer
    private val nvidiaReqs = java.util.concurrent.CopyOnWriteArrayList<String>()
    private val otherReqs = java.util.concurrent.CopyOnWriteArrayList<String>()
    private val otherAuth = java.util.concurrent.CopyOnWriteArrayList<String>()
    @Volatile private var rejectMaxTokens = false
    @Volatile private var rejectTemperature = false

    private fun sse(text: String) = MockResponse().setHeader("Content-Type", "text/event-stream")
        .setBody("""data: {"choices":[{"delta":{"content":"$text"}}]}""" + "\n\ndata: [DONE]\n\n")

    @Before fun setUp() {
        nvidia = MockWebServer()
        nvidia.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val body = request.body.readUtf8()
                nvidiaReqs += body
                return if (body.contains("\"stream\":true")) sse("NVIDIA yanıtı")
                else MockResponse().setBody("""{"choices":[{"message":{"content":"Başlık"}}]}""")
            }
        }
        nvidia.start()
        other = MockWebServer()
        other.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val body = request.body.readUtf8()
                otherReqs += body
                otherAuth += request.getHeader("Authorization").orEmpty()
                return when {
                    rejectMaxTokens && body.contains("\"max_tokens\"") -> MockResponse().setResponseCode(400)
                        .setBody("""{"error":{"message":"Unsupported parameter: 'max_tokens' is not supported with this model. Use 'max_completion_tokens' instead."}}""")
                    rejectTemperature && body.contains("\"temperature\"") -> MockResponse().setResponseCode(400)
                        .setBody("""{"error":{"message":"Unsupported value: 'temperature' does not support 0.7 with this model."}}""")
                    body.contains("\"stream\":true") -> sse("Diğer yanıt")
                    else -> MockResponse().setBody("""{"choices":[{"message":{"content":"Özel başlık"}}]}""")
                }
            }
        }
        other.start()
        container.settings.update {
            it.copy(
                apiKey = "nvapi-test", baseUrl = nvidia.url("/v1").toString().trimEnd('/'),
                customBaseUrl = other.url("/v1").toString().trimEnd('/'), providerKeys = mapOf(Providers.CUSTOM to "sk-ozel"),
                autoMemory = false,
            )
        }
    }

    @After fun tearDown() { nvidia.shutdown(); other.shutdown() }

    private fun until(timeoutMs: Long = 15_000, cond: () -> Boolean) {
        val end = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < end && !cond()) Thread.sleep(50)
        assertTrue("zaman aşımı", cond())
    }
    private fun messages(id: Long) = runBlocking { container.db.messageDao().list(id) }
    private fun idle(id: Long) = !container.engine.isGenerating(id)
    private fun customChat() = runBlocking { container.engine.createChat("llama3.2", "", "", Providers.CUSTOM) }

    @Test fun chatOnAnotherProviderUsesItsAddressAndKey() {
        val id = customChat()
        container.engine.send(id, "selam")
        until { messages(id).size == 2 && idle(id) && messages(id)[1].status == STATUS_DONE }
        val m = messages(id)[1]
        assertEquals("Diğer yanıt", m.content)
        assertEquals("custom:llama3.2", m.model)
        assertTrue(m.durationMs > 0)
        val stream = otherReqs.first { it.contains("\"stream\":true") }
        assertTrue(stream, stream.contains("\"model\":\"llama3.2\""))
        assertTrue(otherAuth.all { it == "Bearer sk-ozel" })
        assertTrue("NVIDIA'ya akış isteği gitmemeli", nvidiaReqs.none { it.contains("\"stream\":true") })
    }

    @Test fun maxTokensIsRetriedAsMaxCompletionTokens() {
        rejectMaxTokens = true
        val id = customChat()
        container.engine.send(id, "selam")
        until { messages(id).size == 2 && idle(id) }
        assertEquals(STATUS_DONE, messages(id)[1].status)
        assertEquals("Diğer yanıt", messages(id)[1].content)
        val streams = otherReqs.filter { it.contains("\"stream\":true") }
        assertTrue(streams.first().contains("\"max_tokens\""))
        assertTrue(streams.last().contains("\"max_completion_tokens\"") && !streams.last().contains("\"max_tokens\""))
    }

    @Test fun temperatureIsDroppedWhenTheModelRejectsIt() {
        rejectTemperature = true
        val id = customChat()
        container.engine.send(id, "selam")
        until { messages(id).size == 2 && idle(id) }
        assertEquals(STATUS_DONE, messages(id)[1].status)
        assertFalse(otherReqs.last { it.contains("\"stream\":true") }.contains("\"temperature\""))
    }

    @Test fun regenerateWithAnotherModelAddsAVersionAndKeepsTheChatModel() {
        val id = runBlocking { container.engine.createChat("google/gemma-4-31b-it", "", "") }
        container.engine.send(id, "soru")
        until { messages(id).size == 2 && idle(id) && messages(id)[1].status == STATUS_DONE }
        assertEquals("NVIDIA yanıtı", messages(id)[1].content)

        container.engine.regenerate(id, ModelRef(Providers.CUSTOM, "llama3.2"))
        until { messages(id).lastOrNull()?.content == "Diğer yanıt" && idle(id) }
        val m = messages(id).last()
        assertEquals(2, messages(id).size)
        assertEquals("custom:llama3.2", m.model)
        assertEquals(2, Versions.parse(m.versions).size)
        val chat = runBlocking { container.db.chatDao().get(id) }!!
        assertEquals("nvidia", chat.provider)
        assertEquals("google/gemma-4-31b-it", chat.model)
    }
}
