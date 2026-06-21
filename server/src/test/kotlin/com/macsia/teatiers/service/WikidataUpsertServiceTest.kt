package com.macsia.teatiers.service

import com.macsia.teatiers.client.WikidataTea
import com.macsia.teatiers.domain.Tea
import com.macsia.teatiers.domain.TeaType
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class WikidataUpsertServiceTest {

    private val repository = mockk<com.macsia.teatiers.repository.TeaRepository>()
    private val legacyIdMap = mockk<com.macsia.teatiers.repository.TeaLegacyIdMapRepository>(relaxed = true)
    private val service = WikidataUpsertService(repository, legacyIdMap)

    private val longjing = WikidataTea(
        qid = "Q1069130",
        type = TeaType.GREEN,
        originCountry = "CN",
        nameEn = "Longjing tea",
        nameRu = "Лунцзин",
        nameZhHans = "龙井茶",
        descriptionEn = "pan-roasted green tea",
    )

    @Test
    fun `createOrGet inserts a new unverified CC0 row with all locale names`() {
        every { repository.findByWikidataQid("Q1069130") } returns null
        every { repository.findByDedupKey(any()) } returns null
        val saved = slot<Tea>()
        every { repository.saveAndFlush(capture(saved)) } answers { saved.captured.also { it.id = 42L } }

        val result = service.createOrGet(longjing)

        assertTrue(result.created)
        assertEquals(42L, result.id)
        val tea = saved.captured
        assertEquals(TeaType.GREEN, tea.type)
        assertEquals("wikidata", tea.source)
        assertEquals("Q1069130", tea.wikidataQid)
        assertEquals("CN", tea.originCountry)
        assertEquals("CC0-1.0", tea.license)
        assertEquals("unverified", tea.verificationStatus)
        assertEquals(0.9f, tea.confidence)
        assertEquals("https://www.wikidata.org/wiki/Q1069130", tea.sourceUrl)
        assertEquals(setOf("en", "ru", "zh-Hans"), tea.names.map { it.name to it.locale }.map { it.second }.toSet())
        assertTrue(tea.names.all { it.isPrimary })
        assertEquals("pan-roasted green tea", tea.descriptions.single().shortText)
    }

    @Test
    fun `createOrGet returns the existing row on a Wikidata-QID hit without inserting`() {
        every { repository.findByWikidataQid("Q1069130") } returns Tea(
            type = TeaType.GREEN, source = "curated", dedupKey = "k",
        ).also { it.id = 7L }

        val result = service.createOrGet(longjing)

        assertFalse(result.created)
        assertEquals(7L, result.id)
        verify(exactly = 0) { repository.saveAndFlush(any()) }
    }

    @Test
    fun `createOrGet returns the existing row on a dedup-key hit without inserting`() {
        every { repository.findByWikidataQid("Q1069130") } returns null
        every { repository.findByDedupKey(DedupKeys.of("Longjing tea", null, TeaType.GREEN)) } returns Tea(
            type = TeaType.GREEN, source = "curated", dedupKey = "k",
        ).also { it.id = 9L }

        val result = service.createOrGet(longjing)

        assertFalse(result.created)
        assertEquals(9L, result.id)
        verify(exactly = 0) { repository.saveAndFlush(any()) }
    }
}
