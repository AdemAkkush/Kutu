package com.kutu.chat

import com.kutu.chat.data.api.Proto
import com.kutu.chat.data.api.RivaException
import com.kutu.chat.data.api.RivaTts
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Assume.assumeTrue
import org.junit.Test

class RivaTtsTest {
    @Test fun protobufEncodingMatchesTheRivaSchema() {
        // Python'daki resmî istemciyle üretilen SynthesizeSpeechRequest ile aynı baytlar:
        // text="Merhaba" lang="tr-TR" encoding=1 rate=24000 voice="V"
        val bytes = Proto.string(1, "Merhaba") + Proto.string(2, "tr-TR") + Proto.varint(3, 1) + Proto.varint(4, 24000) + Proto.string(5, "V")
        val expected = byteArrayOf(0x0A, 7) + "Merhaba".toByteArray() + byteArrayOf(0x12, 5) + "tr-TR".toByteArray() +
            byteArrayOf(0x18, 1, 0x20, 0xC0.toByte(), 0xBB.toByte(), 0x01, 0x2A, 1, 'V'.code.toByte())
        assertEquals(expected.toList(), bytes.toList())
        val parsed = Proto.fields(bytes)
        assertEquals(listOf(1, 2, 3, 4, 5), parsed.map { it.number })
        assertEquals(24000L, parsed[3].value)
        assertEquals("tr-TR", parsed[1].bytes!!.toString(Charsets.UTF_8))
    }

    @Test fun grpcFramesAreJoinedAndValidated() {
        val a = byteArrayOf(1, 2, 3); val b = byteArrayOf(4, 5)
        fun frame(p: ByteArray) = byteArrayOf(0, 0, 0, 0, p.size.toByte()) + p
        assertEquals(listOf<Byte>(1, 2, 3, 4, 5), RivaTts.unframe(frame(a) + frame(b)).toList())
        try { RivaTts.unframe(byteArrayOf(1, 0, 0, 0, 1, 9)); fail() } catch (e: RivaException) { assertTrue(e.message!!.contains("sıkıştırılmış")) }
        try { RivaTts.unframe(byteArrayOf(0, 0, 0, 0, 9, 1)); fail() } catch (e: RivaException) { assertTrue(e.message!!.contains("Bozuk")) }
    }

    @Test fun configResponseIsParsed() {
        fun entry(k: String, v: String) = Proto.bytes(2, Proto.string(1, k) + Proto.string(2, v))
        val config = Proto.bytes(1, Proto.string(1, "model") + entry("sample_rate", "24000") + entry("voice_name", "Chatterbox-Multilingual") +
            entry("language_code", "tr-TR,en-US") + entry("subvoices", "tr-TR.Male:21|0,en-US.Male:4|0"))
        val v = RivaTts.parseConfig(config)
        assertEquals(24000, v.sampleRate); assertEquals(listOf("tr-TR", "en-US"), v.languages)
        assertEquals(listOf("tr-TR.Male", "en-US.Male"), v.subvoices); assertEquals("Chatterbox-Multilingual", v.voiceName)
    }

    /** Gerçek NVIDIA servisine karşı çalışır; yalnızca NVKEY ortam değişkeni tanımlıysa (CI'da atlanır). */
    @Test fun realServiceSynthesizesTurkishAndEnglish() = runBlocking {
        val key = System.getenv("NVKEY")
        assumeTrue("NVKEY tanımlı değil, gerçek servis testi atlandı", !key.isNullOrBlank())
        val riva = RivaTts()
        val chatterbox = "ddacc747-1269-4fab-bfd9-8f593dead106"
        val voices = riva.voices(key!!, chatterbox)
        println("LIVE chatterbox rate=${voices.sampleRate} langs=${voices.languages.size} tr=${voices.subvoices.filter { it.startsWith("tr-TR") }}")
        assertTrue("tr-TR" in voices.languages)

        val pcm = riva.synthesize(key, chatterbox, "Merhaba, ben Kutu. Bugün sana yardımcı olmak için buradayım.", "tr-TR", "Chatterbox-Multilingual.tr-TR.Male", voices.sampleRate)
        val peak = (0 until pcm.size / 2).maxOf { i -> kotlin.math.abs(((pcm[2 * i + 1].toInt() shl 8) or (pcm[2 * i].toInt() and 0xFF)).toShort().toInt()) }
        println("LIVE tr bytes=${pcm.size} seconds=${pcm.size / 2.0 / voices.sampleRate} peak=$peak")
        assertTrue(pcm.size > 20_000); assertTrue("ses sessiz olmamalı", peak > 1000)

        val magpie = "877104f7-e885-42b9-8de8-f6e4c6303969"
        val mv = riva.voices(key, magpie)
        println("LIVE magpie rate=${mv.sampleRate} langs=${mv.languages}")
        val en = riva.synthesize(key, magpie, "Hello, I am Kutu.", "en-US", "Magpie-Multilingual.EN-US.Aria", mv.sampleRate)
        assertTrue(en.size > 10_000)

        // Akışlı sentez: ilk ses hızlı gelmeli, birden çok parça olmalı, WAV başlığı olmamalı.
        val tr470 = ("Bugün hava çok güzel olduğu için arkadaşlarımla birlikte parka gittik ve orada uzun bir yürüyüş yaptık. ".repeat(5)).take(470).substringBeforeLast(" ")
        val t0 = System.nanoTime()
        var firstMs = -1L
        val pieces = ArrayList<ByteArray>()
        riva.synthesizeStream(key, chatterbox, tr470, "tr-TR", "Chatterbox-Multilingual.tr-TR.Male", voices.sampleRate).collect {
            if (firstMs < 0) firstMs = (System.nanoTime() - t0) / 1_000_000
            pieces += it
        }
        val total = pieces.sumOf { it.size }
        println("LIVE stream chatterbox ${tr470.length} karakter: ilk ses ${firstMs} ms, ${pieces.size} parça, ${total / 2.0 / voices.sampleRate} sn ses")
        assertTrue("akış birden çok parça vermeli", pieces.size > 1)
        assertTrue("ilk ses 6 sn içinde gelmeli ($firstMs ms)", firstMs in 0..6000)
        assertTrue("PCM olmalı, WAV başlığı değil", String(pieces.first().copyOf(4)) != "RIFF")
        assertTrue(total > 100_000)

        // Sunucu sınırı aşılırsa anlaşılır bir hata gelmeli (uygulama bunu parçalayarak hiç yaşamaz).
        try {
            riva.synthesizeStream(key, chatterbox, "a ".repeat(300), "tr-TR", "Chatterbox-Multilingual.tr-TR.Male", voices.sampleRate).collect { }
            fail("500 karakter aşımı hata vermeli")
        } catch (e: RivaException) {
            println("LIVE too long -> ${e.message}")
            assertTrue(e.message, e.message!!.contains("sınırını aşıyor"))
        }

        // Magpie: pratikte sınırsız; 2000 karakter tek istekte.
        val longEn = ("Today the weather was so nice that my friends and I went to the park together and took a long walk there. ".repeat(20)).take(2000)
        var mpieces = 0; var mbytes = 0
        riva.synthesizeStream(key, magpie, longEn, "en-US", "Magpie-Multilingual.EN-US.Aria", mv.sampleRate).collect { mpieces++; mbytes += it.size }
        println("LIVE stream magpie ${longEn.length} karakter: $mpieces parça, ${mbytes / 2.0 / mv.sampleRate} sn ses")
        assertTrue(mbytes / 2.0 / mv.sampleRate > 60)

        try {
            riva.synthesize("", chatterbox, "x", "tr-TR", "Chatterbox-Multilingual.tr-TR.Male", 24000); fail("hata bekleniyordu")
        } catch (e: RivaException) {
            assertTrue(e.message, e.message!!.contains("API anahtarı"))
        }
    }
}
