package com.macsia.teatiers.service

import com.macsia.teatiers.client.FoundationModelsClient
import com.macsia.teatiers.client.LlmProperties
import com.macsia.teatiers.domain.FlavorDimension
import com.macsia.teatiers.domain.TeaType
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LlmEnrichmentServiceTest {

    private val client = mockk<FoundationModelsClient>()
    private val props = LlmProperties()
    private val stubService = mockk<EnrichmentStubService>(relaxed = true)
    private val service = LlmEnrichmentService(client, props, stubService)

    private fun profileJson(
        type: String = "GREEN",
        value: Int = 1,
        blurb: String = "Свежий зелёный чай с лёгкими травяными нотами и мягкой сладостью.",
        overall: Double = 0.5,
    ): String {
        val dims = FlavorDimension.entries.joinToString(",") {
            """"${it.name}":{"value":$value,"confidence":0.7,"evidence":false}"""
        }
        return """{"names":{"display_ru":"Тест","original":"测试","pinyin":"Ceshi"},""" +
            """"type":"$type","dimensions":{$dims},"short_blurb_ru":"$blurb","overall_confidence":$overall}"""
    }

    @Test
    fun `valid zero-shot reply is parsed and the confidence is capped then discounted`() {
        every { client.chatJson(any(), any(), any(), any()) } returns profileJson(overall = 0.9)

        val result = service.profile("Sencha", null) as EnrichmentResult.Success

        assertEquals(TeaType.GREEN, result.type)
        assertEquals(FlavorDimension.entries.size, result.flavors.size)
        // zero-shot: min(0.9, 0.6) * 0.7 = 0.42
        assertTrue(abs(result.confidence - 0.42f) < 1e-4)
    }

    @Test
    fun `a Chinese name routes to the booster model`() {
        val model = slot<String>()
        every { client.chatJson(capture(model), any(), any(), any()) } returns profileJson()

        service.profile("龙井", null)

        assertEquals(props.boosterModel, model.captured)
    }

    @Test
    fun `a Latin name routes to the primary model`() {
        val model = slot<String>()
        every { client.chatJson(capture(model), any(), any(), any()) } returns profileJson()

        service.profile("Sencha", null)

        assertEquals(props.primaryModel, model.captured)
    }

    @Test
    fun `a central-tendency profile is penalized`() {
        every { client.chatJson(any(), any(), any(), any()) } returns profileJson(value = 2, overall = 0.8)

        val result = service.profile("Sencha", "grounded vendor blurb about a tea") as EnrichmentResult.Success

        // grounded (no zero-shot factor), all dims in {2,3} -> 0.8 * 0.5
        assertTrue(abs(result.confidence - 0.4f) < 1e-4)
    }

    @Test
    fun `a blurb that copies the vendor text is dropped`() {
        val vendor = "this tea has a wonderful sweet honey aroma and soft floral notes"
        every { client.chatJson(any(), any(), any(), any()) } returns profileJson(blurb = vendor)

        val result = service.profile("Honey Oolong", vendor) as EnrichmentResult.Success

        assertNull(result.blurbRu)
    }

    @Test
    fun `an unknown type falls back to OTHER`() {
        every { client.chatJson(any(), any(), any(), any()) } returns profileJson(type = "DRAGON")

        val result = service.profile("Sencha", null) as EnrichmentResult.Success

        assertEquals(TeaType.OTHER, result.type)
    }

    @Test
    fun `a null model reply is a failure`() {
        every { client.chatJson(any(), any(), any(), any()) } returns null
        assertEquals("llm unavailable", (service.profile("Sencha", null) as EnrichmentResult.Failure).reason)
    }

    @Test
    fun `non-JSON output is a failure`() {
        every { client.chatJson(any(), any(), any(), any()) } returns "I cannot help with that."
        assertEquals("invalid json", (service.profile("Sencha", null) as EnrichmentResult.Failure).reason)
    }

    @Test
    fun `a profile missing a dimension is rejected`() {
        val tenDims = FlavorDimension.entries.drop(1).joinToString(",") {
            """"${it.name}":{"value":1,"confidence":0.7,"evidence":false}"""
        }
        val json = """{"names":{"display_ru":"Т","original":"T","pinyin":"T"},"type":"GREEN",""" +
            """"dimensions":{$tenDims},"short_blurb_ru":"x","overall_confidence":0.5}"""
        every { client.chatJson(any(), any(), any(), any()) } returns json

        assertEquals("incomplete dimensions", (service.profile("Sencha", null) as EnrichmentResult.Failure).reason)
    }
}
