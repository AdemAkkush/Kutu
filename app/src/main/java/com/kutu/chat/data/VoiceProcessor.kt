package com.kutu.chat.data

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Ses hızını ve tonunu birbirinden bağımsız değiştirir (16 bit mono PCM, akışlı).
 *  - [speed] 1.0 = normal, 1.5 = %50 daha hızlı; ses tonu değişmez.
 *  - [pitch] 1.0 = normal, 1.2 = daha ince; konuşma hızı değişmez.
 * Hız için WSOLA (örtüşmeli ekleme + en iyi hizalama), ton için yeniden örnekleme + hız telafisi kullanılır.
 * İkisi de varsayılan değerdeyse veri olduğu gibi geçer.
 */
class VoiceProcessor(sampleRate: Int, speed: Float, pitch: Float) {
    private val resampler = if (abs(pitch - 1f) > 0.005f) LinearResampler(pitch.toDouble()) else null
    private val tempo = speed.toDouble() / pitch.toDouble()
    private val wsola = if (abs(tempo - 1.0) > 0.005) Wsola(sampleRate, tempo) else null
    val isIdentity get() = resampler == null && wsola == null

    fun process(pcm: ByteArray): ByteArray {
        if (isIdentity) return pcm
        var x = toFloats(pcm)
        resampler?.let { x = it.process(x) }
        wsola?.let { x = it.process(x) }
        return toBytes(x)
    }

    /** Akış bittiğinde içeride kalan sesi verir. */
    fun finish(): ByteArray {
        if (isIdentity) return ByteArray(0)
        var x = FloatArray(0)
        resampler?.let { x = it.process(FloatArray(0)) }
        wsola?.let { x = it.process(x) + it.finish() }
        return toBytes(x)
    }

    companion object {
        const val MIN_SPEED = 0.5f
        const val MAX_SPEED = 2.0f
        const val MIN_PITCH = 0.7f
        const val MAX_PITCH = 1.4f

        fun toFloats(pcm: ByteArray): FloatArray = FloatArray(pcm.size / 2) { i ->
            ((pcm[2 * i + 1].toInt() shl 8) or (pcm[2 * i].toInt() and 0xFF)).toShort() / 32768f
        }

        fun toBytes(x: FloatArray): ByteArray {
            val out = ByteArray(x.size * 2)
            for (i in x.indices) {
                val v = (x[i] * 32767f).toInt().coerceIn(-32768, 32767)
                out[2 * i] = v.toByte(); out[2 * i + 1] = (v shr 8).toByte()
            }
            return out
        }
    }
}

/** Akışlı doğrusal yeniden örnekleme: [ratio] kadar hızlı okur (ton ve hız birlikte ratio kat değişir). */
internal class LinearResampler(private val ratio: Double) {
    private var carry = FloatArray(0)
    private var pos = 0.0

    fun process(input: FloatArray): FloatArray {
        val x = if (carry.isEmpty()) input else carry + input
        val out = ArrayList<Float>((x.size / ratio).toInt() + 2)
        while (pos + 1 < x.size) {
            val i = floor(pos).toInt()
            val f = (pos - i).toFloat()
            out.add(x[i] * (1 - f) + x[i + 1] * f)
            pos += ratio
        }
        val keep = min(x.size, floor(pos).toInt())
        carry = x.copyOfRange(keep, x.size)
        pos -= keep
        return out.toFloatArray()
    }
}

/**
 * Akışlı WSOLA zaman ölçekleme. [tempo] > 1 hızlandırır, < 1 yavaşlatır; ses tonu korunur.
 * Çerçeve 32 ms, %50 örtüşme (Hann), çerçeve başlangıcı ±8 ms içinde bir önceki çerçevenin
 * doğal devamıyla en iyi uyuşan yere hizalanır.
 */
internal class Wsola(sampleRate: Int, private val tempo: Double) {
    private val n = ((sampleRate * 0.032).toInt()) and 1.inv()
    private val hop = n / 2
    private val delta = (sampleRate * 0.008).toInt()
    private val window = FloatArray(n) { (0.5 - 0.5 * cos(2 * PI * it / n)).toFloat() }

    private var buf = FloatArray(0)
    private var base = 0L              // buf[0]'ın girişteki mutlak indisi
    private var inputEnd = 0L          // şimdiye dek gelen toplam örnek
    private var frame = 0L
    private var prev = 0L              // önceki çerçevenin seçilen başlangıcı (mutlak)
    private val acc = FloatArray(n)    // çıkışta [frame*hop, frame*hop+n) aralığının birikimi
    private var finished = false

    fun process(input: FloatArray): FloatArray {
        if (input.isNotEmpty()) {
            buf += input
            inputEnd += input.size
        }
        return drain()
    }

    fun finish(): FloatArray {
        if (finished || inputEnd == 0L) return FloatArray(0)
        finished = true
        buf += FloatArray(n + 2 * delta + hop)          // sondaki çerçeveleri tamamlamak için sessizlik
        val out = drain(untilInputEnd = true).toMutableList()
        for (i in 0 until hop) out.add(acc[i])           // son çerçevenin kuyruğu
        return out.toFloatArray()
    }

    private fun at(abs: Long): Float {
        val i = (abs - base).toInt()
        return if (i in buf.indices) buf[i] else 0f
    }

    private fun drain(untilInputEnd: Boolean = false): FloatArray {
        val out = ArrayList<Float>()
        while (true) {
            val ideal = (frame * hop * tempo).toLong()
            if (untilInputEnd && ideal >= inputEnd) break
            val start: Long
            if (frame == 0L) {
                if (base + buf.size < n) break
                start = 0
            } else {
                if (base + buf.size < ideal + delta + n) break      // yeterli giriş yok, beklenir
                val natural = prev + hop
                var best = Long.MIN_VALUE; var bestScore = -Double.MAX_VALUE
                val lo = max(ideal - delta, 0L); val hi = ideal + delta
                var c = lo
                while (c <= hi) {
                    var dot = 0.0; var energy = 1e-9
                    var i = 0
                    while (i < hop) {                               // 2 örnekte bir: hız için seyreltilmiş ilinti
                        val a = at(c + i).toDouble(); val b = at(natural + i).toDouble()
                        dot += a * b; energy += a * a
                        i += 2
                    }
                    val score = dot / sqrt(energy)
                    if (score > bestScore) { bestScore = score; best = c }
                    c += 1
                }
                start = best
            }
            for (i in 0 until n) acc[i] += window[i] * at(start + i)
            for (i in 0 until hop) out.add(acc[i])
            System.arraycopy(acc, hop, acc, 0, n - hop)
            java.util.Arrays.fill(acc, n - hop, n, 0f)
            prev = start
            frame++
            trim(max(0L, min(prev, (frame * hop * tempo).toLong() - delta)))
        }
        return out.toFloatArray()
    }

    private fun trim(keepFrom: Long) {
        val drop = (keepFrom - base).toInt()
        if (drop > 4096) {
            buf = buf.copyOfRange(drop, buf.size)
            base += drop
        }
    }
}
