package com.macsia.teatiers.service

import com.macsia.teatiers.AbstractIntegrationTest
import com.macsia.teatiers.dto.ScrapedFacts
import com.macsia.teatiers.dto.ScrapedName
import com.macsia.teatiers.dto.SourceObservation
import com.macsia.teatiers.repository.NormalizedCandidateRepository
import com.macsia.teatiers.repository.SourceRecordRepository
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.transaction.annotation.Transactional
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull

/**
 * PR4: the staging importer. Asserts the ToS/active gate, the facts-only boundary, and that re-import is
 * idempotent on the source record (NOT the canonical tea), re-queuing only on a real facts change.
 */
@Transactional
class CatalogImportServiceIT : AbstractIntegrationTest() {

    @Autowired lateinit var siteService: SourceSiteService

    @Autowired lateinit var importService: CatalogImportService

    @Autowired lateinit var sourceRecordRepository: SourceRecordRepository

    @Autowired lateinit var candidateRepository: NormalizedCandidateRepository

    private fun eligibleSite(code: String = "artoftea") {
        siteService.register(code, "Art of Tea", "https://artoftea.ru", termsUrl = "https://artoftea.ru/privacy")
        siteService.signOffTerms(code, "owner@teatiers")
        siteService.setActive(code, true)
    }

    private fun observation(
        code: String = "artoftea",
        url: String = "https://artoftea.ru/puer/da-hong-pao",
        externalId: String? = "DHP-2007",
        region: String? = "Wuyi",
    ) = SourceObservation(
        sourceSiteCode = code,
        canonicalUrl = url,
        externalId = externalId,
        retrievedAt = Instant.parse("2026-06-21T10:00:00Z"),
        parserVersion = "artoftea-1",
        facts = ScrapedFacts(
            names = listOf(
                ScrapedName("ru", "Да Хун Пао", true),
                ScrapedName("zh-Hans", "大红袍", true),
                ScrapedName("pinyin", "da hong pao", true),
            ),
            type = "OOLONG",
            originCountry = "CN",
            region = region,
            brand = "Art of Tea",
        ),
    )

    @Test
    fun `a gated source cannot start a run or ingest`() {
        // Registered but neither signed-off nor active.
        siteService.register("blocked", "Blocked", "https://blocked.example")
        assertFailsWith<ImportGateException> {
            importService.startRun("blocked", "op", "tool-1", "p-1")
        }
    }

    @Test
    fun `an eligible source stages a parsed source record with a normalized candidate`() {
        eligibleSite()
        val run = importService.startRun("artoftea", "op", "tool-1", "artoftea-1")

        val record = importService.ingest(requireNotNull(run.id), observation())

        assertEquals("parsed", record.status)
        assertEquals("DHP-2007", record.externalId)
        val candidate = assertNotNull(candidateRepository.findBySourceRecordId(requireNotNull(record.id)))
        assertEquals("Да Хун Пао", candidate.nameRu)
        // The curated Palladius bridge proposes a pinyin candidate from the Cyrillic name.
        assertEquals("da hong pao", candidate.palladiusBridge)
    }

    @Test
    fun `re-ingesting identical facts is idempotent on the source record`() {
        eligibleSite()
        val run = importService.startRun("artoftea", "op", "tool-1", "artoftea-1")

        val first = importService.ingest(requireNotNull(run.id), observation())
        val again = importService.ingest(requireNotNull(run.id), observation())

        assertEquals(first.id, again.id, "same (site, external_id) -> one source_record")
        assertEquals(1, sourceRecordRepository.findAll().count { it.externalId == "DHP-2007" })
        assertEquals("parsed", again.status, "unchanged facts must NOT re-queue review")
    }

    @Test
    fun `changed facts re-queue the record for review`() {
        eligibleSite()
        val run = importService.startRun("artoftea", "op", "tool-1", "artoftea-1")
        val first = importService.ingest(requireNotNull(run.id), observation(region = "Wuyi"))

        val changed = importService.ingest(requireNotNull(run.id), observation(region = "Wuyishan"))

        assertEquals(first.id, changed.id)
        assertEquals("reparse_pending", changed.status, "a real facts change re-queues review")
    }

    @Test
    fun `facts-only boundary is enforced at ingest`() {
        eligibleSite()
        val run = importService.startRun("artoftea", "op", "tool-1", "artoftea-1")
        val prose = observation(region = "Богатый чай с горы Уи, ".repeat(10))
        assertFailsWith<FactsOnlyViolationException> { importService.ingest(requireNotNull(run.id), prose) }
    }

    @Test
    fun `distinct urls without external ids create distinct records`() {
        eligibleSite()
        val run = importService.startRun("artoftea", "op", "tool-1", "artoftea-1")
        val a = importService.ingest(
            requireNotNull(run.id),
            observation(url = "https://artoftea.ru/a", externalId = null),
        )
        val b = importService.ingest(
            requireNotNull(run.id),
            observation(url = "https://artoftea.ru/b", externalId = null),
        )
        assert(a.id != b.id)
    }
}
