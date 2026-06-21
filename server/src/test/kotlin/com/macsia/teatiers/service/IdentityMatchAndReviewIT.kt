package com.macsia.teatiers.service

import com.macsia.teatiers.AbstractIntegrationTest
import com.macsia.teatiers.domain.MatchDecision
import com.macsia.teatiers.domain.Tea
import com.macsia.teatiers.domain.TeaName
import com.macsia.teatiers.domain.TeaType
import com.macsia.teatiers.dto.ScrapedFacts
import com.macsia.teatiers.dto.ScrapedName
import com.macsia.teatiers.dto.SourceObservation
import com.macsia.teatiers.repository.MatchDecisionRepository
import com.macsia.teatiers.repository.SourceRecordRepository
import com.macsia.teatiers.repository.TeaFieldProvenanceRepository
import com.macsia.teatiers.repository.TeaIdentityAliasRepository
import com.macsia.teatiers.repository.TeaLegacyIdMapRepository
import com.macsia.teatiers.repository.TeaRepository
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.transaction.annotation.Transactional
import java.time.Instant
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * PR5: the cross-script matcher + the human-approved canonical write. Asserts the tier ladder (no
 * auto-merge -- every proposal is pending), and that an approval is the only path that writes the
 * catalog, always 'unverified', with per-field provenance + aliases + a legacy-id map entry.
 */
@Transactional
class IdentityMatchAndReviewIT : AbstractIntegrationTest() {

    @Autowired lateinit var siteService: SourceSiteService

    @Autowired lateinit var importService: CatalogImportService

    @Autowired lateinit var matchService: IdentityMatchService

    @Autowired lateinit var reviewService: ReviewService

    @Autowired lateinit var aliasService: IdentityAliasService

    @Autowired lateinit var teaRepository: TeaRepository

    @Autowired lateinit var sourceRecordRepository: SourceRecordRepository

    @Autowired lateinit var matchDecisionRepository: MatchDecisionRepository

    @Autowired lateinit var provenanceRepository: TeaFieldProvenanceRepository

    @Autowired lateinit var aliasRepository: TeaIdentityAliasRepository

    @Autowired lateinit var legacyIdMap: TeaLegacyIdMapRepository

    private var runId: Long = 0

    private fun eligibleSite() {
        siteService.register("artoftea", "Art of Tea", "https://artoftea.ru", licenseDefault = "facts-only")
        siteService.signOffTerms("artoftea", "owner@teatiers")
        siteService.setActive("artoftea", true)
        runId = requireNotNull(importService.startRun("artoftea", "op", "tool-1", "artoftea-1").id)
    }

    private fun seedTea(
        type: TeaType = TeaType.OOLONG,
        brand: String? = null,
        names: List<Triple<String, String, Boolean>>,
    ): Long {
        val tea = Tea(
            type = type,
            source = "curated",
            dedupKey = "seed-${UUID.randomUUID()}",
            brand = brand,
            verificationStatus = "verified",
        )
        names.forEach { (locale, name, primary) -> tea.addName(TeaName(locale = locale, name = name, isPrimary = primary)) }
        return requireNotNull(teaRepository.saveAndFlush(tea).id)
    }

    private fun stageAndPropose(names: List<ScrapedName>, brand: String? = null, url: String = "https://artoftea.ru/x"): MatchDecision {
        val obs = SourceObservation(
            sourceSiteCode = "artoftea",
            canonicalUrl = url,
            externalId = "EX-${UUID.randomUUID()}",
            retrievedAt = Instant.parse("2026-06-21T10:00:00Z"),
            parserVersion = "artoftea-1",
            facts = ScrapedFacts(names = names, type = "OOLONG", originCountry = "CN", brand = brand),
        )
        val record = importService.ingest(runId, obs)
        return matchService.proposeFor(requireNotNull(record.id), runId)
    }

    @Test
    fun `no catalog match proposes create_new and approval writes an unverified scrape tea`() {
        eligibleSite()
        val decision = stageAndPropose(
            listOf(
                ScrapedName("ru", "Жоу Гуй", true),
                ScrapedName("pinyin", "rou gui", true),
            ),
        )
        assertEquals("none", decision.matchTier)
        assertEquals("create_new", decision.proposedKind)
        assertEquals("pending", decision.decision)

        val result = reviewService.approveNew(requireNotNull(decision.id), "operator")
        val teaId = assertNotNull(result.teaId)
        val tea = teaRepository.findById(teaId).orElseThrow()

        assertEquals("scrape", tea.source)
        assertEquals("unverified", tea.verificationStatus, "a scrape can NEVER be verified")
        assertEquals("active", tea.status)
        // provenance + aliases + legacy map were all written
        assertTrue(provenanceRepository.findByTeaId(teaId).any { it.fieldName == "name:ru" })
        assertTrue(aliasRepository.findByTeaId(teaId).all { !it.verified }, "scraped aliases are unverified")
        assertNotNull(legacyIdMap.findById(teaId).orElse(null))
        // source record is now linked, not floating
        val record = sourceRecordRepository.findById(decision.sourceRecordId).orElseThrow()
        assertEquals("linked", record.status)
        assertEquals(teaId, record.teaId)
    }

    @Test
    fun `a curated authoritative alias is a tier-0 hit and merge folds in the scrape`() {
        eligibleSite()
        val teaId = seedTea(brand = null, names = listOf(Triple("pinyin", "da hong pao", true)))
        aliasService.addAuthoritative(teaId, "ru", "Да Хун Пао", romanizationSystem = "palladius")

        val decision = stageAndPropose(listOf(ScrapedName("ru", "Да Хун Пао", true)))

        assertEquals("authoritative", decision.matchTier)
        assertEquals("attach", decision.proposedKind)
        assertEquals(teaId, decision.candidateTeaId)

        reviewService.approveMerge(requireNotNull(decision.id), "operator")
        val tea = teaRepository.findById(teaId).orElseThrow()
        // The merge added the ru name additively and flipped the (curated) row to 'mixed'.
        assertTrue(tea.names.any { it.locale == "ru" && it.name == "Да Хун Пао" })
        assertEquals("mixed", tea.source)
    }

    @Test
    fun `an exact same-script catalog name is a tier-1 hit`() {
        eligibleSite()
        seedTea(names = listOf(Triple("en", "Long Jing", true)))
        val decision = stageAndPropose(listOf(ScrapedName("en", "Long Jing", true)))
        assertEquals("exact", decision.matchTier)
        assertEquals("attach", decision.proposedKind)
    }

    @Test
    fun `a Cyrillic name bridges to a pinyin catalog name as a transliteration hit`() {
        eligibleSite()
        // Catalog has only the pinyin name; the scrape has only the Cyrillic name -> Palladius bridge.
        seedTea(names = listOf(Triple("pinyin", "tie guan yin", true)))
        val decision = stageAndPropose(listOf(ScrapedName("ru", "Те Гуань Инь", true)))
        assertEquals("transliteration", decision.matchTier)
        assertEquals(1, matchDecisionRepository.countByDecision("pending"))
    }

    @Test
    fun `a brand conflict is flagged, never silently attached`() {
        eligibleSite()
        seedTea(brand = "Shop A", names = listOf(Triple("en", "Pu Erh Cake", true)))
        val decision = stageAndPropose(listOf(ScrapedName("en", "Pu Erh Cake", true)), brand = "Shop B")
        assertEquals("exact", decision.matchTier)
        assertEquals("conflict", decision.proposedKind, "vendor lots never auto-collapse")
    }

    @Test
    fun `reject closes a decision without writing the catalog`() {
        eligibleSite()
        val before = teaRepository.count()
        val decision = stageAndPropose(listOf(ScrapedName("en", "Nothing Here", true)))

        reviewService.reject(requireNotNull(decision.id), "operator")
        val reloaded = matchDecisionRepository.findById(decision.id!!).orElseThrow()

        assertEquals("rejected", reloaded.decision)
        assertEquals(before, teaRepository.count(), "reject must not create a tea")
        val record = sourceRecordRepository.findById(decision.sourceRecordId).orElseThrow()
        assertNull(record.teaId)
    }

    @Test
    fun `an already-decided decision cannot be approved again`() {
        eligibleSite()
        val decision = stageAndPropose(listOf(ScrapedName("en", "Bai Mu Dan", true)))
        reviewService.approveNew(requireNotNull(decision.id), "operator")
        assertTrue(
            runCatching { reviewService.approveNew(decision.id!!, "operator") }.isFailure,
            "double-approval must fail",
        )
    }

    @Test
    fun `re-proposing a source record does not stack pending decisions`() {
        eligibleSite()
        val obs = SourceObservation(
            sourceSiteCode = "artoftea",
            canonicalUrl = "https://artoftea.ru/idem",
            externalId = "EX-IDEM",
            retrievedAt = Instant.parse("2026-06-21T10:00:00Z"),
            parserVersion = "artoftea-1",
            facts = ScrapedFacts(names = listOf(ScrapedName("en", "Bai Hao Yinzhen", true)), type = "WHITE"),
        )
        val record = importService.ingest(runId, obs)
        matchService.proposeFor(requireNotNull(record.id), runId)
        matchService.proposeFor(record.id!!, runId)
        assertEquals(1, matchDecisionRepository.findBySourceRecordId(record.id!!).count { it.decision == "pending" })
    }

    @Test
    fun `approving a fresh decision for an already-linked record cannot mint a duplicate`() {
        eligibleSite()
        val decision = stageAndPropose(listOf(ScrapedName("en", "Gaba Cha", true)))
        reviewService.approveNew(requireNotNull(decision.id), "operator")
        // A stale second proposal for the now-linked record must NOT create a second canonical tea.
        val stale = matchService.proposeFor(decision.sourceRecordId, runId)
        assertTrue(runCatching { reviewService.approveNew(requireNotNull(stale.id), "operator") }.isFailure)
    }

    @Test
    fun `a create_new colliding on dedup_key surfaces a conflict, not a crash`() {
        eligibleSite()
        // Seed a tea sharing the dedup_key a scraped "Rou Gui" computes, but whose NAME the matcher misses.
        val collidingKey = DedupKeys.of("Rou Gui", "rou gui", TeaType.OOLONG)
        val seeded = Tea(type = TeaType.OOLONG, source = "curated", dedupKey = collidingKey, verificationStatus = "verified")
        seeded.addName(TeaName(locale = "en", name = "Unrelated Label", isPrimary = true))
        teaRepository.saveAndFlush(seeded)

        val decision = stageAndPropose(
            listOf(ScrapedName("en", "Rou Gui", true), ScrapedName("pinyin", "rou gui", true)),
        )
        assertEquals("create_new", decision.proposedKind)
        val outcome = runCatching { reviewService.approveNew(requireNotNull(decision.id), "operator") }
        assertTrue(outcome.exceptionOrNull() is CanonicalUpsertConflictException, "collision must surface as a merge hint")
    }

    @Test
    fun `a brand-conflict decision refuses to merge without an explicit target`() {
        eligibleSite()
        seedTea(brand = "Shop A", names = listOf(Triple("en", "Conflict Tea", true)))
        val decision = stageAndPropose(listOf(ScrapedName("en", "Conflict Tea", true)), brand = "Shop B")
        assertEquals("conflict", decision.proposedKind)
        assertTrue(
            runCatching { reviewService.approveMerge(requireNotNull(decision.id), "operator") }.isFailure,
            "a conflict must not auto-collapse into the proposed candidate",
        )
    }
}
