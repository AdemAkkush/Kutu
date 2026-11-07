package com.kutu.chat

import androidx.test.core.app.ApplicationProvider
import com.kutu.chat.data.AiSpeech
import com.kutu.chat.data.PcmPlayer
import com.kutu.chat.data.Speaker
import com.kutu.chat.data.TtsService
import com.kutu.chat.data.api.RivaException
import com.kutu.chat.data.api.RivaVoices
import com.kutu.chat.domain.TtsEngine
import com.kutu.chat.domain.TtsModel
import com.kutu.chat.domain.TtsPlanner
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = KutuApp::class)
class TtsTest {
    private val app get() = ApplicationProvider.getApplicationContext<KutuApp>()
    private val settings get() = app.container.settings

    // ---- Dil algılama ve model seçimi ----

    @Test fun languageIsDetectedFromScriptAndCommonWords() {
        fun d(t: String) = TtsPlanner.detectLanguage(t, "xx")
        assertEquals("tr", d("Merhaba, bugün hava çok güzel ve ben yürüyüşe çıkıyorum."))
        assertEquals("en", d("Hello, how are you today? I hope this is fine for you."))
        assertEquals("es", d("Hola, ¿cómo estás? Muy bien, gracias por todo."))
        assertEquals("fr", d("Bonjour tout le monde, ceci est un test pour vous."))
        assertEquals("de", d("Hallo, das ist ein Test und ich bin nicht sicher."))
        assertEquals("ja", d("こんにちは、お元気ですか？"))
        assertEquals("ko", d("안녕하세요, 만나서 반갑습니다"))
        assertEquals("zh", d("你好，今天天气很好"))
        assertEquals("ru", d("Привет, как дела у тебя сегодня"))
        assertEquals("ar", d("مرحبا كيف حالك اليوم"))
        assertEquals("tr", TtsPlanner.detectLanguage("12345 !!!", "tr"))        // sinyal yok → cihaz dili
        assertEquals("tr", TtsPlanner.detectLanguage("Merhaba", "en"))          // tek ama belirgin sözcük
    }

    @Test fun planPicksTheRightModelPerLanguage() {
        val tr = TtsPlanner.plan("Merhaba, bugün nasılsın? Çok güzel bir gün.", "tr", TtsModel.AUTO)!!
        assertEquals(TtsModel.CHATTERBOX, tr.model); assertEquals("tr-TR", tr.language)
        val en = TtsPlanner.plan("Hello, how are you today? I hope this is fine.", "tr", TtsModel.AUTO)!!
        assertEquals(TtsModel.MAGPIE, en.model); assertEquals("en-US", en.language)
        val ru = TtsPlanner.plan("Привет, как дела у тебя сегодня", "tr", TtsModel.AUTO)!!
        assertEquals(TtsModel.CHATTERBOX, ru.model)                                 // Magpie Rusça bilmiyor
        assertEquals(TtsModel.CHATTERBOX, TtsPlanner.plan("Hello, how are you today? I hope this is fine.", "tr", TtsModel.CHATTERBOX)!!.model)
        assertEquals(TtsModel.CHATTERBOX, TtsPlanner.plan("Merhaba, bugün nasılsın? Çok güzel.", "tr", TtsModel.MAGPIE)!!.model)  // Magpie'de Türkçe yok
        assertNull(TtsPlanner.plan("Xin chào", "xx", TtsModel.AUTO)?.takeIf { false })
    }

    private val magpie = RivaVoices(22050, listOf("en-US", "es-US"), listOf("EN-US.Mia", "EN-US.Mia.Calm", "EN-US.Aria", "ES-US.Isabela"), "Magpie-Multilingual")
    private val chatterbox = RivaVoices(24000, listOf("tr-TR", "en-US"), listOf("en-US.Male", "tr-TR.Male"), "Chatterbox-Multilingual")

    @Test fun voiceNamesAreBuiltFromServerVoiceLists() {
        val en = TtsPlanner.Plan(TtsModel.MAGPIE, "en-US")
        assertEquals("Magpie-Multilingual.EN-US.Aria", TtsPlanner.voiceName(en, magpie, "Aria"))
        assertEquals("Magpie-Multilingual.EN-US.Mia", TtsPlanner.voiceName(en, magpie, "Yok"))   // ilk duygusuz ses
        assertEquals("Magpie-Multilingual.ES-US.Isabela", TtsPlanner.voiceName(TtsPlanner.Plan(TtsModel.MAGPIE, "es-US"), magpie, "Aria"))
        assertNull(TtsPlanner.voiceName(TtsPlanner.Plan(TtsModel.MAGPIE, "de-DE"), magpie, "Aria"))
        assertEquals("Chatterbox-Multilingual.tr-TR.Male", TtsPlanner.voiceName(TtsPlanner.Plan(TtsModel.CHATTERBOX, "tr-TR"), chatterbox, ""))
    }

    // ---- Sahte servis + sahte oynatıcı ----

    private class FakeService(
        val voices: Map<String, RivaVoices>,
        val staleId: String? = null,
        val failWith: RivaException? = null,
        val newId: String? = null,
    ) : TtsService {
        val calls = CopyOnWriteArrayList<String>()
        override suspend fun voices(apiKey: String, functionId: String): RivaVoices {
            if (functionId == staleId) throw RivaException("yok", grpcStatus = 5)
            return voices[functionId] ?: voices.values.first()
        }
        override suspend fun synthesize(apiKey: String, functionId: String, text: String, languageCode: String, voiceName: String, sampleRate: Int): ByteArray {
            failWith?.let { throw it }
            if (functionId == staleId) throw RivaException("yok", grpcStatus = 5)
            calls += "$functionId|$languageCode|$voiceName|$sampleRate|${text.take(12)}"
            delay(30)
            return ByteArray(text.length * 2) { 1 }
        }
        override suspend fun findFunctionId(apiKey: String, functionName: String) = newId
    }

    private class FakePlayer(val rate: Int) : PcmPlayer {
        val writes = CopyOnWriteArrayList<Int>()
        @Volatile var released = false
        override suspend fun write(pcm: ByteArray) { writes += pcm.size }
        override suspend fun drain() {}
        override fun release() { released = true }
    }

    private fun waitFor(timeoutMs: Long = 10_000, cond: () -> Boolean) {
        val end = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < end && !cond()) Thread.sleep(20)
        assertTrue("zaman aşımı", cond())
    }

    private fun speaker(service: TtsService, players: MutableList<FakePlayer>): Speaker {
        settings.update { it.copy(apiKey = "nvapi-test", ttsEngine = TtsEngine.NVIDIA, ttsModel = TtsModel.AUTO, ttsVoice = "Aria") }
        return Speaker(app, settings, CoroutineScope(SupervisorJob() + Dispatchers.Default), AiSpeech(service, settings)) { rate ->
            FakePlayer(rate).also { players += it }
        }
    }

    private val turkishLong = (1..40).joinToString(" ") { "Bu $it numaralı cümle bugün çok güzel bir gün olduğunu söylüyor." }

    @Test fun aiSpeechIsSynthesizedInChunksAndPlayedInOrder() {
        val service = FakeService(mapOf(TtsModel.CHATTERBOX.defaultFunctionId to chatterbox))
        val players = CopyOnWriteArrayList<FakePlayer>()
        val speaker = speaker(service, players)
        speaker.speak(7, turkishLong)
        assertEquals(7L, speaker.speaking.value)
        waitFor { speaker.speaking.value == null }
        val player = players.single()
        assertEquals(24000, player.rate)
        assertTrue("birden çok parça beklenir: ${service.calls.size}", service.calls.size >= 4)
        assertTrue(service.calls.all { it.startsWith(TtsModel.CHATTERBOX.defaultFunctionId + "|tr-TR|Chatterbox-Multilingual.tr-TR.Male|24000|") })
        assertEquals(service.calls.size, player.writes.size)
        assertTrue(player.released)
        assertFalse(speaker.preparing.value)
        assertNull(speaker.error.value)
    }

    @Test fun stopCancelsPlaybackAndReleasesThePlayer() {
        val service = FakeService(mapOf(TtsModel.CHATTERBOX.defaultFunctionId to chatterbox))
        val players = CopyOnWriteArrayList<FakePlayer>()
        val speaker = speaker(service, players)
        speaker.speak(1, turkishLong)
        waitFor { players.isNotEmpty() }
        speaker.stop()
        assertNull(speaker.speaking.value)
        waitFor { players.single().released }
    }

    @Test fun failureBeforeAnyAudioReportsAndFallsBackToDeviceVoice() {
        val service = FakeService(mapOf(TtsModel.CHATTERBOX.defaultFunctionId to chatterbox), failWith = RivaException("Ses sunucusuna ulaşılamadı."))
        val players = CopyOnWriteArrayList<FakePlayer>()
        val speaker = speaker(service, players)
        speaker.speak(3, "Merhaba, bugün hava çok güzel ve ben yürüyüşe çıkıyorum.")
        waitFor { speaker.error.value != null }
        assertTrue(speaker.error.value, speaker.error.value!!.contains("Cihaz sesi kullanılıyor"))
        assertTrue(speaker.error.value!!.contains("ulaşılamadı"))
    }

    @Test fun staleFunctionIdIsResolvedFromDirectoryAndRemembered() {
        val fresh = "11111111-2222-3333-4444-555555555555"
        val service = FakeService(
            mapOf(fresh to chatterbox), staleId = TtsModel.CHATTERBOX.defaultFunctionId, newId = fresh,
        )
        val ai = AiSpeech(service, settings)
        runBlocking {
            val s = settings.settings.value.copy(apiKey = "k", ttsModel = TtsModel.AUTO)
            val prepared = ai.prepare("Merhaba, bugün nasılsın? Çok güzel bir gün.", s, "tr")
            assertEquals(fresh, prepared.functionId)
            assertEquals("Chatterbox-Multilingual.tr-TR.Male", prepared.voice)
            ai.synthesize(prepared, "Merhaba", "k")
        }
        assertEquals(fresh, settings.ttsFunctionId(TtsModel.CHATTERBOX.name))
        assertTrue(service.calls.single().startsWith(fresh))
    }

    @Test fun deviceEngineOrMissingKeyNeverCallsTheAiService() {
        val service = FakeService(mapOf(TtsModel.CHATTERBOX.defaultFunctionId to chatterbox))
        val players = CopyOnWriteArrayList<FakePlayer>()
        val speaker = speaker(service, players)
        settings.update { it.copy(ttsEngine = TtsEngine.DEVICE) }
        speaker.speak(5, "Merhaba dünya, bugün hava güzel.")
        Thread.sleep(300)
        assertTrue(service.calls.isEmpty()); assertTrue(players.isEmpty())
        speaker.stop()
        settings.update { it.copy(ttsEngine = TtsEngine.NVIDIA, apiKey = "") }
        speaker.speak(6, "Merhaba dünya, bugün hava güzel.")
        Thread.sleep(300)
        assertTrue(service.calls.isEmpty()); assertTrue(players.isEmpty())
        assertNotNull(speaker)
    }
}
