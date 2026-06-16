package com.macsia.teatiers.service

import com.macsia.teatiers.AbstractIntegrationTest
import com.macsia.teatiers.client.LlmNames
import com.macsia.teatiers.domain.FlavorDimension
import com.macsia.teatiers.domain.TeaType
import com.macsia.teatiers.repository.TeaRepository
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.transaction.annotation.Transactional

/**
 * Exercises the async enrichment tier's DB writes against the real V2 schema: the PENDING stub, the
 * DONE result (flavors, blurb, aliases, type, confidence), and the FAILED / retry transitions.
 */
@Transactional
class EnrichmentStubServiceIT : AbstractIntegrationTest() {

    @Autowired
    lateinit var stubService: EnrichmentStubService

    @Autowired
    lateinit var teaRepository: TeaRepository

    private fun success(model: String = "aliceai-llm-flash") = EnrichmentResult.Success(
        type = TeaType.OOLONG,
        flavors = FlavorDimension.entries.associateWith { 2 },
        blurbRu = "Насыщенный медовый улун с цветочным ароматом и долгим сладким послевкусием.",
        names = LlmNames(displayRu = "Ми Лань Сян", original = "蜜兰香", pinyin = "Mì Lán Xiāng"),
        confidence = 0.55f,
        model = model,
    )

    @Test
    fun `createOrGetStub inserts a PENDING row from the user name and is idempotent`() {
        val first = stubService.createOrGetStub("Неизвестный Улун IT", "ru")
        assertTrue(first.created)
        assertEquals("PENDING", first.state)

        val tea = teaRepository.findById(first.id).orElseThrow()
        assertEquals(TeaType.OTHER, tea.type)
        assertEquals("ai", tea.source)
        assertEquals("PENDING", tea.enrichmentState)
        assertEquals("Неизвестный Улун IT", tea.names.single { it.isPrimary }.name)
        assertEquals("ru", tea.names.single { it.isPrimary }.locale)

        val second = stubService.createOrGetStub("Неизвестный Улун IT", "ru")
        assertFalse(second.created)
        assertEquals(first.id, second.id)
    }

    @Test
    fun `applyResult fills the profile and flips the row to DONE`() {
        val id = stubService.createOrGetStub("Profile Target IT", "en").id

        stubService.applyResult(id, success())

        val tea = teaRepository.findById(id).orElseThrow()
        assertEquals("DONE", tea.enrichmentState)
        assertNull(tea.enrichmentError)
        assertEquals(TeaType.OOLONG, tea.type)
        assertEquals(0.55f, tea.confidence)
        assertEquals("aliceai-llm-flash", tea.enrichedBy)
        assertNotNull(tea.enrichedAt)
        assertEquals(FlavorDimension.entries.size, tea.flavors.size)
        assertTrue(tea.descriptions.any { it.locale == "ru" && it.source == "ai" })
        assertTrue(tea.names.any { it.locale == "pinyin" && it.name == "Mì Lán Xiāng" })
        assertTrue(tea.names.any { it.locale == "zh-Hans" && it.name == "蜜兰香" })
    }

    @Test
    fun `markFailed and resetToPending move the row between states`() {
        val id = stubService.createOrGetStub("Failing Target IT", "en").id

        stubService.markFailed(id, "invalid json")
        teaRepository.findById(id).orElseThrow().let {
            assertEquals("FAILED", it.enrichmentState)
            assertEquals("invalid json", it.enrichmentError)
        }

        stubService.resetToPending(id)
        teaRepository.findById(id).orElseThrow().let {
            assertEquals("PENDING", it.enrichmentState)
            assertNull(it.enrichmentError)
        }
    }
}
