package com.kutu.chat.data

import com.kutu.chat.data.api.RivaException
import com.kutu.chat.data.api.RivaTts
import com.kutu.chat.data.api.RivaVoices
import com.kutu.chat.domain.TtsModel
import com.kutu.chat.domain.TtsPlanner
import java.util.concurrent.ConcurrentHashMap

/** Riva TTS çağrılarını soyutlar (testlerde sahtesi kullanılır). */
interface TtsService {
    suspend fun voices(apiKey: String, functionId: String): RivaVoices
    suspend fun synthesize(apiKey: String, functionId: String, text: String, languageCode: String, voiceName: String, sampleRate: Int): ByteArray
    suspend fun findFunctionId(apiKey: String, functionName: String): String?
}

class RivaTtsService(private val riva: RivaTts = RivaTts()) : TtsService {
    override suspend fun voices(apiKey: String, functionId: String) = riva.voices(apiKey, functionId)
    override suspend fun synthesize(apiKey: String, functionId: String, text: String, languageCode: String, voiceName: String, sampleRate: Int) =
        riva.synthesize(apiKey, functionId, text, languageCode, voiceName, sampleRate)
    override suspend fun findFunctionId(apiKey: String, functionName: String) = riva.findFunctionId(apiKey, functionName)
}

/** Bir metin için seçilmiş model, dil, ses ve örnekleme hızı. */
data class PreparedSpeech(
    val model: TtsModel,
    val functionId: String,
    val language: String,
    val voice: String,
    val sampleRate: Int,
)

/** Metin → (model, dil, ses) kararı ve parça parça sentez. Bir model kimliği değişmişse dizinden yeniden çözer. */
class AiSpeech(
    private val service: TtsService,
    private val settings: SettingsStore,
) {
    private val voiceCache = ConcurrentHashMap<String, RivaVoices>()
    private val functionIds = ConcurrentHashMap<TtsModel, String>()

    suspend fun prepare(text: String, s: AppSettings, deviceLanguage: String): PreparedSpeech {
        val plan = TtsPlanner.plan(text, deviceLanguage, s.ttsModel)
            ?: throw RivaException("Bu dil NVIDIA ses modelleri tarafından desteklenmiyor.")
        val voices = withFunction(plan.model, s.apiKey) { id -> voiceCache.getOrPut(id) { service.voices(s.apiKey, id) } }
        val voice = TtsPlanner.voiceName(plan, voices, s.ttsVoice)
            ?: throw RivaException("${plan.model.label} modelinde ${plan.language} için ses bulunamadı.")
        return PreparedSpeech(plan.model, functionId(plan.model), plan.language, voice, voices.sampleRate)
    }

    suspend fun synthesize(p: PreparedSpeech, chunk: String, apiKey: String): ByteArray =
        withFunction(p.model, apiKey) { id -> service.synthesize(apiKey, id, chunk, p.language, p.voice, p.sampleRate) }

    private fun functionId(model: TtsModel) = functionIds[model] ?: settings.ttsFunctionId(model.name) ?: model.defaultFunctionId

    /** Kimlik bulunamazsa NVCF dizininden adıyla yeniden çözüp bir kez daha dener. */
    private suspend fun <T> withFunction(model: TtsModel, apiKey: String, block: suspend (String) -> T): T {
        try {
            return block(functionId(model))
        } catch (e: RivaException) {
            if (!e.notFound) throw e
            val fresh = runCatching { service.findFunctionId(apiKey, model.functionName) }.getOrNull()
                ?: throw e
            if (fresh == functionId(model)) throw e
            functionIds[model] = fresh
            settings.saveTtsFunctionId(model.name, fresh)
            return block(fresh)
        }
    }
}
