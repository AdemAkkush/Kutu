package com.kutu.chat.data.api

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class ChatMessageDto(val role: String, val content: String)

@Serializable
data class ChatRequest(
    val model: String,
    val messages: List<ChatMessageDto>,
    val stream: Boolean,
    val temperature: Float? = null,
    @SerialName("max_tokens") val maxTokens: Int? = null,
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
    data class Chunk(val content: String, val reasoning: String) : StreamEvent
    data object Done : StreamEvent
}

class ApiException(message: String, val code: Int = 0) : Exception(message)
