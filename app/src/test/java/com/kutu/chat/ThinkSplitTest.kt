package com.kutu.chat

import com.kutu.chat.domain.splitThinking
import com.kutu.chat.domain.stripThinking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ThinkSplitTest {
    @Test fun plainTextHasNoThinking() {
        val s = splitThinking("Merhaba")
        assertEquals("", s.thinking)
        assertEquals("Merhaba", s.answer)
        assertFalse(s.thinkingOpen)
    }

    @Test fun closedThinkBlockIsSeparated() {
        val s = splitThinking("<think>önce düşün</think>Cevap bu")
        assertEquals("önce düşün", s.thinking)
        assertEquals("Cevap bu", s.answer)
        assertFalse(s.thinkingOpen)
    }

    @Test fun unclosedThinkBlockMeansStillThinking() {
        val s = splitThinking("<think>hâlâ düşünüyorum")
        assertEquals("hâlâ düşünüyorum", s.thinking)
        assertEquals("", s.answer)
        assertTrue(s.thinkingOpen)
    }

    @Test fun closingTagOnlyIsTreatedAsThinkingBeforeIt() {
        val s = splitThinking("akıl yürütme</think>sonuç")
        assertEquals("akıl yürütme", s.thinking)
        assertEquals("sonuç", s.answer)
    }

    @Test fun reasoningFieldIsMergedWithAnswer() {
        val s = splitThinking("Yanıt", "api düşüncesi")
        assertEquals("api düşüncesi", s.thinking)
        assertEquals("Yanıt", s.answer)
    }

    @Test fun stripThinkingKeepsOnlyAnswer() {
        assertEquals("tamam", stripThinking("<think>x</think>tamam"))
    }
}
