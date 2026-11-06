package com.kutu.chat

import com.kutu.chat.data.db.MemoryEntity
import com.kutu.chat.domain.MemoryLogic
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MemoryLogicTest {
    private fun mem(id: Long, text: String) = MemoryEntity(id = id, content = text, createdAt = 0, updatedAt = 0)
    private val existing = listOf(mem(1, "Kullanıcının adı Adem."), mem(2, "Kullanıcı Ankara'da yaşıyor."))

    @Test fun parsesAddAndRemove() {
        val ops = MemoryLogic.parseOps("""{"add": ["Kullanıcı İstanbul'a taşındı."], "remove": [2]}""", existing)
        assertEquals(listOf("Kullanıcı İstanbul'a taşındı."), ops.add)
        assertEquals(listOf(2L), ops.remove)
    }

    @Test fun toleratesProseAndCodeFences() {
        val raw = "Tabii!\n```json\n{\"add\": [\"Kullanıcı Kotlin biliyor.\"], \"remove\": []}\n```"
        assertEquals(listOf("Kullanıcı Kotlin biliyor."), MemoryLogic.parseOps(raw, existing).add)
    }

    @Test fun ignoresThinkBlocks() {
        val raw = "<think>{\"add\": [\"yanlış\"]}</think>{\"add\": [\"Doğru bilgi burada.\"], \"remove\": []}"
        assertEquals(listOf("Doğru bilgi burada."), MemoryLogic.parseOps(raw, existing).add)
    }

    @Test fun dropsDuplicatesOfExistingAndUnknownIds() {
        val ops = MemoryLogic.parseOps("""{"add": ["kullanıcının adı adem", "Yeni bilgi var."], "remove": [99, 1]}""", existing)
        assertEquals(listOf("Yeni bilgi var."), ops.add)
        assertEquals(listOf(1L), ops.remove)
    }

    @Test fun garbageYieldsNoOps() {
        assertTrue(MemoryLogic.parseOps("üzgünüm yapamam", existing).isEmpty)
        assertTrue(MemoryLogic.parseOps("{bozuk json", existing).isEmpty)
        assertTrue(MemoryLogic.parseOps("""{"add": "tek string"}""", existing).isEmpty)
    }

    @Test fun cleansGeneratedTitles() {
        assertEquals("Kotlin coroutine örneği", MemoryLogic.cleanTitle("\"Kotlin coroutine örneği.\"\nBaşka satır"))
        assertEquals("Başlık", MemoryLogic.cleanTitle("<think>x</think>**Başlık**"))
    }
}
