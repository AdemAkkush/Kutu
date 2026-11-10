package com.kutu.chat.data.api

import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.buildClassSerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonEncoder
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * [images] doluysa OpenAI uyumlu çok kipli biçimde gönderilir:
 * `content: [{type:text,…},{type:image_url,image_url:{url:"data:image/jpeg;base64,…"}}]`.
 */
@Serializable(with = ChatMessageSerializer::class)
data class ChatMessageDto(val role: String, val content: String, val images: List<String> = emptyList())

object ChatMessageSerializer : KSerializer<ChatMessageDto> {
    override val descriptor: SerialDescriptor = buildClassSerialDescriptor("com.kutu.chat.ChatMessage")

    override fun serialize(encoder: Encoder, value: ChatMessageDto) {
        val json = encoder as JsonEncoder
        json.encodeJsonElement(
            buildJsonObject {
                put("role", value.role)
                if (value.images.isEmpty()) {
                    put("content", value.content)
                } else {
                    put(
                        "content",
                        buildJsonArray {
                            add(buildJsonObject { put("type", "text"); put("text", value.content) })
                            value.images.forEach { url ->
                                add(
                                    buildJsonObject {
                                        put("type", "image_url")
                                        put("image_url", buildJsonObject { put("url", url) })
                                    },
                                )
                            }
                        },
                    )
                }
            },
        )
    }

    override fun deserialize(decoder: Decoder): ChatMessageDto {
        val obj = (decoder as JsonDecoder).decodeJsonElement().jsonObject
        val role = obj["role"]?.jsonPrimitive?.content.orEmpty()
        val content = when (val c = obj["content"]) {
            is JsonArray -> c.mapNotNull { (it as? JsonObject)?.get("text")?.jsonPrimitive?.content }.joinToString("\n")
            is JsonPrimitive -> c.content
            else -> ""
        }
        return ChatMessageDto(role, content)
    }
}

@Serializable
data class ChatRequest(
    val model: String,
    val messages: List<ChatMessageDto>,
    val stream: Boolean,
    val temperature: Float? = null,
    @SerialName("max_tokens") val maxTokens: Int? = null,
    /** Bazı sağlayıcılar (OpenAI'nin yeni modelleri) `max_tokens` yerine bunu ister. */
    @SerialName("max_completion_tokens") val maxCompletionTokens: Int? = null,
)

@Serializable
data class StreamChunk(val choices: List<StreamChoice> = emptyList())

@Serializable
data class StreamChoice(
    val delta: Delta? = null,
    @SerialName("finish_reason") val finishReason: String? = null,
)

@Serializable
data class Delta(
    val content: String? = null,
    @SerialName("reasoning_content") val reasoningContent: String? = null,
    val reasoning: String? = null,
)

@Serializable
data class CompletionResponse(val choices: List<CompletionChoice> = emptyList())

@Serializable
data class CompletionChoice(val message: CompletionMessage? = null)

@Serializable
data class CompletionMessage(val content: String? = null)

@Serializable
data class ModelsResponse(val data: List<ModelInfo> = emptyList())

@Serializable
data class ModelInfo(val id: String)

sealed interface StreamEvent {
    /** [finish]: sunucu bu parçada yanıtın bittiğini bildirdi (finish_reason). */
    data class Chunk(val content: String, val reasoning: String, val finish: Boolean = false) : StreamEvent
    data object Done : StreamEvent
}

/**
 * [retriable]: geçici bir ağ/sunucu sorunu (bağlantı koptu, 502/503/504). Aynı istek yeniden denenebilir.
 * API'nin açık reddi (401, 404, 400…) ya da zaman aşımı yeniden denenmez.
 */
class ApiException(message: String, val code: Int = 0, val retriable: Boolean = false) : Exception(message)
