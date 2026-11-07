package com.kutu.chat

import com.kutu.chat.data.api.NvidiaApi
import com.kutu.chat.domain.Cap
import com.kutu.chat.domain.Capabilities
import com.kutu.chat.domain.CatalogEntry
import java.time.LocalDate
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CapabilitiesTest {
    private fun resource(name: String) = javaClass.classLoader!!.getResource(name)!!.readText()
    private val catalog: Map<String, CatalogEntry> by lazy { NvidiaApi.parseCatalog(resource("catalog_sample.json")).associateBy { it.name } }
    private val record by lazy { Json.parseToJsonElement(resource("probe_record.json")).jsonObject }
    private fun list(key: String) = record[key]!!.jsonArray.map { it.jsonPrimitive.content }

    @Test fun catalogParsesRealResponse() {
        assertTrue(catalog.size > 100)
        val gemma = catalog["gemma-4-31b-it"]!!
        assertEquals("google", gemma.publisher)
        assertTrue(gemma.labels.contains("reasoning"))
        assertTrue(gemma.popularity > 0)
        assertEquals("2026-11-15", catalog["llama-3.2-90b-vision-instruct"]!!.deprecation)
    }

    /** Gerçek yoklama kaydı: çalışan her modelin katalogda olduğu, 404/410 verenlerin hiçbirinin olmadığı doğrulanmıştı. */
    @Test fun catalogPresenceSeparatesLiveFromRetiredModels() {
        list("working").forEach { assertEquals(it, true, Capabilities.describe(it, catalog).inCatalog) }
        list("dead").forEach { assertEquals(it, false, Capabilities.describe(it, catalog).inCatalog) }
    }

    @Test fun emptyCatalogMeansUnknownNotDead() {
        assertNull(Capabilities.describe("google/gemma-4-31b-it", emptyMap()).inCatalog)
    }

    @Test fun idHeuristicsCoverModelsMissingFromCatalog() {
        assertTrue(Cap.CODE in Capabilities.fromId("mistralai/codestral-22b-instruct-v0.1"))
        assertTrue(Cap.VISION in Capabilities.fromId("microsoft/phi-3-vision-128k-instruct"))
        assertTrue(Cap.TRANSLATE in Capabilities.fromId("nvidia/riva-translate-4b-instruct"))
        assertTrue(Cap.EMBED in Capabilities.fromId("nvidia/nv-embedqa-mistral-7b-v2"))
        assertTrue(Cap.SAFETY in Capabilities.fromId("meta/llama-guard-4-12b"))
        assertFalse(Capabilities.describe("meta/llama-guard-4-12b", emptyMap()).chatCapable)
    }

    @Test fun verifiedVisionModelsAreRecognisedEvenWhenCatalogLabelsMissIt() {
        // Gerçek API'de resim gönderilerek doğrulandı; katalogda "görsel" etiketi yok.
        assertTrue(Capabilities.describe("google/gemma-4-31b-it", catalog).has(Cap.VISION))
        assertTrue(Capabilities.describe("minimaxai/minimax-m3.1-flash", catalog).has(Cap.VISION))
        assertTrue(Capabilities.describe("meta/llama-3.2-90b-vision-instruct", catalog).has(Cap.VISION))
        assertFalse(Capabilities.describe("poolside/laguna-xs-2.1", catalog).has(Cap.VISION))
    }

    @Test fun learnedVisionOverridesCatalog() {
        val id = "poolside/laguna-xs-2.1"
        assertTrue(Capabilities.describe(id, catalog, visionConfirmed = setOf(id)).has(Cap.VISION))
        val gemma = "google/gemma-4-31b-it"
        assertFalse(Capabilities.describe(gemma, catalog, visionRejected = setOf(gemma)).has(Cap.VISION))
    }

    @Test fun bestPicksPopularLiveNonDeprecatingModel() {
        val ids = list("working")
        val metas = ids.map { Capabilities.describe(it, catalog) }
        val best = Capabilities.best(Cap.VISION, metas, unavailable = emptySet(), today = LocalDate.of(2026, 11, 7))
        assertNotNull(best)
        assertTrue(best!!.has(Cap.VISION))
        // llama-3.2-90b-vision 15.11.2026'da kaldırılıyor → 30 gün içinde olduğu için önerilmemeli.
        assertFalse(best.id, best.id == "meta/llama-3.2-90b-vision-instruct")
        val withoutBest = Capabilities.best(Cap.VISION, metas, unavailable = setOf(best.id), today = LocalDate.of(2026, 11, 7))
        assertTrue(withoutBest == null || withoutBest.id != best.id)
    }

    @Test fun parseUsDate() {
        assertEquals("2026-11-15", NvidiaApi.parseUsDate("11/15/2026"))
        assertEquals("", NvidiaApi.parseUsDate("yakında"))
    }
}
