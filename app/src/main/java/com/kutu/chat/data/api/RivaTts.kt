package com.kutu.chat.data.api

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.URLDecoder
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response

class RivaException(message: String, val grpcStatus: Int = -1, val http: Int = 0) : Exception(message) {
    /** İşlev bulunamadı: kimlik değişmiş olabilir, dizinden yeniden çözülmeli. */
    val notFound get() = grpcStatus == 5 || http == 404
}

/** Bir TTS modelinin sunucudaki yapılandırması. */
data class RivaVoices(
    val sampleRate: Int,
    val languages: List<String>,
    /** Alt ses adları, örn. "EN-US.Aria", "tr-TR.Male" (":kimlik" kısmı atılmış). */
    val subvoices: List<String>,
    val voiceName: String,
)

/**
 * NVIDIA Riva TTS (Magpie, Chatterbox) için sade gRPC istemcisi.
 *
 * gRPC, HTTP/2 üzerinde 5 baytlık çerçeveli protobuf mesajlarıdır; tek yönlü (unary) çağrılar için
 * ağır gRPC kütüphaneleri yerine OkHttp yeterlidir. Protobuf alanları elle kodlanır.
 * Sunucu: `grpc.nvcf.nvidia.com`, model `function-id` başlığıyla seçilir, kimlik doğrulama NVIDIA API anahtarıdır.
 */
class RivaTts(
    private val client: OkHttpClient = NvidiaApi.defaultClient(),
    private val host: String = "https://grpc.nvcf.nvidia.com",
) {
    /** Metni konuşmaya çevirir. Dönen veri 16 bit, tek kanallı ham PCM'dir. */
    suspend fun synthesize(
        apiKey: String,
        functionId: String,
        text: String,
        languageCode: String,
        voiceName: String,
        sampleRate: Int,
    ): ByteArray {
        val reply = unary(synthesizeRequest(text, languageCode, voiceName, sampleRate, apiKey, functionId, "/nvidia.riva.tts.RivaSpeechSynthesis/Synthesize"))
        val audio = Proto.fields(reply).firstOrNull { it.number == 1 && it.bytes != null }?.bytes
        if (audio == null || audio.isEmpty()) throw RivaException("Sunucu ses döndürmedi.")
        return audio
    }

    suspend fun voices(apiKey: String, functionId: String): RivaVoices {
        val reply = unary(grpcRequest("/nvidia.riva.tts.RivaSpeechSynthesis/GetRivaSynthesisConfig", apiKey, functionId, ByteArray(0)))
        return parseConfig(reply)
    }

    /** NVCF işlev dizininden ada göre kimlik bulur (model kimliği değiştiğinde). */
    suspend fun findFunctionId(apiKey: String, functionName: String): String? {
        val request = Request.Builder().url("https://api.nvcf.nvidia.com/v2/nvcf/functions")
            .header("authorization", "Bearer $apiKey").get().build()
        val text = suspendCancellableCoroutine<String> { cont ->
            val call = client.newCall(request)
            cont.invokeOnCancellation { call.cancel() }
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) { if (cont.isActive) cont.resumeWithException(RivaException("Ses dizinine ulaşılamadı: ${e.message}")) }
                override fun onResponse(call: Call, response: Response) {
                    response.use { r ->
                        if (cont.isActive) {
                            if (r.isSuccessful) cont.resume(r.body?.string().orEmpty())
                            else cont.resumeWithException(RivaException("Ses dizini hatası (${r.code}).", http = r.code))
                        }
                    }
                }
            })
        }
        val functions = runCatching { NvidiaApi.JSON.parseToJsonElement(text).jsonObject["functions"]?.jsonArray }.getOrNull() ?: return null
        return functions.mapNotNull { it as? kotlinx.serialization.json.JsonObject }
            .firstOrNull { it["name"]?.jsonPrimitive?.contentOrNull == functionName && it["status"]?.jsonPrimitive?.contentOrNull == "ACTIVE" }
            ?.get("id")?.jsonPrimitive?.contentOrNull
    }

    private suspend fun unary(request: Request): ByteArray {
        val (body, status, grpcMessage, http) = client.newCall(request).awaitGrpc()
        failure(http, status, grpcMessage)?.let { throw it }
        return unframe(body)
    }

    /**
     * Sunucu yanıtını Türkçe bir hataya çevirir; başarılıysa null.
     * Ağ geçidi hataları HTTP koduyla, gRPC hataları `grpc-status` ile gelir.
     */
    private fun failure(http: Int, status: Int, grpcMessage: String): RivaException? {
        if (http != 200) {
            return RivaException(
                when (http) {
                    401, 403 -> "Yetkilendirme hatası ($http): API anahtarınız bu ses modeline erişemiyor."
                    404 -> "Ses modeli bulunamadı (404)."
                    429 -> "İstek sınırı aşıldı (429): biraz bekleyin."
                    else -> "Ses sunucusu hatası ($http)."
                },
                http = http,
            )
        }
        if (status == 0) return null
        val tooLong = grpcMessage.contains("exceeds maximum allowed length")
        return RivaException(
            when {
                tooLong -> "Metin parçası modelin sınırını aşıyor ($grpcMessage)"
                status == 16 -> "Yetkilendirme hatası: API anahtarı geçersiz."
                status == 8 -> "Ses isteği sınırı aşıldı, biraz bekleyin."
                status == 14 -> "Ses sunucusuna ulaşılamadı."
                status == 3 -> "Ses isteği reddedildi: $grpcMessage"
                else -> "Ses sunucusu hatası ($status): ${grpcMessage.take(160)}"
            },
            grpcStatus = status,
        )
    }

    private fun synthesizeRequest(text: String, languageCode: String, voiceName: String, sampleRate: Int, apiKey: String, functionId: String, path: String): Request {
        val message = Proto.string(1, text) + Proto.string(2, languageCode) + Proto.varint(3, ENCODING_LINEAR_PCM) +
            Proto.varint(4, sampleRate.toLong()) + Proto.string(5, voiceName)
        return grpcRequest(path, apiKey, functionId, message)
    }

    private fun grpcRequest(path: String, apiKey: String, functionId: String, message: ByteArray): Request {
        if (apiKey.isBlank()) throw RivaException("API anahtarı girilmemiş.")
        val framed = ByteArray(5 + message.size)
        framed[1] = (message.size ushr 24).toByte(); framed[2] = (message.size ushr 16).toByte()
        framed[3] = (message.size ushr 8).toByte(); framed[4] = message.size.toByte()
        message.copyInto(framed, 5)
        return Request.Builder()
            .url(host + path)
            .header("te", "trailers")
            .header("grpc-accept-encoding", "identity")
            .header("accept-encoding", "identity")
            .header("user-agent", "kutu-grpc/1.0")
            .header("function-id", functionId)
            .header("authorization", "Bearer $apiKey")
            .post(framed.toRequestBody(GRPC))
            .build()
    }

    /**
     * Akışlı sentez (`SynthesizeOnline`): ses üretildikçe parça parça gelir, ilk ses ~1 saniyede başlar.
     * Metin sınırı tek-seferlik çağrıyla aynıdır (Chatterbox için 500 karakter).
     */
    fun synthesizeStream(
        apiKey: String,
        functionId: String,
        text: String,
        languageCode: String,
        voiceName: String,
        sampleRate: Int,
    ): Flow<ByteArray> = callbackFlow {
        val request = synthesizeRequest(text, languageCode, voiceName, sampleRate, apiKey, functionId, "/nvidia.riva.tts.RivaSpeechSynthesis/SynthesizeOnline")
        val call = client.newCall(request)
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                close(if (call.isCanceled()) null else RivaException("Bağlantı hatası: ${e.message ?: e.javaClass.simpleName}"))
            }

            override fun onResponse(call: Call, response: Response) {
                response.use { res ->
                    try {
                        if (res.code != 200) { close(failure(res.code, 0, "")); return }
                        if (res.protocol != Protocol.HTTP_2) { close(RivaException("Ses sunucusu HTTP/2 gerektiriyor (${res.protocol}).")); return }
                        val source = res.body!!.source()
                        while (!source.exhausted()) {
                            val flag = source.readByte().toInt()
                            val len = source.readInt()
                            if (flag != 0) throw RivaException("Sunucu sıkıştırılmış yanıt gönderdi (desteklenmiyor).")
                            if (len < 0 || len > MAX_MESSAGE) throw RivaException("Bozuk gRPC yanıtı.")
                            val audio = Proto.fields(source.readByteArray(len.toLong())).firstOrNull { it.number == 1 && it.bytes != null }?.bytes
                            if (audio != null && audio.isNotEmpty()) trySend(audio)
                        }
                        val trailers = runCatching { res.trailers() }.getOrNull()
                        val status = (trailers?.get("grpc-status") ?: res.header("grpc-status"))?.toIntOrNull() ?: 0
                        val msg = (trailers?.get("grpc-message") ?: res.header("grpc-message")).orEmpty()
                        close(failure(200, status, runCatching { URLDecoder.decode(msg, "UTF-8") }.getOrDefault(msg)))
                    } catch (e: RivaException) {
                        close(e)
                    } catch (e: IOException) {
                        close(if (call.isCanceled()) null else RivaException("Ses bağlantısı koptu: ${e.message ?: e.javaClass.simpleName}"))
                    } catch (e: Exception) {
                        close(RivaException("Yanıt okunamadı: ${e.message}"))
                    }
                }
            }
        })
        awaitClose { call.cancel() }
    }.buffer(Channel.UNLIMITED)

    private data class GrpcResult(val body: ByteArray, val status: Int, val message: String, val http: Int)

    private suspend fun Call.awaitGrpc(): GrpcResult = suspendCancellableCoroutine { cont ->
        cont.invokeOnCancellation { cancel() }
        enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                if (cont.isActive) cont.resumeWithException(
                    RivaException(
                        if (e is java.net.SocketTimeoutException) "Ses sunucusu zamanında yanıt vermedi."
                        else "Bağlantı hatası: ${e.message ?: e.javaClass.simpleName}",
                    ),
                )
            }

            override fun onResponse(call: Call, response: Response) {
                response.use { res ->
                    try {
                        if (res.protocol != Protocol.HTTP_2 && res.isSuccessful) {
                            throw RivaException("Ses sunucusu HTTP/2 gerektiriyor (${res.protocol}).")
                        }
                        val bytes = res.body?.bytes() ?: ByteArray(0)
                        val trailers = runCatching { res.trailers() }.getOrNull()
                        val status = (trailers?.get("grpc-status") ?: res.header("grpc-status"))?.toIntOrNull() ?: 0
                        val msg = (trailers?.get("grpc-message") ?: res.header("grpc-message")).orEmpty()
                        val decoded = runCatching { URLDecoder.decode(msg, "UTF-8") }.getOrDefault(msg)
                        if (cont.isActive) cont.resume(GrpcResult(bytes, status, decoded, res.code))
                    } catch (e: Exception) {
                        if (cont.isActive) cont.resumeWithException(if (e is RivaException) e else RivaException("Yanıt okunamadı: ${e.message}"))
                    }
                }
            }
        })
    }

    companion object {
        private val GRPC = "application/grpc".toMediaType()
        const val ENCODING_LINEAR_PCM = 1L
        private const val MAX_MESSAGE = 64 * 1024 * 1024

        /** gRPC çerçevelerinden (1 bayt bayrak + 4 bayt uzunluk) mesaj yüklerini birleştirir. */
        fun unframe(body: ByteArray): ByteArray {
            val out = ByteArrayOutputStream()
            var i = 0
            while (i + 5 <= body.size) {
                val compressed = body[i].toInt() != 0
                val len = ((body[i + 1].toInt() and 0xFF) shl 24) or ((body[i + 2].toInt() and 0xFF) shl 16) or
                    ((body[i + 3].toInt() and 0xFF) shl 8) or (body[i + 4].toInt() and 0xFF)
                if (compressed) throw RivaException("Sunucu sıkıştırılmış yanıt gönderdi (desteklenmiyor).")
                if (len < 0 || i + 5 + len > body.size) throw RivaException("Bozuk gRPC yanıtı.")
                out.write(body, i + 5, len)
                i += 5 + len
            }
            return out.toByteArray()
        }

        /** `GetRivaSynthesisConfig` yanıtını (Config.parameters haritası) çözer. */
        fun parseConfig(reply: ByteArray): RivaVoices {
            val params = HashMap<String, String>()
            for (config in Proto.fields(reply).filter { it.number == 1 && it.bytes != null }) {
                for (entry in Proto.fields(config.bytes!!).filter { it.number == 2 && it.bytes != null }) {
                    val kv = Proto.fields(entry.bytes!!)
                    val k = kv.firstOrNull { it.number == 1 }?.bytes?.toString(Charsets.UTF_8) ?: continue
                    val v = kv.firstOrNull { it.number == 2 }?.bytes?.toString(Charsets.UTF_8).orEmpty()
                    params.putIfAbsent(k, v)
                }
            }
            return RivaVoices(
                sampleRate = params["sample_rate"]?.toIntOrNull() ?: 22050,
                languages = params["language_code"].orEmpty().split(',').filter { it.isNotBlank() },
                subvoices = params["subvoices"].orEmpty().split(',').map { it.substringBefore(':').trim() }.filter { it.isNotBlank() },
                voiceName = params["voice_name"].orEmpty(),
            )
        }
    }
}

/** Gereken en küçük protobuf alt kümesi: uzunluk-önekli alanlar ve varint'ler. */
object Proto {
    class Field(val number: Int, val bytes: ByteArray?, val value: Long)

    fun varint(number: Int, value: Long): ByteArray = tag(number, 0) + varintBytes(value)
    fun string(number: Int, value: String): ByteArray = bytes(number, value.toByteArray(Charsets.UTF_8))
    fun bytes(number: Int, value: ByteArray): ByteArray = tag(number, 2) + varintBytes(value.size.toLong()) + value

    private fun tag(number: Int, wire: Int) = varintBytes(((number shl 3) or wire).toLong())

    private fun varintBytes(v: Long): ByteArray {
        val out = ByteArrayOutputStream()
        var x = v
        while (x and 0x7FL.inv() != 0L) { out.write(((x and 0x7F) or 0x80).toInt()); x = x ushr 7 }
        out.write(x.toInt())
        return out.toByteArray()
    }

    fun fields(data: ByteArray): List<Field> {
        val out = ArrayList<Field>()
        var i = 0
        fun readVarint(): Long {
            var shift = 0; var result = 0L
            while (i < data.size) {
                val b = data[i++].toInt()
                result = result or ((b and 0x7F).toLong() shl shift)
                if (b and 0x80 == 0) return result
                shift += 7
            }
            throw RivaException("Bozuk protobuf verisi.")
        }
        while (i < data.size) {
            val key = readVarint()
            val number = (key ushr 3).toInt()
            when ((key and 7).toInt()) {
                0 -> out.add(Field(number, null, readVarint()))
                2 -> {
                    val len = readVarint().toInt()
                    if (len < 0 || i + len > data.size) throw RivaException("Bozuk protobuf verisi.")
                    out.add(Field(number, data.copyOfRange(i, i + len), 0)); i += len
                }
                1 -> i += 8
                5 -> i += 4
                else -> throw RivaException("Desteklenmeyen protobuf alan türü.")
            }
        }
        return out
    }
}
