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

    /** Bir resmin kabaca kaç token tuttuğu. */
    const val IMAGE_TOKENS = 1100

    fun estimate(m: ChatMessageDto): Int = estimate(m.content) + m.images.size * IMAGE_TOKENS

    /** Bir mesajın ekleriyle birlikte tahmini tokenı (özetleme eşiği için). */
    fun estimate(m: MessageEntity): Int =
        estimate(m.content) + Attachments.parse(m.attachments).sumOf {
            if (it.type == ATT_TEXT) (it.size / 3).toInt() else IMAGE_TOKENS
        }
}

/**
 * Modele gönderilecek mesaj listesini üretir:
 * sistem istemi + uzun süreli hafıza + sohbet özeti + (bütçeye sığan) son mesajlar.
 */
object ContextBuilder {
    private const val MAX_MEMORY_CHARS = 4000
    private const val IMAGE_WINDOW = 4

    fun build(
        settings: AppSettings,
        chat: ChatEntity,
        memories: List<MemoryEntity>,
        history: List<MessageEntity>,
        now: ZonedDateTime = ZonedDateTime.now(ZoneId.systemDefault()),
        source: AttachmentSource? = null,
    ): List<ChatMessageDto> {
        val system = buildSystemPrompt(settings, chat, memories, now)
        val usable = usableMessages(chat, history, source)
        val budget = (settings.contextTokens - TokenEstimator.estimate(system)).coerceAtLeast(1000)
        val trimmed = trimToBudget(usable, budget)
        return buildList {
            add(ChatMessageDto("system", system))
            addAll(trimmed)
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
    fun usableMessages(
        chat: ChatEntity,
        history: List<MessageEntity>,
        source: AttachmentSource? = null,
    ): List<ChatMessageDto> {
        val kept = history.filter {
            it.id > chat.summarizedUpTo &&
                (it.role == ROLE_USER || it.role == ROLE_ASSISTANT) &&
                !(it.role == ROLE_ASSISTANT && it.status != STATUS_DONE)
        }
        // Resimler yalnızca son birkaç mesajda gerçekten gönderilir; eskileri yer tutucuya indirilir.
        val imageFrom = kept.size - IMAGE_WINDOW
        val out = ArrayList<ChatMessageDto>()
        kept.forEachIndexed { index, m ->
            var text = if (m.role == ROLE_ASSISTANT) stripThinking(m.content) else m.content.trim()
            val images = ArrayList<String>()
            if (m.role == ROLE_USER && m.attachments.isNotBlank()) {
                val extra = StringBuilder()
                for (a in Attachments.parse(m.attachments)) {
                    if (a.type == ATT_TEXT) {
                        val body = source?.text(a)
                        if (body != null) extra.append("\n\n[Ek dosya: ${a.name}]\n```\n$body\n```")
                        else extra.append("\n\n[Ek dosya: ${a.name} – okunamadı]")
                    } else {
                        val url = if (index >= imageFrom) source?.imageDataUrl(a) else null
                        if (url != null) images.add(url) else extra.append("\n[Görsel: ${a.name}]")
                    }
                }
                text = (text + extra).trim()
                if (text.isBlank() && images.isNotEmpty()) text = "Ekli görseli incele."
            }
            if (text.isBlank() && images.isEmpty()) return@forEachIndexed
            val last = out.lastOrNull()
            if (last != null && last.role == m.role) {
                out[out.size - 1] = last.copy(content = last.content + "\n\n" + text, images = last.images + images)
            } else {
                out.add(ChatMessageDto(m.role, text, images))
            }
        }
        return out
    }

    fun trimToBudget(messages: List<ChatMessageDto>, budgetTokens: Int): List<ChatMessageDto> {
        var total = messages.sumOf { TokenEstimator.estimate(it) }
        var start = 0
        while (start < messages.size - 1 && total > budgetTokens) {
            total -= TokenEstimator.estimate(messages[start])
            start++
        }
        var result = messages.subList(start, messages.size)
        // Bazı modeller sohbetin "user" ile başlamasını ister.
        if (result.size > 1 && result.first().role != ROLE_USER) result = result.drop(1)
        return result
    }
}
