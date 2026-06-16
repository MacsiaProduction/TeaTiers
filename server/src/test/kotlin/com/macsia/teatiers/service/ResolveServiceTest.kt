package com.macsia.teatiers.service

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
import kotlin.test.Test
import kotlin.test.assertEquals
import org.springframework.dao.DataIntegrityViolationException

class ResolveServiceTest {

    private val repository = mockk<TeaRepository>()
    private val wikidataClient = mockk<WikidataClient>()
    private val upsertService = mockk<WikidataUpsertService>()
    private val catalogService = mockk<TeaCatalogService>()
    private val service = ResolveService(repository, wikidataClient, upsertService, catalogService)

    private val longjing = WikidataTea("Q1069130", TeaType.GREEN, "CN", "Longjing tea", "Лунцзин", "龙井茶", null)

    private fun detail(id: Long) = TeaDetailDto(
        id = id, wikidataQid = null, type = TeaType.GREEN, originCountry = "CN", region = null,
        cultivar = null, oxidationMin = null, oxidationMax = null, brand = null, image = null,
        names = listOf(TeaNameDto("en", "Longjing tea", true)), descriptions = emptyList(),
        flavors = emptyList(), provenance = TeaProvenanceDto("wikidata", null, "CC0-1.0", "unverified", 0.9f),
    )

    @Test
    fun `cache hit returns MATCHED without touching Wikidata`() {
        every { repository.findIdByNormalizedName("Лунцзин") } returns 5L
        every { catalogService.detail(5L) } returns detail(5L)

        val response = service.resolve("  Лунцзин ", "ru")

        assertEquals(ResolveStatus.MATCHED, response.status)
        assertEquals(5L, response.tea?.id)
        verify(exactly = 0) { wikidataClient.findTea(any(), any()) }
    }

    @Test
    fun `fresh Wikidata match is imported as ENRICHED`() {
        every { repository.findIdByNormalizedName(any()) } returns null
        every { wikidataClient.findTea("Longjing", "en") } returns longjing
        every { upsertService.createOrGet(longjing) } returns WikidataUpsertService.CreateResult(42L, created = true)
        every { catalogService.detail(42L) } returns detail(42L)

        val response = service.resolve("Longjing", "en")

        assertEquals(ResolveStatus.ENRICHED, response.status)
        assertEquals(42L, response.tea?.id)
    }

    @Test
    fun `Wikidata hit on an already-imported tea is MATCHED`() {
        every { repository.findIdByNormalizedName(any()) } returns null
        every { wikidataClient.findTea(any(), any()) } returns longjing
        every { upsertService.createOrGet(longjing) } returns WikidataUpsertService.CreateResult(42L, created = false)
        every { catalogService.detail(42L) } returns detail(42L)

        assertEquals(ResolveStatus.MATCHED, service.resolve("Longjing", null).status)
    }

    @Test
    fun `Wikidata miss returns UNRESOLVED and creates nothing`() {
        every { repository.findIdByNormalizedName(any()) } returns null
        every { wikidataClient.findTea(any(), any()) } returns null

        val response = service.resolve("Totally Unknown Brew", null)

        assertEquals(ResolveStatus.UNRESOLVED, response.status)
        assertEquals(null, response.tea)
        verify(exactly = 0) { upsertService.createOrGet(any()) }
    }

    @Test
    fun `a match with no usable labels is treated as UNRESOLVED`() {
        every { repository.findIdByNormalizedName(any()) } returns null
        every { wikidataClient.findTea(any(), any()) } returns
            WikidataTea("Q1", TeaType.OTHER, null, null, null, null, null)

        assertEquals(ResolveStatus.UNRESOLVED, service.resolve("x", null).status)
        verify(exactly = 0) { upsertService.createOrGet(any()) }
    }

    @Test
    fun `a lost insert race recovers by re-reading the row`() {
        every { repository.findIdByNormalizedName(any()) } returns null
        every { wikidataClient.findTea(any(), any()) } returns longjing
        every { upsertService.createOrGet(longjing) } throws DataIntegrityViolationException("dup")
        every { upsertService.findExisting(longjing) } returns 50L
        every { catalogService.detail(50L) } returns detail(50L)

        val response = service.resolve("Longjing", null)

        assertEquals(ResolveStatus.MATCHED, response.status)
        assertEquals(50L, response.tea?.id)
    }
}
