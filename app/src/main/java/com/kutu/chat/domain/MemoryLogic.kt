package com.kutu.chat.domain

import com.kutu.chat.data.api.ChatMessageDto
import com.kutu.chat.data.api.NvidiaApi
import com.kutu.chat.data.db.MemoryEntity
import com.kutu.chat.data.db.MessageEntity
import com.kutu.chat.data.db.ROLE_ASSISTANT
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.contentOrNull

/** Hafıza çıkarımı sonucunda uygulanacak değişiklikler. */
data class MemoryOps(val add: List<String>, val remove: List<Long>) {
    val isEmpty get() = add.isEmpty() && remove.isEmpty()
}

object MemoryLogic {
    const val MAX_FACT_LENGTH = 300

    fun extractionMessages(
        existing: List<MemoryEntity>,
        userText: String,
        assistantText: String,
    ): List<ChatMessageDto> {
        val system = """
You maintain the long-term memory of a chat assistant about its user.

Decide whether the USER's latest message reveals durable personal information worth remembering across future conversations (name, location, job, skills, preferences, projects, relationships, goals, constraints, habits), or explicitly asks you to remember or forget something.
Do NOT store: temporary tasks, one-off questions, general world knowledge, the assistant's own statements, or anything sensitive such as passwords, API keys, payment or health details. Do not store what is already in memory.
Write each memory as ONE short self-contained sentence about the user, in the language the user speaks (e.g. "Kullanıcının adı Adem." or "The user lives in Istanbul.").
If an existing memory is outdated or contradicted, put its id in "remove" and add the corrected fact in "add". If the user asks to forget something, remove the matching ids.

Reply with ONLY a JSON object, no prose: {"add": ["..."], "remove": [1, 2]}
Use empty arrays when nothing should change.
        """.trimIndent()
        val memoryList = if (existing.isEmpty()) "(none)" else existing.joinToString("\n") { "${it.id}: ${it.content}" }
        val user = buildString {
            append("Existing memories (id: text):\n").append(memoryList)
            append("\n\nLatest exchange:\nUser: ").append(userText.take(3000))
            append("\nAssistant: ").append(assistantText.take(1500))
        }
        return listOf(ChatMessageDto("system", system), ChatMessageDto("user", user))
    }

    /** Modelin çıktısından ilk JSON nesnesini ayıklar. Bozuk çıktıda boş işlem döner. */
    fun parseOps(raw: String, existing: List<MemoryEntity>): MemoryOps {
        val text = stripThinking(raw)
        val start = text.indexOf('{')
        val end = text.lastIndexOf('}')
        if (start < 0 || end <= start) return MemoryOps(emptyList(), emptyList())
        val obj = runCatching { NvidiaApi.JSON.parseToJsonElement(text.substring(start, end + 1)) }
            .getOrNull() as? JsonObject ?: return MemoryOps(emptyList(), emptyList())

        val known = existing.map { normalize(it.content) }.toMutableSet()
        val add = (obj["add"] as? JsonArray).orEmpty()
            .mapNotNull { (it as? JsonPrimitive)?.contentOrNull?.trim() }
            .map { it.take(MAX_FACT_LENGTH) }
            .filter { it.length >= 4 && known.add(normalize(it)) }
            .take(5)
        val existingIds = existing.map { it.id }.toSet()
        val remove = (obj["remove"] as? JsonArray).orEmpty()
            .mapNotNull { (it as? JsonPrimitive)?.longOrNull }
            .filter { it in existingIds }
            .distinct()
        return MemoryOps(add, remove)
    }

    fun normalize(s: String): String =
        s.lowercase().filter { it.isLetterOrDigit() }

    fun summaryMessages(
        previousSummary: String,
        toFold: List<MessageEntity>,
    ): List<ChatMessageDto> {
        val system = """
You compress chat history. Write a faithful summary of the conversation so far so that it can continue later without the original messages.
Keep: the user's goals, key facts and decisions, preferences, names, code identifiers and snippets that matter, open questions, and the current state of any task.
Drop: greetings and filler. Write in the language of the conversation, as concise bullet points, at most about 350 words. Output only the summary.
        """.trimIndent()
        val user = buildString {
            if (previousSummary.isNotBlank()) append("Previous summary:\n").append(previousSummary.trim()).append("\n\n")
            append("New messages to fold into the summary:\n")
            toFold.forEach { m ->
                val who = if (m.role == ROLE_ASSISTANT) "Assistant" else "User"
                val body = if (m.role == ROLE_ASSISTANT) stripThinking(m.content) else m.content
                append(who).append(": ").append(body.take(4000)).append("\n\n")
            }
        }
        return listOf(ChatMessageDto("system", system), ChatMessageDto("user", user))
    }

    fun titleMessages(userText: String, assistantText: String): List<ChatMessageDto> = listOf(
        ChatMessageDto(
            "system",
            "Write a very short title (2 to 5 words) for this conversation, in the same language the user writes in. " +
                "No quotes, no trailing punctuation, no emoji. Output only the title.",
        ),
        ChatMessageDto("user", "User: ${userText.take(500)}\nAssistant: ${assistantText.take(500)}"),
    )

    fun cleanTitle(raw: String): String =
        stripThinking(raw).lines().firstOrNull { it.isNotBlank() }.orEmpty()
            .trim().trim('"', '\'', '“', '”', '*', '#').trim().trimEnd('.', '!', ':').take(60)
}
