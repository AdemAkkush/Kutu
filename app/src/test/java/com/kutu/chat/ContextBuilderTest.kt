package com.kutu.chat

import com.kutu.chat.data.AppSettings
import com.kutu.chat.data.db.ChatEntity
import com.kutu.chat.data.db.MemoryEntity
import com.kutu.chat.data.db.MessageEntity
import com.kutu.chat.data.db.ROLE_ASSISTANT
import com.kutu.chat.data.db.ROLE_USER
import com.kutu.chat.data.db.STATUS_ERROR
import com.kutu.chat.data.db.STATUS_STREAMING
import com.kutu.chat.domain.ContextBuilder
import java.time.ZoneId
import java.time.ZonedDateTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ContextBuilderTest {
    private val now = ZonedDateTime.of(2026, 11, 6, 10, 30, 0, 0, ZoneId.of("Europe/Istanbul"))
    private val chat = ChatEntity(id = 1, model = "m", createdAt = 0, updatedAt = 0)
    private val settings = AppSettings(systemPrompt = "SİSTEM")

    private fun msg(id: Long, role: String, text: String, status: Int = 0) =
        MessageEntity(id = id, chatId = 1, role = role, content = text, status = status, createdAt = id)

    private fun mem(id: Long, text: String, enabled: Boolean = true) =
        MemoryEntity(id = id, content = text, enabled = enabled, createdAt = id, updatedAt = id)

    @Test fun systemPromptContainsMemoryAndSummary() {
        val c = chat.copy(summary = "Kullanıcı bir Android uygulaması yazıyor.")
        val out = ContextBuilder.build(settings, c, listOf(mem(1, "Kullanıcının adı Adem."), mem(2, "gizli", enabled = false)), emptyList(), now)
        val system = out.first().content
        assertEquals("system", out.first().role)
        assertTrue(system.startsWith("SİSTEM"))
        assertTrue(system.contains("2026-11-06 10:30"))
        assertTrue(system.contains("Kullanıcının adı Adem."))
        assertFalse(system.contains("gizli"))
        assertTrue(system.contains("Android uygulaması"))
    }

    @Test fun memoryIsOmittedWhenDisabled() {
        val out = ContextBuilder.build(settings.copy(memoryEnabled = false), chat, listOf(mem(1, "Adem")), emptyList(), now)
        assertFalse(out.first().content.contains("Adem"))
    }

    @Test fun chatSystemPromptOverridesGlobal() {
        val out = ContextBuilder.build(settings, chat.copy(systemPrompt = "ÖZEL"), emptyList(), emptyList(), now)
        assertTrue(out.first().content.startsWith("ÖZEL"))
    }

    @Test fun skipsSummarizedFailedAndEmptyMessages() {
        val history = listOf(
            msg(1, ROLE_USER, "eski"),
            msg(2, ROLE_ASSISTANT, "eski cevap"),
            msg(3, ROLE_USER, "yeni soru"),
            msg(4, ROLE_ASSISTANT, "yarım", status = STATUS_ERROR),
            msg(5, ROLE_ASSISTANT, "", status = STATUS_STREAMING),
        )
        val out = ContextBuilder.build(settings, chat.copy(summarizedUpTo = 2), emptyList(), history, now)
        assertEquals(listOf("system", "user"), out.map { it.role })
        assertEquals("yeni soru", out.last().content)
    }

    @Test fun thinkingIsStrippedFromHistory() {
        val history = listOf(msg(1, ROLE_USER, "2+2?"), msg(2, ROLE_ASSISTANT, "<think>hesapla</think>4"), msg(3, ROLE_USER, "peki 3+3?"))
        val out = ContextBuilder.build(settings, chat, emptyList(), history, now)
        assertEquals("4", out[2].content)
    }

    @Test fun consecutiveSameRoleMessagesAreMerged() {
        val history = listOf(msg(1, ROLE_USER, "a"), msg(2, ROLE_ASSISTANT, "hata", status = STATUS_ERROR), msg(3, ROLE_USER, "b"))
        val out = ContextBuilder.build(settings, chat, emptyList(), history, now)
        assertEquals(2, out.size)
        assertEquals("a\n\nb", out[1].content)
    }

    @Test fun oldestMessagesAreDroppedWhenOverBudget() {
        val big = "x".repeat(6000) // ~2000 token
        val history = (1L..8L).map { msg(it, if (it % 2 == 1L) ROLE_USER else ROLE_ASSISTANT, big + it) }
        val out = ContextBuilder.build(settings.copy(contextTokens = 5000), chat, emptyList(), history, now)
        val kept = out.drop(1)
        assertTrue(kept.size < 8)
        assertEquals("user", kept.first().role)
        assertTrue(kept.last().content.endsWith("8"))
    }

    @Test fun lastMessageIsAlwaysKept() {
        val history = listOf(msg(1, ROLE_USER, "x".repeat(300_000)))
        val out = ContextBuilder.build(settings.copy(contextTokens = 4000), chat, emptyList(), history, now)
        assertEquals(2, out.size)
    }
}
