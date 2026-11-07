package com.kutu.chat.data

import android.content.Context
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import com.kutu.chat.domain.TtsEngine
import com.kutu.chat.domain.TtsPlanner
import java.util.Locale
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** Cihazın metin okuma motorundaki bir ses. */
data class DeviceVoice(val name: String, val language: String, val network: Boolean, val quality: Int)

/**
 * Yanıtları sesli okur. İki motor vardır:
 *  - NVIDIA yapay zekâ sesi (Chatterbox / Magpie): parça parça sentezlenir, bir parça çalarken sonraki üretilir.
 *  - Cihazın kendi metin okuma motoru: yedek olarak, ya da kullanıcı seçtiyse.
 * Yapay zekâ sesi ilk sesten önce başarısız olursa otomatik olarak cihaz sesine düşer.
 */
class Speaker(
    private val context: Context,
    private val settingsStore: SettingsStore,
    private val scope: CoroutineScope,
    private val ai: AiSpeech,
    private val playerFactory: (Int) -> PcmPlayer = { AudioTrackPlayer(it) },
) {
    private var tts: TextToSpeech? = null
    private var ready = false
    private var pending: (() -> Unit)? = null
    private var pendingLoad: (() -> Unit)? = null
    @Volatile private var voiceOverride: String? = null

    /** Cihazın metin okuma motorunda yüklü sesler. [loadDeviceVoices] ile doldurulur. */
    private val _deviceVoices = MutableStateFlow<List<DeviceVoice>>(emptyList())
    val deviceVoices: StateFlow<List<DeviceVoice>> = _deviceVoices.asStateFlow()

    private var aiJob: Job? = null
    @Volatile private var activePlayer: PcmPlayer? = null

    private val _speaking = MutableStateFlow<Long?>(null)
    /** Şu anda okunan (ya da hazırlanan) mesajın id'si. */
    val speaking: StateFlow<Long?> = _speaking.asStateFlow()

    private val _preparing = MutableStateFlow(false)
    /** Yapay zekâ sesi henüz ilk ses parçasını üretiyor. */
    val preparing: StateFlow<Boolean> = _preparing.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    fun clearError() { _error.value = null }

    fun toggle(messageId: Long, markdown: String) {
        if (_speaking.value == messageId) stop() else speak(messageId, markdown)
    }

    fun speak(messageId: Long, markdown: String) {
        val text = plainText(markdown)
        if (text.isBlank()) return
        stop()
        val s = settingsStore.settings.value
        val turkishOnDevice = s.ttsTurkishOnDevice && TtsPlanner.detectLanguage(text, Locale.getDefault().language) == "tr"
        if (s.ttsEngine == TtsEngine.NVIDIA && s.apiKey.isNotBlank() && !turkishOnDevice) speakAi(messageId, text, s)
        else speakDevice(messageId, text)
    }

    /** Ayarlardaki örnek için: belirli bir cihaz sesini dinletir. */
    fun previewDeviceVoice(voiceName: String, messageId: Long, text: String) {
        stop()
        voiceOverride = voiceName
        speakDevice(messageId, text)
    }

    fun loadDeviceVoices() {
        val load = {
            _deviceVoices.value = tts?.voices.orEmpty().map {
                DeviceVoice(it.name, it.locale.toLanguageTag(), it.isNetworkConnectionRequired, it.quality)
            }.sortedWith(compareBy<DeviceVoice> { !it.language.startsWith("tr") }.thenBy { it.language }.thenByDescending { it.quality }.thenBy { it.name })
        }
        if (ready) load() else {
            pendingLoad = load
            if (tts == null) initDevice()
        }
    }

    fun stop() {
        aiJob?.cancel()
        aiJob = null
        activePlayer?.release()
        activePlayer = null
        pending = null
        voiceOverride = null
        tts?.stop()
        _preparing.value = false
        _speaking.value = null
    }

    fun shutdown() {
        stop()
        tts?.shutdown()
        tts = null
        ready = false
    }

    // ---- Yapay zekâ sesi ----

    private fun speakAi(messageId: Long, text: String, s: AppSettings) {
        _speaking.value = messageId
        _preparing.value = true
        aiJob = scope.launch {
            var started = false
            var fellBack = false
            try {
                val prepared = ai.prepare(text, s, Locale.getDefault().language)
                val chunks = chunk(text, prepared.maxChars)
                val player = playerFactory(prepared.sampleRate).also { activePlayer = it }
                val voice = VoiceProcessor(prepared.sampleRate, s.ttsSpeed, s.ttsPitch)
                coroutineScope {
                    // Üretici, oynatıcıdan önde gider: bir parça çalarken sonrakiler üretilir.
                    val audio = Channel<ByteArray>(PREFETCH_PIECES)
                    launch {
                        try {
                            for (c in chunks) ai.synthesizeStream(prepared, c, s.apiKey).collect { audio.send(it) }
                            audio.close()
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Throwable) {
                            audio.close(e)
                        }
                    }
                    for (pcm in audio) {
                        if (!started) { started = true; _preparing.value = false }
                        player.write(voice.process(pcm))
                    }
                    voice.finish().takeIf { it.isNotEmpty() }?.let { player.write(it) }
                    player.drain()
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                val reason = e.message ?: e.javaClass.simpleName
                if (!started) {
                    _error.value = "Yapay zekâ sesi kullanılamadı ($reason). Cihaz sesi kullanılıyor."
                    fellBack = true
                    activePlayer?.release(); activePlayer = null
                    speakDevice(messageId, text)
                } else {
                    _error.value = "Seslendirme yarıda kesildi: $reason"
                }
            } finally {
                activePlayer?.release()
                activePlayer = null
                if (!fellBack) {
                    _preparing.value = false
                    if (_speaking.value == messageId) _speaking.value = null
                }
            }
        }
    }

    // ---- Cihaz sesi ----

    private fun speakDevice(messageId: Long, text: String) {
        _speaking.value = messageId
        val start = {
            val engine = tts
            if (engine != null) {
                val s = settingsStore.settings.value
                val locale = applyLanguage(engine, text)
                engine.setSpeechRate(s.ttsSpeed.coerceIn(VoiceProcessor.MIN_SPEED, VoiceProcessor.MAX_SPEED))
                engine.setPitch(s.ttsPitch.coerceIn(VoiceProcessor.MIN_PITCH, VoiceProcessor.MAX_PITCH))
                val wanted = voiceOverride ?: s.ttsDeviceVoice
                if (wanted.isNotBlank()) {
                    engine.voices?.firstOrNull { it.name == wanted && it.locale.language == locale.language }?.let { engine.voice = it }
                }
                val chunks = chunk(text, minOf(TextToSpeech.getMaxSpeechInputLength(), MAX_CHUNK))
                chunks.forEachIndexed { i, part ->
                    engine.speak(part, TextToSpeech.QUEUE_ADD, Bundle(), "$messageId:$i:${chunks.size}")
                }
            }
        }
        if (ready) start() else {
            pending = start
            if (tts == null) initDevice()
        }
    }

    private fun initDevice() {
        tts = TextToSpeech(context.applicationContext) { status ->
            if (status == TextToSpeech.SUCCESS) {
                ready = true
                tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                    override fun onStart(utteranceId: String?) {}
                    override fun onDone(utteranceId: String?) {
                        val parts = utteranceId?.split(':') ?: return
                        if (parts.size == 3 && parts[1].toIntOrNull() == (parts[2].toIntOrNull() ?: 0) - 1) {
                            if (_speaking.value == parts[0].toLongOrNull()) _speaking.value = null
                        }
                    }
                    @Deprecated("Deprecated in Java")
                    override fun onError(utteranceId: String?) { _speaking.value = null }
                })
                pending?.invoke()
                pending = null
                pendingLoad?.invoke()
                pendingLoad = null
            } else {
                _speaking.value = null
                _error.value = "Cihazda metin okuma motoru bulunamadı."
            }
        }
    }

    private fun applyLanguage(engine: TextToSpeech, text: String): Locale {
        val locale = if (looksTurkish(text)) Locale("tr", "TR") else Locale.getDefault()
        val result = engine.setLanguage(locale)
        if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) {
            engine.setLanguage(Locale.US)
            _error.value = "“${locale.displayLanguage}” ses verisi yüklü değil; Ayarlar > Metin okuma bölümünden indirin."
            return Locale.US
        }
        return locale
    }

    companion object {
        private const val MAX_CHUNK = 3500
        /** Üretilip henüz çalınmamış en fazla ses parçası sayısı (bellek sınırı). */
        const val PREFETCH_PIECES = 256
        private val TURKISH_CHARS = Regex("[çğıöşüÇĞİÖŞÜ]")

        fun looksTurkish(text: String) = TURKISH_CHARS.findAll(text).count() >= 2

        /** Markdown işaretlerini ve kod bloklarını okunabilir düz metne çevirir. */
        fun plainText(markdown: String): String {
            var s = markdown
            s = Regex("```[\\s\\S]*?(```|$)").replace(s, " (kod bloğu) ")
            s = Regex("!?\\[([^\\]]*)]\\([^)]*\\)").replace(s, "$1")
            s = Regex("https?://\\S+").replace(s, " bağlantı ")
            s = Regex("(?m)^\\s{0,3}#{1,6}\\s*").replace(s, "")
            s = Regex("(?m)^\\s*[-*+]\\s+").replace(s, "")
            s = Regex("(?m)^\\s*>\\s?").replace(s, "")
            s = Regex("(?m)^\\|?\\s*:?-{2,}[-|:\\s]*$").replace(s, "")
            s = s.replace("|", ", ")
            s = Regex("[*_~`]+").replace(s, "")
            return Regex("[ \\t]+").replace(Regex("\\n{3,}").replace(s, "\n\n"), " ").trim()
        }

        /** Metni cümle/paragraf sınırlarında en fazla [max] karakterlik parçalara böler. */
        fun chunk(text: String, max: Int): List<String> {
            if (text.length <= max) return listOf(text)
            val out = ArrayList<String>()
            var rest = text
            while (rest.length > max) {
                val window = rest.substring(0, max)
                var cut = maxOf(window.lastIndexOf("\n\n"), window.lastIndexOf(". "), window.lastIndexOf("! "), window.lastIndexOf("? "), window.lastIndexOf("\n"))
                if (cut < max / 2) cut = window.lastIndexOf(' ')
                if (cut <= 0) cut = max - 1
                out.add(rest.substring(0, cut + 1).trim())
                rest = rest.substring(cut + 1).trim()
            }
            if (rest.isNotBlank()) out.add(rest)
            return out
        }
    }
}
