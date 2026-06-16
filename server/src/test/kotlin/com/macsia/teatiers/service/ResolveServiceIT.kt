package com.macsia.teatiers.service

import com.macsia.teatiers.AbstractIntegrationTest
import com.macsia.teatiers.client.FoundationModelsClient
import com.macsia.teatiers.client.WikidataClient
import com.macsia.teatiers.client.WikidataTea
import com.macsia.teatiers.domain.Tea
import com.macsia.teatiers.domain.TeaName
import com.macsia.teatiers.domain.TeaType
import com.macsia.teatiers.dto.ResolveStatus
import com.macsia.teatiers.repository.TeaRepository
import io.mockk.every
import io.mockk.mockk
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Primary
import org.springframework.transaction.annotation.Transactional

/**
 * End-to-end resolve over the real schema: the native unaccented name lookup, the Wikidata import,
 * and idempotency on the unique dedup_key / wikidata_qid. The Wikidata client is mocked so the test
 * never hits the network; the LLM tier is mocked off so a miss creates nothing here (the enrich
 * tier has its own IT in [EnrichmentStubServiceIT]).
 */
@Transactional
class ResolveServiceIT : AbstractIntegrationTest() {

    @TestConfiguration
    class MockWikidata {
        @Bean
        @Primary
        fun wikidataClient(): WikidataClient = mockk()

        @Bean
        @Primary
        fun foundationModelsClient(): FoundationModelsClient = mockk { every { isEnabled } returns false }
    }

    @Autowired
    lateinit var service: ResolveService

    @Autowired
    lateinit var teaRepository: TeaRepository

    @Autowired
    lateinit var wikidataClient: WikidataClient

    @BeforeTest
    fun resetClient() {
        // Default to a miss; enrich tests override this.
        every { wikidataClient.findTea(any(), any()) } returns null
    }

    @Test
    fun `cache hit matches a stored name case- and accent-insensitively`() {
        val ru = save("Q-it-ru", TeaType.GREEN, "ru" to "Лунцзин", "en" to "Longjing tea")
        val accented = save("Q-it-acc", TeaType.OOLONG, "en" to "Dà Hóng Páo")

        assertEquals(ResolveStatus.MATCHED, service.resolve("ЛУНЦЗИН", "ru", null).status)
        assertEquals(ru, service.resolve("  лунцзин ", "ru", null).tea?.id)
        assertEquals(accented, service.resolve("da hong pao", "en", null).tea?.id)
    }

    @Test
    fun `fresh Wikidata match is imported once and then served from cache`() {
        every { wikidataClient.findTea("Resolve IT Sencha", any()) } returns WikidataTea(
            qid = "Q-it-new-777",
            type = TeaType.GREEN,
            originCountry = "JP",
            nameEn = "Resolve IT Sencha",
            nameRu = "Тест Сенча IT",
            nameZhHans = "测试煎茶IT",
            descriptionEn = "integration-test gloss",
        )

        val first = service.resolve("Resolve IT Sencha", "en", null)
        assertEquals(ResolveStatus.ENRICHED, first.status)
        val id = requireNotNull(first.tea?.id)
        assertEquals("Q-it-new-777", first.tea?.wikidataQid)
        assertEquals(3, first.tea?.names?.size)
        assertEquals("unverified", first.tea?.provenance?.verificationStatus)

        // Second resolve of the same name is a cache hit on the just-created row (idempotent).
        val second = service.resolve("resolve it sencha", "en", null)
        assertEquals(ResolveStatus.MATCHED, second.status)
        assertEquals(id, second.tea?.id)
        assertEquals(id, teaRepository.findByWikidataQid("Q-it-new-777")?.id)
    }

    @Test
    fun `a miss creates nothing`() {
        val before = teaRepository.count()
        assertEquals(ResolveStatus.UNRESOLVED, service.resolve("Nonexistent IT Brew 9001", null, null).status)
        assertEquals(before, teaRepository.count())
    }

    private fun save(qid: String, type: TeaType, vararg names: Pair<String, String>): Long {
        val tea = Tea(type = type, source = "curated", dedupKey = "$qid|${type.name}", wikidataQid = qid)
        names.forEach { (locale, name) -> tea.addName(TeaName(locale = locale, name = name, isPrimary = true)) }
        return requireNotNull(teaRepository.saveAndFlush(tea).id)
    }
}
