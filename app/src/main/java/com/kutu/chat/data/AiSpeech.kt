package com.kutu.chat.data

import com.kutu.chat.data.api.RivaException
import com.kutu.chat.data.api.RivaTts
import com.kutu.chat.data.api.RivaVoices
import com.kutu.chat.domain.TtsModel
import com.kutu.chat.domain.TtsPlanner
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

/** Riva TTS çağrılarını soyutlar (testlerde sahtesi kullanılır). */
interface TtsService {
    suspend fun voices(apiKey: String, functionId: String): RivaVoices
    suspend fun synthesize(apiKey: String, functionId: String, text: String, languageCode: String, voiceName: String, sampleRate: Int): ByteArray
    suspend fun findFunctionId(apiKey: String, functionName: String): String?

    /** Ses parça parça gelir. Varsayılan: tek seferlik sentezi tek parça olarak verir. */
    fun synthesizeStream(apiKey: String, functionId: String, text: String, languageCode: String, voiceName: String, sampleRate: Int): Flow<ByteArray> =
        flow { emit(synthesize(apiKey, functionId, text, languageCode, voiceName, sampleRate)) }
}

class RivaTtsService(private val riva: RivaTts = RivaTts()) : TtsService {
    override suspend fun voices(apiKey: String, functionId: String) = riva.voices(apiKey, functionId)
    override suspend fun synthesize(apiKey: String, functionId: String, text: String, languageCode: String, voiceName: String, sampleRate: Int) =
        riva.synthesize(apiKey, functionId, text, languageCode, voiceName, sampleRate)
    override suspend fun findFunctionId(apiKey: String, functionName: String) = riva.findFunctionId(apiKey, functionName)

    /** Akış desteklenmiyorsa (UNIMPLEMENTED) ilk ses gelmeden tek seferlik senteze düşer. */
    override fun synthesizeStream(apiKey: String, functionId: String, text: String, languageCode: String, voiceName: String, sampleRate: Int): Flow<ByteArray> = flow {
        var any = false
        try {
            riva.synthesizeStream(apiKey, functionId, text, languageCode, voiceName, sampleRate).collect { any = true; emit(it) }
        } catch (e: RivaException) {
            if (any || e.grpcStatus != 12) throw e
            emit(riva.synthesize(apiKey, functionId, text, languageCode, voiceName, sampleRate))
        }
    }
}

/** Bir metin için seçilmiş model, dil, ses ve örnekleme hızı. */
data class PreparedSpeech(
    val model: TtsModel,
    val functionId: String,
    val language: String,
    val voice: String,
    val sampleRate: Int,
) {
    /** Bu modelin tek istekte kabul ettiği en fazla karakter. */
    val maxChars get() = model.maxChars
}

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

    /** Parçayı akışlı sentezler: ses ilk saniyede başlar. Kimlik değişmişse bir kez yeniden çözer. */
    fun synthesizeStream(p: PreparedSpeech, chunk: String, apiKey: String): Flow<ByteArray> = flow {
        var emitted = false
        try {
            service.synthesizeStream(apiKey, functionId(p.model), chunk, p.language, p.voice, p.sampleRate).collect { emitted = true; emit(it) }
        } catch (e: RivaException) {
            if (emitted || !e.notFound) throw e
            val fresh = service.findFunctionId(apiKey, p.model.functionName) ?: throw e
            if (fresh == functionId(p.model)) throw e
            functionIds[p.model] = fresh
            settings.saveTtsFunctionId(p.model.name, fresh)
            service.synthesizeStream(apiKey, fresh, chunk, p.language, p.voice, p.sampleRate).collect { emit(it) }
        }
    }

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
