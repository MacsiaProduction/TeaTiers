package com.macsia.teatiers.service

import com.macsia.teatiers.client.FoundationModelsClient
import com.macsia.teatiers.client.WikidataClient
import com.macsia.teatiers.client.WikidataTea
import com.macsia.teatiers.domain.TeaType
import com.macsia.teatiers.dto.ResolveStatus
import com.macsia.teatiers.dto.TeaDetailDto
import com.macsia.teatiers.dto.TeaNameDto
import com.macsia.teatiers.dto.TeaProvenanceDto
import com.macsia.teatiers.repository.TeaRepository
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import org.springframework.dao.DataIntegrityViolationException

class ResolveServiceTest {

    private val repository = mockk<TeaRepository>()
    private val wikidataClient = mockk<WikidataClient>()
    private val upsertService = mockk<WikidataUpsertService>()
    private val catalogService = mockk<TeaCatalogService>()
    private val foundationModelsClient = mockk<FoundationModelsClient>()
    private val stubService = mockk<EnrichmentStubService>(relaxed = true)
    private val llmEnrichmentService = mockk<LlmEnrichmentService>(relaxed = true)
    // Default: budget available; the exhausted case overrides it.
    private val llmDailyBudget = mockk<DailyBudget> { every { tryAcquire() } returns true }
    private val missLogService = mockk<MissLogService>(relaxed = true)
    private val service = ResolveService(
        repository, wikidataClient, upsertService, catalogService,
        foundationModelsClient, stubService, llmEnrichmentService, llmDailyBudget, missLogService,
    )

    private val longjing = WikidataTea("Q1069130", TeaType.GREEN, "CN", "Longjing tea", "Лунцзин", "龙井茶", null)

    private fun detail(id: Long, state: String? = null) = TeaDetailDto(
        id = id, publicId = UUID.randomUUID(), status = "active", supersededByPublicId = null,
        wikidataQid = null, type = TeaType.GREEN, originCountry = "CN", region = null, harvestYear = null,
        cultivar = null, oxidationMin = null, oxidationMax = null, brand = null, image = null,
        images = emptyList(),
        names = listOf(TeaNameDto("en", "Longjing tea", true)), descriptions = emptyList(),
        flavors = emptyList(), provenance = TeaProvenanceDto("wikidata", null, "CC0-1.0", "unverified", 0.9f),
        enrichmentState = state,
    )

    @Test
    fun `cache hit returns MATCHED without touching Wikidata`() {
        every { repository.findIdByNormalizedName("Лунцзин") } returns 5L
        every { catalogService.detail(5L) } returns detail(5L)

        val response = service.resolve("  Лунцзин ", "ru", null)

        assertEquals(ResolveStatus.MATCHED, response.status)
        assertEquals(5L, response.tea?.id)
        verify(exactly = 0) { wikidataClient.findTea(any(), any()) }
        // A hit is not a miss — nothing goes to the miss log.
        verify(exactly = 0) { missLogService.record(any()) }
    }

    @Test
    fun `cache hit on a PENDING stub reports ENRICHING and does not re-dispatch`() {
        every { repository.findIdByNormalizedName(any()) } returns 7L
        every { catalogService.detail(7L) } returns detail(7L, state = "PENDING")

        val response = service.resolve("Some Brew", null, null)

        assertEquals(ResolveStatus.ENRICHING, response.status)
        verify(exactly = 0) { llmEnrichmentService.enrich(any(), any(), any()) }
    }

    @Test
    fun `cache hit on a FAILED stub re-arms and retries enrichment`() {
        every { repository.findIdByNormalizedName(any()) } returns 8L
        every { catalogService.detail(8L) } returnsMany listOf(detail(8L, state = "FAILED"), detail(8L, state = "PENDING"))
        every { foundationModelsClient.isEnabled } returns true

        val response = service.resolve("Failed Brew", null, "pu erh, earthy")

        assertEquals(ResolveStatus.ENRICHING, response.status)
        verify { stubService.resetToPending(8L) }
        verify { llmEnrichmentService.enrich(8L, "Failed Brew", "pu erh, earthy") }
    }

    @Test
    fun `fresh Wikidata match is imported as ENRICHED`() {
        every { repository.findIdByNormalizedName(any()) } returns null
        every { wikidataClient.findTea("Longjing", "en") } returns longjing
        every { upsertService.createOrGet(longjing) } returns WikidataUpsertService.CreateResult(42L, created = true)
        every { catalogService.detail(42L) } returns detail(42L)

        val response = service.resolve("Longjing", "en", null)

        assertEquals(ResolveStatus.ENRICHED, response.status)
        assertEquals(42L, response.tea?.id)
    }

    @Test
    fun `Wikidata miss with the LLM tier disabled returns UNRESOLVED`() {
        every { repository.findIdByNormalizedName(any()) } returns null
        every { wikidataClient.findTea(any(), any()) } returns null
        every { foundationModelsClient.isEnabled } returns false

        val response = service.resolve("Totally Unknown Brew", null, null)

        assertEquals(ResolveStatus.UNRESOLVED, response.status)
        assertEquals(null, response.tea)
        verify(exactly = 0) { llmEnrichmentService.enrich(any(), any(), any()) }
        // A genuine miss is logged (trimmed name) for demand-driven curation (#116).
        verify { missLogService.record("Totally Unknown Brew") }
    }

    @Test
    fun `Wikidata miss with the LLM tier enabled creates a PENDING stub and dispatches`() {
        every { repository.findIdByNormalizedName(any()) } returns null
        every { wikidataClient.findTea(any(), any()) } returns null
        every { foundationModelsClient.isEnabled } returns true
        every { stubService.createOrGetStub("Unknown Brew", null) } returns
            EnrichmentStubService.StubResult(99L, created = true, state = "PENDING")
        every { catalogService.detail(99L) } returns detail(99L, state = "PENDING")

        val response = service.resolve("Unknown Brew", null, null)

        assertEquals(ResolveStatus.ENRICHING, response.status)
        assertEquals("PENDING", response.tea?.enrichmentState)
        verify { llmEnrichmentService.enrich(99L, "Unknown Brew", null) }
    }

    @Test
    fun `Wikidata miss with the daily LLM budget exhausted fails closed to UNRESOLVED`() {
        every { repository.findIdByNormalizedName(any()) } returns null
        every { wikidataClient.findTea(any(), any()) } returns null
        every { foundationModelsClient.isEnabled } returns true
        every { llmDailyBudget.tryAcquire() } returns false

        val response = service.resolve("Budget Capped Brew", null, null)

        assertEquals(ResolveStatus.UNRESOLVED, response.status)
        assertEquals(null, response.tea)
        // Fails closed before any cost is incurred: no stub row, no LLM dispatch.
        verify(exactly = 0) { stubService.createOrGetStub(any(), any()) }
        verify(exactly = 0) { llmEnrichmentService.enrich(any(), any(), any()) }
        // Still a real miss from the user's view → logged (#116).
        verify { missLogService.record("Budget Capped Brew") }
    }

    @Test
    fun `a lost insert race recovers by re-reading the row`() {
        every { repository.findIdByNormalizedName(any()) } returns null
        every { wikidataClient.findTea(any(), any()) } returns longjing
        every { upsertService.createOrGet(longjing) } throws DataIntegrityViolationException("dup")
        every { upsertService.findExisting(longjing) } returns 50L
        every { catalogService.detail(50L) } returns detail(50L)

        val response = service.resolve("Longjing", null, null)

        assertEquals(ResolveStatus.MATCHED, response.status)
        assertEquals(50L, response.tea?.id)
    }

    @Test
    fun `a lost stub-insert race is treated as a cache hit`() {
        every { repository.findIdByNormalizedName("New Brew") } returnsMany listOf(null, 60L)
        every { wikidataClient.findTea(any(), any()) } returns null
        every { foundationModelsClient.isEnabled } returns true
        every { stubService.createOrGetStub("New Brew", null) } throws DataIntegrityViolationException("dup")
        every { catalogService.detail(60L) } returns detail(60L, state = "PENDING")

        val response = service.resolve("New Brew", null, null)

        assertEquals(ResolveStatus.ENRICHING, response.status)
        assertEquals(60L, response.tea?.id)
    }
}
