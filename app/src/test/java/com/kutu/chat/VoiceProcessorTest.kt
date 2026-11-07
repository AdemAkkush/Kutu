package com.kutu.chat

import com.kutu.chat.data.VoiceProcessor
import java.io.File
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class VoiceProcessorTest {
    private val rate = 24000

    /** Sesli konuşmaya benzer: temel frekans + harmonikler, hafif genlik dalgalanması. */
    private fun voiced(seconds: Double, f0: Double = 200.0): ByteArray {
        val n = (seconds * rate).toInt()
        val x = FloatArray(n) { i ->
            val t = i.toDouble() / rate
            val env = 0.6 + 0.4 * sin(2 * PI * 3 * t)
            (env * (0.5 * sin(2 * PI * f0 * t) + 0.25 * sin(2 * PI * 2 * f0 * t) + 0.15 * sin(2 * PI * 3 * f0 * t))).toFloat() * 0.8f
        }
        return VoiceProcessor.toBytes(x)
    }

    private fun run(pcm: ByteArray, speed: Float, pitch: Float, pieces: Int = 1): ByteArray {
        val vp = VoiceProcessor(rate, speed, pitch)
        val out = java.io.ByteArrayOutputStream()
        val size = (pcm.size / pieces / 2) * 2
        var i = 0
        while (i < pcm.size) { val e = minOf(pcm.size, i + size); out.write(vp.process(pcm.copyOfRange(i, e))); i = e }
        out.write(vp.finish())
        return out.toByteArray()
    }

    /** Sıfır geçişlerinden temel frekans tahmini. */
    private fun frequency(pcm: ByteArray): Double {
        val x = VoiceProcessor.toFloats(pcm)
        val from = x.size / 5; val to = x.size * 4 / 5
        var crossings = 0
        for (i in from + 1 until to) if (x[i - 1] < 0 && x[i] >= 0) crossings++
        return crossings / ((to - from).toDouble() / rate)
    }

    private fun seconds(pcm: ByteArray) = pcm.size / 2.0 / rate

    @Test fun defaultSettingsPassAudioThroughUntouched() {
        val pcm = voiced(1.0)
        assertArrayEquals(pcm, run(pcm, 1f, 1f))
        assertTrue(VoiceProcessor(rate, 1f, 1f).isIdentity)
    }

    @Test fun fasterSpeedShortensAudioButKeepsPitch() {
        val pcm = voiced(4.0)
        for (speed in listOf(1.25f, 1.5f, 2.0f)) {
            val out = run(pcm, speed, 1f)
            assertEquals("süre x$speed", 4.0 / speed, seconds(out), 4.0 / speed * 0.04)
            assertEquals("ton x$speed", 200.0, frequency(out), 8.0)
        }
    }

    @Test fun slowerSpeedLengthensAudioButKeepsPitch() {
        val pcm = voiced(4.0)
        for (speed in listOf(0.5f, 0.75f)) {
            val out = run(pcm, speed, 1f)
            assertEquals("süre x$speed", 4.0 / speed, seconds(out), 4.0 / speed * 0.04)
            assertEquals("ton x$speed", 200.0, frequency(out), 8.0)
        }
    }

    @Test fun pitchChangesFrequencyButKeepsDuration() {
        val pcm = voiced(4.0)
        for (pitch in listOf(0.8f, 1.2f, 1.4f)) {
            val out = run(pcm, 1f, pitch)
            assertEquals("süre pitch=$pitch", 4.0, seconds(out), 0.2)
            assertEquals("frekans pitch=$pitch", 200.0 * pitch, frequency(out), 200.0 * pitch * 0.05)
        }
    }

    @Test fun speedAndPitchCombine() {
        val out = run(voiced(4.0), 1.5f, 1.2f)
        assertEquals(4.0 / 1.5, seconds(out), 0.15)
        assertEquals(240.0, frequency(out), 12.0)
    }

    @Test fun chunkedStreamingMatchesWholeBufferProcessing() {
        val pcm = voiced(4.0)
        val whole = run(pcm, 1.4f, 1.1f, pieces = 1)
        val streamed = run(pcm, 1.4f, 1.1f, pieces = 37)
        assertEquals(seconds(whole), seconds(streamed), 0.05)
        assertEquals(frequency(whole), frequency(streamed), 8.0)
        // Parça sınırlarında tıkırtı olmamalı: ardışık örnekler arasındaki en büyük sıçrama normal sinyalinkinden belirgin büyük değil.
        fun maxJump(b: ByteArray) = VoiceProcessor.toFloats(b).let { x -> (1 until x.size).maxOf { abs(x[it] - x[it - 1]) } }
        assertTrue("sıçrama ${maxJump(streamed)} / ${maxJump(whole)}", maxJump(streamed) < maxJump(whole) * 1.5f + 0.02f)
    }

    @Test fun tinyAndEmptyInputsAreSafe() {
        assertEquals(0, run(ByteArray(0), 1.5f, 1.2f).size)
        val short = run(voiced(0.02), 1.5f, 1f)
        assertTrue(short.size % 2 == 0)
    }

    @Test fun loudnessIsPreserved() {
        fun rms(b: ByteArray) = VoiceProcessor.toFloats(b).let { x -> kotlin.math.sqrt(x.sumOf { (it * it).toDouble() } / x.size) }
        val pcm = voiced(4.0)
        val out = run(pcm, 1.5f, 1f)
        assertEquals(rms(pcm), rms(out), rms(pcm) * 0.12)
    }

    /** Gerçek TTS kaydı üzerinde çalıştırıp çıktıyı dosyaya yazar (yalnızca VP_IN/VP_OUT tanımlıysa; Whisper ile dinlemeden doğrulamak için). */
    @Test fun realRecordingRoundTrip() {
        val input = System.getenv("VP_IN") ?: return
        val outDir = System.getenv("VP_OUT") ?: return
        val wav = File(input).readBytes()
        val pcm = wav.copyOfRange(44, wav.size)
        for ((name, speed, pitch) in listOf(Triple("fast15", 1.5f, 1f), Triple("slow075", 0.75f, 1f), Triple("high12", 1f, 1.2f), Triple("low085", 1f, 0.85f))) {
            val out = run(pcm, speed, pitch, pieces = 25)
            val header = wav.copyOfRange(0, 44)
            val size = out.size
            fun le(v: Int, at: Int) { for (i in 0..3) header[at + i] = (v shr (8 * i)).toByte() }
            le(36 + size, 4); le(size, 40)
            File(outDir, "$name.wav").writeBytes(header + out)
        }
    }
}
