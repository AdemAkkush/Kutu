package com.kutu.chat.data

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext

/** Ham 16 bit mono PCM çalar. */
interface PcmPlayer {
    suspend fun write(pcm: ByteArray)
    /** Yazılan her şey çalınana kadar bekler. */
    suspend fun drain()
    fun release()
}

class AudioTrackPlayer(private val sampleRate: Int) : PcmPlayer {
    private val track: AudioTrack
    private var frames = 0L
    private var started = false
    @Volatile private var released = false

    init {
        val min = AudioTrack.getMinBufferSize(sampleRate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT)
        track = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build(),
            )
            .setAudioFormat(
                AudioFormat.Builder().setSampleRate(sampleRate).setEncoding(AudioFormat.ENCODING_PCM_16BIT).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build(),
            )
            .setBufferSizeInBytes(maxOf(min, sampleRate * 2))
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()
    }

    override suspend fun write(pcm: ByteArray) {
        if (released) return
        if (!started) { track.play(); started = true }
        withContext(Dispatchers.IO) {
            var offset = 0
            while (offset < pcm.size && !released) {
                val n = track.write(pcm, offset, pcm.size - offset)
                if (n <= 0) break
                offset += n
            }
        }
        frames += pcm.size / 2
    }

    override suspend fun drain() {
        while (!released && currentCoroutineContext().isActive && track.playbackHeadPosition < frames) delay(60)
    }

    override fun release() {
        if (released) return
        released = true
        runCatching { track.pause(); track.flush() }
        runCatching { track.release() }
    }
}
