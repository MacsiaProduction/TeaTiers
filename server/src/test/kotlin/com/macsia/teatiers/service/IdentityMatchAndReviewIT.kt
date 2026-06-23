package com.macsia.teatiers.service

import com.macsia.teatiers.AbstractIntegrationTest
import com.macsia.teatiers.domain.MatchDecision
import com.macsia.teatiers.domain.Tea
import com.macsia.teatiers.domain.TeaIdentityAlias
import com.macsia.teatiers.domain.TeaName
import com.macsia.teatiers.domain.TeaType
import com.macsia.teatiers.dto.FetchEvidence
import com.macsia.teatiers.dto.RobotsEvidence
import com.macsia.teatiers.dto.ScrapedFacts
import com.macsia.teatiers.dto.ScrapedName
import com.macsia.teatiers.dto.SourceObservation
import com.macsia.teatiers.repository.MatchCandidateRepository
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
import kotlin.test.assertFailsWith
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

    @Autowired lateinit var matchCandidateRepository: MatchCandidateRepository

    @Autowired lateinit var provenanceRepository: TeaFieldProvenanceRepository

    @Autowired lateinit var aliasRepository: TeaIdentityAliasRepository

    @Autowired lateinit var legacyIdMap: TeaLegacyIdMapRepository

    private var runId: Long = 0

    private fun eligibleSite() {
        siteService.register("artoftea", "Art of Tea", "https://artoftea.ru", licenseDefault = "facts-only")
        siteService.setAllowedHosts("artoftea", listOf("artoftea.ru"))
        siteService.signOffTerms("artoftea", "owner@teatiers")
        siteService.setActive("artoftea", true)
        // A real (non-dry) robots-allowed run so approval can write the catalog (decision #137-C4).
        val robots = RobotsEvidence(
            "allow", "https://artoftea.ru/robots.txt", "TeaTiers/test", Instant.now().minusSeconds(60), 200, "robots-hash",
        )
        runId = requireNotNull(importService.startRun("artoftea", "op", "tool-1", "artoftea-1", robots, dryRun = false).id)
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
            evidence = FetchEvidence(contentHash = "c".repeat(64), httpStatus = 200, contentType = "text/html"),
        )
        val record = importService.ingest(runId, obs)
        return matchService.proposeFor(requireNotNull(record.id), runId)
    }

    /** Two-phase #137-C4 materialization: seal ingestion, mark the run reviewed, and apply it. */
    private fun sealAndApply() = run {
        importService.closeIngestion(runId)
        reviewService.markReviewed(runId)
        reviewService.applyRun(runId)
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

        reviewService.approveNew(requireNotNull(decision.id), "operator")
        val teaId = assertNotNull(sealAndApply().results.single().teaId)
        val tea = teaRepository.findById(teaId).orElseThrow()

        assertEquals("scrape", tea.source)
        assertEquals("unverified", tea.verificationStatus, "a scrape can NEVER be verified")
        assertEquals("active", tea.status)
        // C6: provenance is value-bearing -- a selected claim carries the actual value, not just a field name.
        val originClaim = provenanceRepository.findByTeaId(teaId).firstOrNull { it.fieldName == "origin_country" && it.selected }
        assertEquals("CN", assertNotNull(originClaim).claimedValue, "provenance records the claimed value")
        assertTrue(provenanceRepository.findByTeaId(teaId).any { it.fieldName == "name:ru" })
        // C6: approving the identity PROMOTES the scraped names to human-confirmed aliases (reused as Tier 0).
        val aliases = aliasRepository.findByTeaId(teaId)
        assertTrue(
            aliases.isNotEmpty() && aliases.all { it.verified && it.origin == "human_confirmed" },
            "approved scraped names become human-confirmed verified aliases",
        )
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
        sealAndApply()
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
            evidence = FetchEvidence(contentHash = "d".repeat(64), httpStatus = 200, contentType = "text/html"),
        )
        val record = importService.ingest(runId, obs)
        matchService.proposeFor(requireNotNull(record.id), runId)
        matchService.proposeFor(record.id!!, runId)
        assertEquals(1, matchDecisionRepository.findBySourceRecordId(record.id!!).count { it.decision == "pending" })
    }

    @Test
    fun `an applied (linked) record's decision cannot be re-approved to mint a duplicate`() {
        eligibleSite()
        val decision = stageAndPropose(listOf(ScrapedName("en", "Gaba Cha", true)))
        reviewService.approveNew(requireNotNull(decision.id), "operator")
        assertNotNull(sealAndApply().results.single().teaId) // record now linked, run 'applied'
        // Re-proposing the now-linked record returns the SAME approved decision; it cannot be re-approved.
        val again = matchService.proposeFor(decision.sourceRecordId, runId)
        assertEquals(decision.id, again.id)
        assertTrue(
            runCatching { reviewService.approveNew(requireNotNull(again.id), "operator") }.isFailure,
            "a linked record's already-approved decision cannot be re-approved",
        )
    }

    @Test
    fun `a create_new colliding on dedup_key is quarantined and reported, not a crash (decision 141 H3)`() {
        eligibleSite()
        // Seed an ACTIVE tea sharing the dedup_key a scraped "Rou Gui" computes, but whose NAME the matcher misses.
        val collidingKey = DedupKeys.of("Rou Gui", "rou gui", TeaType.OOLONG)
        val seeded = Tea(type = TeaType.OOLONG, source = "curated", dedupKey = collidingKey, verificationStatus = "verified")
        seeded.addName(TeaName(locale = "en", name = "Unrelated Label", isPrimary = true))
        teaRepository.saveAndFlush(seeded)

        val decision = stageAndPropose(
            listOf(ScrapedName("en", "Rou Gui", true), ScrapedName("pinyin", "rou gui", true)),
        )
        assertEquals("create_new", decision.proposedKind)
        reviewService.approveNew(requireNotNull(decision.id), "operator") // decide is fine; the collision is a write-time fact
        // H3: the apply phase quarantines the colliding decision (a merge hint for the operator) instead of one
        // collision aborting the whole run -- the run completes and the conflict is reported.
        val applied = sealAndApply()
        assertEquals(0, applied.appliedCount)
        assertEquals(1, applied.failures.size)
        assertEquals(requireNotNull(decision.id), applied.failures.single().decisionId)
        assertTrue(applied.failures.single().reason.contains("merge"), "the report is a merge hint")
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

    @Test
    fun `re-proposing after approval returns the approved decision and orphans no pending row`() {
        eligibleSite()
        val decision = stageAndPropose(listOf(ScrapedName("en", "Orphan Test", true)))
        reviewService.approveNew(requireNotNull(decision.id), "operator")

        val again = matchService.proposeFor(decision.sourceRecordId, runId)

        assertEquals(decision.id, again.id, "returns the approved decision, not a fresh pending one")
        assertEquals(0, matchDecisionRepository.countByDecision("pending"))
    }

    @Test
    fun `merging conflicting cross-source oxidation bounds does not crash`() {
        eligibleSite()
        val teaId = seedTea(names = listOf(Triple("pinyin", "rou gui", true)))
        // Target carries only a lower bound; the scrape carries only an upper bound that inverts it.
        teaRepository.findById(teaId).orElseThrow().also { it.oxidationMin = 10; teaRepository.saveAndFlush(it) }
        aliasService.addAuthoritative(teaId, "ru", "Жоу Гуй", romanizationSystem = "palladius")

        val obs = SourceObservation(
            sourceSiteCode = "artoftea",
            canonicalUrl = "https://artoftea.ru/ox",
            externalId = "EX-OX",
            retrievedAt = Instant.parse("2026-06-21T10:00:00Z"),
            parserVersion = "artoftea-1",
            facts = ScrapedFacts(names = listOf(ScrapedName("ru", "Жоу Гуй", true)), type = "OOLONG", oxidationMax = 5),
            evidence = FetchEvidence(contentHash = "e".repeat(64), httpStatus = 200, contentType = "text/html"),
        )
        val record = importService.ingest(runId, obs)
        val decision = matchService.proposeFor(requireNotNull(record.id), runId)
        assertEquals("authoritative", decision.matchTier)

        reviewService.approveMerge(requireNotNull(decision.id), "operator")
        sealAndApply() // must not throw on the CHECK
        val tea = teaRepository.findById(teaId).orElseThrow()
        assertEquals(10.toShort(), tea.oxidationMin)
        assertEquals(null, tea.oxidationMax, "conflicting cross-source oxidation bounds are left unmerged")
    }

    @Test
    fun `create_new records brand only as a non-selected proposal, never on the canonical tea (decision 139-R4)`() {
        eligibleSite()
        val decision = stageAndPropose(listOf(ScrapedName("en", "Vendor Rou Gui", true)), brand = "Some Vendor")
        assertEquals("create_new", decision.proposedKind)
        reviewService.approveNew(requireNotNull(decision.id), "operator")
        val teaId = assertNotNull(sealAndApply().results.single().teaId)

        val tea = teaRepository.findById(teaId).orElseThrow()
        assertNull(tea.brand, "brand is never auto-written to the canonical tea from identity approval")
        val brandClaims = provenanceRepository.findByTeaId(teaId).filter { it.fieldName == "brand" }
        assertTrue(brandClaims.isNotEmpty() && brandClaims.none { it.selected }, "brand is a non-selected proposal claim")
        assertEquals("Some Vendor", brandClaims.first().claimedValue)
    }

    @Test
    fun `the matcher ignores a retracted tea and proposes create_new (active-only, decision 141)`() {
        eligibleSite()
        val teaId = seedTea(names = listOf(Triple("en", "Solo Retracted", true)))
        teaRepository.findById(teaId).orElseThrow().also { it.status = "retracted"; teaRepository.saveAndFlush(it) }

        val decision = stageAndPropose(listOf(ScrapedName("en", "Solo Retracted", true)))
        assertEquals("none", decision.matchTier, "a retracted tea is not an active match candidate")
        assertEquals("create_new", decision.proposedKind)
        assertNull(decision.candidateTeaId)
    }

    @Test
    fun `two active teas sharing a name yield a conflict with a ranked candidate set (decision 141 FND-P1-1)`() {
        eligibleSite()
        val a = seedTea(names = listOf(Triple("en", "Long Jing", true)))
        val b = seedTea(names = listOf(Triple("en", "Long Jing", true)))

        val decision = stageAndPropose(listOf(ScrapedName("en", "Long Jing", true)))
        assertEquals("exact", decision.matchTier)
        assertEquals("conflict", decision.proposedKind, "two distinct active owners must not auto-attach to one")
        assertTrue(decision.candidateTeaId in setOf(a, b), "a default target is offered, but the operator must choose")

        // The ranked candidate set is surfaced to the operator so they can pick an explicit merge target.
        val pending = reviewService.pending().first { it.decisionId == decision.id }
        assertEquals(setOf(a, b), pending.candidates.map { it.teaId }.toSet())
        assertEquals(listOf(0, 1), pending.candidates.map { it.rank }, "candidates are ranked, best first")
        assertTrue(pending.candidates.all { it.matchTier == "exact" })
    }

    @Test
    fun `an authoritative alias cannot be assigned to a second active tea, but may move after the first retracts (decision 141)`() {
        val a = seedTea(names = listOf(Triple("pinyin", "alias owner a", true)))
        val b = seedTea(names = listOf(Triple("pinyin", "alias owner b", true)))
        aliasService.addAuthoritative(a, "ru", "Дубль")

        assertFailsWith<DuplicateAuthoritativeAliasException> { aliasService.addAuthoritative(b, "ru", "Дубль") }

        // Once the first owner is retracted, the identity is free to move to an active tea.
        teaRepository.findById(a).orElseThrow().also { it.status = "retracted"; teaRepository.saveAndFlush(it) }
        val moved = aliasService.addAuthoritative(b, "ru", "Дубль")
        assertEquals(b, moved.teaId)
    }

    @Test
    fun `an accent or case variant of an authoritative alias collides with the same identity (SRV-P1-1)`() {
        val a = seedTea(names = listOf(Triple("pinyin", "accent owner a", true)))
        val b = seedTea(names = listOf(Triple("pinyin", "accent owner b", true)))
        aliasService.addAuthoritative(a, "en", "Café Noir")

        // "cafe noir" unaccents+lowercases to the same alias_norm as "Café Noir" -> one identity, rejected.
        // The advisory lock now keys on the SAME lower(f_unaccent(...)) form (SRV-P1-1), so a concurrent add of
        // either variant serializes on one lock rather than both passing the check.
        assertFailsWith<DuplicateAuthoritativeAliasException> { aliasService.addAuthoritative(b, "en", "cafe noir") }
    }

    @Test
    fun `re-proposing rebuilds the ranked candidate set in place without duplicating it (decision 141)`() {
        eligibleSite()
        seedTea(names = listOf(Triple("en", "Re Propose Tea", true)))
        val decision = stageAndPropose(listOf(ScrapedName("en", "Re Propose Tea", true)))
        assertEquals("exact", decision.matchTier)
        assertEquals(1, matchCandidateRepository.findByMatchDecisionIdOrderByRankAsc(requireNotNull(decision.id)).size)

        // Re-propose the same still-pending record: persistCandidates must delete+reinsert (re-point in
        // place), not stack a second rank-0 row (which would trip the (decision, rank) unique).
        matchService.proposeFor(decision.sourceRecordId, runId)
        assertEquals(1, matchCandidateRepository.findByMatchDecisionIdOrderByRankAsc(decision.id!!).size)
    }

    @Test
    fun `the duplicate-authoritative-alias repair report finds an existing cross-tea violation (decision 141)`() {
        val a = seedTea(names = listOf(Triple("pinyin", "dup a", true)))
        val b = seedTea(names = listOf(Triple("pinyin", "dup b", true)))
        // Insert the duplicate directly (bypassing the addAuthoritative guard) to simulate a pre-invariant DB.
        listOf(a, b).forEach {
            aliasRepository.save(TeaIdentityAlias(teaId = it, locale = "ru", alias = "Повтор", origin = "curated", verified = true))
        }
        aliasRepository.flush()

        val report = aliasRepository.findDuplicateActiveAuthoritativeAliases()
        assertTrue(report.any { it.teaCount == 2L }, "the repair report surfaces the (locale, alias) owned by two active teas")
    }
}
