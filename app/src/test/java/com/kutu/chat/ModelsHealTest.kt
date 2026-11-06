package com.kutu.chat

import com.kutu.chat.data.AppSettings
import com.kutu.chat.data.ModelsRepository
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ModelsHealTest {
    private val models = listOf(
        "google/gemma-4-31b-it", "nvidia/nemotron-3.5-bolt-e4b", "nvidia/embed-qa-4",
        "meta/llama-guard-4-12b", "poolside/laguna-xs-2.1",
    )

    @Test fun removedDefaultsAreReplacedWithWorkingModels() {
        val s = AppSettings(defaultModel = "meta/llama-3.3-70b-instruct", memoryModel = "meta/llama-3.1-8b-instruct")
        val healed = ModelsRepository.heal(s, models)
        assertEquals("google/gemma-4-31b-it", healed.defaultModel)
        assertEquals("nvidia/nemotron-3.5-bolt-e4b", healed.memoryModel)
    }

    @Test fun unavailableModelsAreSkipped() {
        val s = AppSettings(
            defaultModel = "google/gemma-4-31b-it",
            memoryModel = "nvidia/nemotron-3.5-bolt-e4b",
            unavailableModels = setOf("google/gemma-4-31b-it", "nvidia/nemotron-3.5-bolt-e4b"),
        )
        val healed = ModelsRepository.heal(s, models)
        assertEquals("poolside/laguna-xs-2.1", healed.defaultModel)
        assertEquals("poolside/laguna-xs-2.1", healed.memoryModel)
    }

    @Test fun validSelectionsAndEmptyListsAreLeftAlone() {
        val s = AppSettings(defaultModel = "poolside/laguna-xs-2.1", memoryModel = "google/gemma-4-31b-it")
        assertEquals(s, ModelsRepository.heal(s, models))
        assertEquals(s, ModelsRepository.heal(s, emptyList()))
    }

    @Test fun nonChatModelsAreFiltered() {
        assertFalse(ModelsRepository.isChatModel("nvidia/embed-qa-4"))
        assertFalse(ModelsRepository.isChatModel("meta/llama-guard-4-12b"))
        assertFalse(ModelsRepository.isChatModel("nvidia/nemotron-parse-2.0"))
        assertTrue(ModelsRepository.isChatModel("google/gemma-4-31b-it"))
        assertTrue(ModelsRepository.isChatModel("nvidia/riva-translate-4b-instruct-v2"))
    }
}
