package com.kutu.chat.domain

import com.kutu.chat.data.AppSettings
import com.kutu.chat.data.api.ChatMessageDto
import com.kutu.chat.data.db.ChatEntity
import com.kutu.chat.data.db.MemoryEntity
import com.kutu.chat.data.db.MessageEntity
import com.kutu.chat.data.db.ROLE_ASSISTANT
import com.kutu.chat.data.db.ROLE_USER
import com.kutu.chat.data.db.STATUS_DONE
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter

object TokenEstimator {
    /** Kaba tahmin: Türkçe ve kod için ~3 karakter ≈ 1 token alınır (temkinli). */
    fun estimate(text: String): Int = text.length / 3 + 4
}

/**
 * Modele gönderilecek mesaj listesini üretir:
 * sistem istemi + uzun süreli hafıza + sohbet özeti + (bütçeye sığan) son mesajlar.
 */
object ContextBuilder {
    private const val MAX_MEMORY_CHARS = 4000

    fun build(
        settings: AppSettings,
        chat: ChatEntity,
        memories: List<MemoryEntity>,
        history: List<MessageEntity>,
        now: ZonedDateTime = ZonedDateTime.now(ZoneId.systemDefault()),
    ): List<ChatMessageDto> {
        val system = buildSystemPrompt(settings, chat, memories, now)
        val usable = usableMessages(chat, history)
        val budget = (settings.contextTokens - TokenEstimator.estimate(system)).coerceAtLeast(1000)
        val trimmed = trimToBudget(usable, budget)
        return buildList {
            add(ChatMessageDto("system", system))
            trimmed.forEach { add(ChatMessageDto(it.role, it.content)) }
        }
    }

    fun buildSystemPrompt(
        settings: AppSettings,
        chat: ChatEntity,
        memories: List<MemoryEntity>,
        now: ZonedDateTime,
    ): String = buildString {
        append(chat.systemPrompt.ifBlank { settings.systemPrompt }.trim())
        append("\n\nŞu anki tarih ve saat: ")
        append(now.format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")))
        append(" (").append(now.zone.id).append(")")

        val facts = if (settings.memoryEnabled) memories.filter { it.enabled } else emptyList()
        if (facts.isNotEmpty()) {
            append("\n\n## Kullanıcı hakkında hatırladıkların (uzun süreli hafıza)\n")
            var used = 0
            for (m in facts) {
                val line = "- ${m.content.trim()}\n"
                if (used + line.length > MAX_MEMORY_CHARS) break
                append(line)
                used += line.length
            }
            append("Bu bilgileri yalnızca ilgili olduklarında ve doğal biçimde kullan. ")
            append("Kullanıcı sormadıkça \"hafızamda var\" diye belirtme.")
        }
        if (chat.summary.isNotBlank()) {
            append("\n\n## Bu sohbetin önceki bölümünün özeti\n")
            append(chat.summary.trim())
            append("\nSohbet bu özetten sonra kaldığı yerden devam ediyor.")
        }
    }

    /** Özetlenmemiş, dolu ve başarılı mesajlar; düşünce kısmı atılmış, ardışık aynı roller birleştirilmiş. */
    fun usableMessages(chat: ChatEntity, history: List<MessageEntity>): List<ChatMessageDto> {
        val out = ArrayList<ChatMessageDto>()
        for (m in history) {
            if (m.id <= chat.summarizedUpTo) continue
            if (m.role != ROLE_USER && m.role != ROLE_ASSISTANT) continue
            if (m.role == ROLE_ASSISTANT && m.status != STATUS_DONE) continue
            val text = if (m.role == ROLE_ASSISTANT) stripThinking(m.content) else m.content.trim()
            if (text.isBlank()) continue
            val last = out.lastOrNull()
            if (last != null && last.role == m.role) {
                out[out.size - 1] = last.copy(content = last.content + "\n\n" + text)
            } else {
                out.add(ChatMessageDto(m.role, text))
            }
        }
        return out
    }

    fun trimToBudget(messages: List<ChatMessageDto>, budgetTokens: Int): List<ChatMessageDto> {
        var total = messages.sumOf { TokenEstimator.estimate(it.content) }
        var start = 0
        while (start < messages.size - 1 && total > budgetTokens) {
            total -= TokenEstimator.estimate(messages[start].content)
            start++
        }
        var result = messages.subList(start, messages.size)
        // Bazı modeller sohbetin "user" ile başlamasını ister.
        if (result.size > 1 && result.first().role != ROLE_USER) result = result.drop(1)
        return result
    }
}
