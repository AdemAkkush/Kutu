package com.kutu.chat.data.api

import com.kutu.chat.data.AppSettings
import com.kutu.chat.domain.CatalogEntry
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import okhttp3.Call
import okhttp3.Callback
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response

/** NVIDIA Build (build.nvidia.com / integrate.api.nvidia.com) için OpenAI uyumlu istemci. */
class NvidiaApi(
    private val client: OkHttpClient = defaultClient(),
) {
    fun streamChat(settings: AppSettings, request: ChatRequest): Flow<StreamEvent> = callbackFlow {
        val call = client.newCall(buildRequest(settings, "/chat/completions", request))
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                close(if (call.isCanceled()) null else networkError(e))
            }

            override fun onResponse(call: Call, response: Response) {
                response.use { res ->
                    try {
                        if (!res.isSuccessful) {
                            close(httpError(res))
                            return
                        }
                        val source = res.body?.source()
                        if (source == null) {
                            close(ApiException("Sunucudan boş yanıt geldi."))
                            return
                        }
                        // Akış [DONE] ya da finish_reason ile bitmeden kapanırsa bağlantı kopmuştur (arka plana atma,
                        // ağ değişimi, proxy). Bunu "tamamlandı" saymak yarım yanıtı sessizce kaydederdi.
                        var completed = false
                        while (true) {
                            val line = source.readUtf8Line() ?: break
                            when (val event = parseSseLine(line)) {
                                null -> Unit
                                StreamEvent.Done -> { completed = true; break }
                                else -> {
                                    if ((event as StreamEvent.Chunk).finish) completed = true
                                    trySend(event)
                                }
                            }
                        }
                        if (completed) close()
                        else close(ApiException("Bağlantı yarıda kesildi (akış tamamlanmadan kapandı).", retriable = true))
                    } catch (e: IOException) {
                        close(if (call.isCanceled()) null else networkError(e))
                    } catch (e: Exception) {
                        close(ApiException("Yanıt işlenemedi: ${e.message}"))
                    }
                }
            }
        })
        awaitClose { call.cancel() }
    }.buffer(Channel.UNLIMITED)

    /** Akışsız tek seferlik çağrı (başlık, özet ve hafıza çıkarımı için). */
    suspend fun complete(settings: AppSettings, request: ChatRequest): String {
        val body = request.copy(stream = false)
        val call = client.newCall(buildRequest(settings, "/chat/completions", body))
        val text = call.await()
        val parsed = runCatching { JSON.decodeFromString<CompletionResponse>(text) }
            .getOrElse { throw ApiException("Yanıt işlenemedi: ${it.message}") }
        return parsed.choices.firstOrNull()?.message?.content.orEmpty()
    }

    suspend fun listModels(settings: AppSettings): List<String> {
        val call = client.newCall(buildGet(settings, "/models"))
        val text = call.await()
        val parsed = runCatching { JSON.decodeFromString<ModelsResponse>(text) }
            .getOrElse { throw ApiException("Model listesi işlenemedi: ${it.message}") }
        // Gemini kimlikleri "models/…" önekiyle döndürür; sohbet uç noktası öneksiz hâlini bekler.
        return parsed.data.map { it.id.removePrefix("models/") }.distinct().sorted()
    }

    /**
     * build.nvidia.com'un herkese açık kataloğu: model başına yetenek etiketleri, popülerlik ve kaldırılma tarihi.
     * Kimlik doğrulama gerekmez. Başarısız olursa çağıran yer sessizce yoklama tabanlı bilgiye düşer.
     */
    suspend fun fetchCatalog(): List<CatalogEntry> {
        val url = CATALOG_URL.toHttpUrl().newBuilder()
            .addQueryParameter("q", """{"query":"","page":0,"pageSize":300}""")
            .build()
        val text = client.newCall(Request.Builder().url(url).get().build()).await()
        return parseCatalog(text)
    }

    private fun baseUrl(settings: AppSettings) = settings.baseUrl.trim().trimEnd('/')

    private fun authorized(builder: Request.Builder, settings: AppSettings): Request.Builder {
        val key = settings.apiKey.trim()
        if (key.isEmpty()) throw ApiException("API anahtarı girilmemiş. Ayarlar'dan NVIDIA API anahtarınızı ekleyin.")
        return builder
            .header("Authorization", "Bearer $key")
            .header("Accept", "application/json, text/event-stream")
    }

    private fun buildRequest(settings: AppSettings, path: String, body: ChatRequest): Request =
        authorized(Request.Builder().url(baseUrl(settings) + path), settings)
            .post(JSON.encodeToString(body).toRequestBody(JSON_MEDIA))
            .build()

    private fun buildGet(settings: AppSettings, path: String): Request =
        authorized(Request.Builder().url(baseUrl(settings) + path), settings).get().build()

    private suspend fun Call.await(): String = suspendCancellableCoroutine { cont ->
        cont.invokeOnCancellation { cancel() }
        enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                if (cont.isActive) cont.resumeWith(Result.failure(networkError(e)))
            }

            override fun onResponse(call: Call, response: Response) {
                response.use { res ->
                    val result = runCatching {
                        if (!res.isSuccessful) throw httpError(res)
                        res.body?.string().orEmpty()
                    }
                    if (cont.isActive) cont.resumeWith(result)
                }
            }
        })
    }

    companion object {
        private val JSON_MEDIA = "application/json".toMediaType()

        val JSON = Json {
            ignoreUnknownKeys = true
            explicitNulls = false
            isLenient = true
        }

        fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(150, TimeUnit.SECONDS)
            .writeTimeout(60, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .build()

        const val CATALOG_URL = "https://api.ngc.nvidia.com/v2/search/catalog/resources/ENDPOINT"

        /** Katalog yanıtından ("ENDPOINT" grubu) model kayıtlarını çıkarır. */
        fun parseCatalog(raw: String): List<CatalogEntry> {
            val root = runCatching { JSON.parseToJsonElement(raw) }.getOrNull() as? JsonObject ?: return emptyList()
            val groups = root["results"] as? kotlinx.serialization.json.JsonArray ?: return emptyList()
            val resources = groups.mapNotNull { it as? JsonObject }
                .firstOrNull { (it["groupValue"] as? JsonPrimitive)?.contentOrNull == "ENDPOINT" }
                ?.get("resources") as? kotlinx.serialization.json.JsonArray ?: return emptyList()
            fun str(e: JsonElement?) = (e as? JsonPrimitive)?.contentOrNull.orEmpty()
            return resources.mapNotNull { el ->
                val r = el as? JsonObject ?: return@mapNotNull null
                val name = str(r["name"])
                if (name.isBlank()) return@mapNotNull null
                val labels = ArrayList<String>()
                var publisher = ""
                (r["labels"] as? kotlinx.serialization.json.JsonArray)?.forEach { l ->
                    val o = l as? JsonObject ?: return@forEach
                    val values = (o["values"] as? kotlinx.serialization.json.JsonArray)?.map { str(it) }.orEmpty()
                    if (str(o["key"]) == "publisher") publisher = values.firstOrNull().orEmpty() else labels += values
                }
                val attrs = (r["attributes"] as? kotlinx.serialization.json.JsonArray)?.mapNotNull { it as? JsonObject }
                    ?.associate { str(it["key"]) to str(it["value"]) }.orEmpty()
                val deprecation = attrs["DEPRECATION"]?.let { parseUsDate(it) }.orEmpty()
                val popularity = maxOf(
                    attrs["lastMonthApiInvocationCount"]?.toLongOrNull() ?: 0,
                    attrs["last_month_api_invocation_count"]?.toLongOrNull() ?: 0,
                )
                CatalogEntry(name.lowercase(), str(r["displayName"]).ifBlank { name }, publisher, labels, deprecation, popularity)
            }
        }

        /** "11/15/2026" → "2026-11-15"; çözülemezse boş. */
        fun parseUsDate(s: String): String = runCatching {
            java.time.LocalDate.parse(s.trim(), java.time.format.DateTimeFormatter.ofPattern("M/d/yyyy")).toString()
        }.getOrDefault("")

        /** Tek bir SSE satırını çözer. İlgisiz satırlar için null döner. */
        fun parseSseLine(line: String): StreamEvent? {
            if (!line.startsWith("data:")) return null
            val payload = line.removePrefix("data:").trim()
            if (payload.isEmpty()) return null
            if (payload == "[DONE]") return StreamEvent.Done
            val chunk = runCatching { JSON.decodeFromString<StreamChunk>(payload) }.getOrNull() ?: return null
            val choice = chunk.choices.firstOrNull() ?: return null
            val content = choice.delta?.content.orEmpty()
            val reasoning = choice.delta?.reasoningContent ?: choice.delta?.reasoning.orEmpty()
            val finish = !choice.finishReason.isNullOrEmpty()
            if (content.isEmpty() && reasoning.isEmpty() && !finish) return null
            return StreamEvent.Chunk(content, reasoning, finish)
        }

        private fun networkError(e: IOException) = when (e) {
            is java.net.SocketTimeoutException ->
                ApiException("Model 150 saniye içinde yanıt vermedi. Model yoğun ya da kapalı olabilir; başka bir model deneyin.")
            else -> ApiException("Bağlantı koptu: ${e.message ?: e.javaClass.simpleName}", retriable = true)
        }

        private fun httpError(res: Response): ApiException {
            val raw = runCatching { res.body?.string().orEmpty() }.getOrDefault("")
            val detail = extractErrorMessage(raw)
            val prefix = when (res.code) {
                401, 403 -> "Yetkilendirme hatası (${res.code}): API anahtarınızı kontrol edin."
                404 -> "Model kullanılamıyor (404): kaldırılmış ya da hesabınıza kapalı olabilir. Başka bir model seçin."
                410 -> "Model artık yok (410): NVIDIA bu modeli kaldırmış. Başka bir model seçin."
                429 -> "İstek sınırı aşıldı (429): biraz bekleyip tekrar deneyin."
                else -> "Sunucu hatası (${res.code})"
            }
            return ApiException(if (detail.isBlank()) prefix else "$prefix\n$detail", res.code, retriable = res.code in 502..504)
        }

        fun extractErrorMessage(raw: String): String {
            if (raw.isBlank()) return ""
            val root: JsonElement = runCatching { JSON.parseToJsonElement(raw) }.getOrNull()
                ?: return raw.take(300)
            if (root !is JsonObject) return raw.take(300)
            fun str(e: JsonElement?): String? = (e as? JsonPrimitive)?.contentOrNull
            str(root["detail"])?.let { return it }
            (root["error"] as? JsonObject)?.let { err -> str(err["message"])?.let { return it } }
            str(root["error"])?.let { return it }
            str(root["message"])?.let { return it }
            str(root["title"])?.let { return it }
            return raw.take(300)
        }
    }
}
