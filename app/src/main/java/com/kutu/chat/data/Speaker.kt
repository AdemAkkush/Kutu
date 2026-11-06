package com.kutu.chat.data

import android.content.Context
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import java.util.Locale
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Android'in metin okuma motoruyla yanıtları sesli okur. İlk kullanımda başlatılır. */
class Speaker(private val context: Context) {
    private var tts: TextToSpeech? = null
    private var ready = false
    private var pending: (() -> Unit)? = null

    private val _speaking = MutableStateFlow<Long?>(null)
    /** Şu anda okunan mesajın id'si. */
    val speaking: StateFlow<Long?> = _speaking.asStateFlow()

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
        _speaking.value = messageId
        val start = {
            val engine = tts
            if (engine != null) {
                applyLanguage(engine, text)
                val chunks = chunk(text, minOf(TextToSpeech.getMaxSpeechInputLength(), MAX_CHUNK))
                chunks.forEachIndexed { i, part ->
                    val id = "$messageId:$i:${chunks.size}"
                    engine.speak(part, TextToSpeech.QUEUE_ADD, Bundle(), id)
                }
            }
        }
        if (ready) start() else {
            pending = start
            if (tts == null) init()
        }
    }

    fun stop() {
        pending = null
        tts?.stop()
        _speaking.value = null
    }

    fun shutdown() {
        tts?.shutdown()
        tts = null
        ready = false
    }

    private fun init() {
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
            } else {
                _speaking.value = null
                _error.value = "Cihazda metin okuma motoru bulunamadı."
            }
        }
    }

    private fun applyLanguage(engine: TextToSpeech, text: String) {
        val locale = if (looksTurkish(text)) Locale("tr", "TR") else Locale.getDefault()
        val result = engine.setLanguage(locale)
        if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) {
            engine.setLanguage(Locale.US)
            _error.value = "“${locale.displayLanguage}” ses verisi yüklü değil; Ayarlar > Metin okuma bölümünden indirin."
        }
    }

    companion object {
        private const val MAX_CHUNK = 3500
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
