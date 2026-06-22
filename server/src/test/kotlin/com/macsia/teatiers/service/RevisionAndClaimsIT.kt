package com.macsia.teatiers.service

import com.macsia.teatiers.AbstractIntegrationTest
import com.macsia.teatiers.domain.Tea
import com.macsia.teatiers.domain.TeaName
import com.macsia.teatiers.domain.TeaType
import com.macsia.teatiers.dto.FetchEvidence
import com.macsia.teatiers.dto.RobotsEvidence
import com.macsia.teatiers.dto.ScrapedFacts
import com.macsia.teatiers.dto.ScrapedName
import com.macsia.teatiers.dto.SourceObservation
import com.macsia.teatiers.repository.MatchDecisionRepository
import com.macsia.teatiers.repository.SourceRecordRepository
import com.macsia.teatiers.repository.SourceRecordRevisionRepository
import com.macsia.teatiers.repository.SourceRecordUrlHistoryRepository
import com.macsia.teatiers.repository.TeaFieldProvenanceRepository
import com.macsia.teatiers.repository.TeaRepository
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.transaction.annotation.Transactional
import java.time.Instant
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Decision #137-C5 (immutable observation revisions, correction flow, stale-approval rejection,
 * source-identity reconciliation) and #137-C6 (value-bearing field claims + selection/conflict).
 */
@Transactional
class RevisionAndClaimsIT : AbstractIntegrationTest() {

    @Autowired lateinit var siteService: SourceSiteService

    @Autowired lateinit var importService: CatalogImportService

    @Autowired lateinit var matchService: IdentityMatchService

    @Autowired lateinit var reviewService: ReviewService

    @Autowired lateinit var aliasService: IdentityAliasService

    @Autowired lateinit var sourceRecordRepository: SourceRecordRepository

    @Autowired lateinit var revisionRepository: SourceRecordRevisionRepository

    @Autowired lateinit var matchDecisionRepository: MatchDecisionRepository

    @Autowired lateinit var provenanceRepository: TeaFieldProvenanceRepository

    @Autowired lateinit var urlHistoryRepository: SourceRecordUrlHistoryRepository

    @Autowired lateinit var teaRepository: TeaRepository

    // Fresh per-test so the run's robots snapshot passes the #139-R3 freshness window.
    private val fetchedAt = Instant.now().minusSeconds(60)
    private var runId: Long = 0

    private fun allowRobots() =
        RobotsEvidence("allow", "https://s.example/robots.txt", "TeaTiers/test", fetchedAt, 200, "robots-hash")

    private fun startRun() {
        siteService.register("s", "S", "https://s.example")
        siteService.setAllowedHosts("s", listOf("s.example"))
        siteService.signOffTerms("s", "owner@teatiers")
        siteService.setActive("s", true)
        runId = requireNotNull(
            importService.startRun("s", "op", "t", "p-1", allowRobots(), dryRun = false).id,
        )
    }

    private fun obs(
        url: String,
        externalId: String? = null,
        names: List<ScrapedName> = listOf(ScrapedName("en", "Sen Cha", true)),
        region: String? = null,
    ) = SourceObservation(
        sourceSiteCode = "s",
        canonicalUrl = url,
        externalId = externalId,
        retrievedAt = fetchedAt,
        parserVersion = "p-1",
        facts = ScrapedFacts(names = names, type = "OOLONG", originCountry = "CN", region = region),
        evidence = FetchEvidence(contentHash = "b".repeat(64), httpStatus = 200, contentType = "text/html"),
    )

    private fun seedTea(pinyin: String): Long {
        val tea = Tea(type = TeaType.OOLONG, source = "curated", dedupKey = "seed-${UUID.randomUUID()}", verificationStatus = "verified")
        tea.addName(TeaName(locale = "pinyin", name = pinyin, isPrimary = true))
        return requireNotNull(teaRepository.saveAndFlush(tea).id)
    }

    /** Two-phase #137-C4 materialization for the run [id]: seal ingestion, mark reviewed, apply. */
    private fun sealAndApply(id: Long = runId) = run {
        importService.closeIngestion(id)
        reviewService.markReviewed(id)
        reviewService.applyRun(id)
    }

    // ---- C5: revisions ----

    @Test
    fun `ingest creates an immutable current revision and identical re-import reuses it`() {
        startRun()
        val record = importService.ingest(runId, obs("https://s.example/a", externalId = "A"))
        val rev1 = assertNotNull(record.currentRevisionId)
        assertEquals(1, revisionRepository.findAll().count { it.sourceRecordId == record.id })

        val again = importService.ingest(runId, obs("https://s.example/a", externalId = "A"))
        assertEquals(rev1, again.currentRevisionId, "identical facts reuse the same revision")
        assertEquals(1, revisionRepository.findAll().count { it.sourceRecordId == record.id }, "no new revision")
        assertEquals("parsed", again.status, "unchanged re-import is a no-op")
    }

    @Test
    fun `a correction in a later run re-enters review and records a conflict claim`() {
        startRun()
        val r1 = importService.ingest(runId, obs("https://s.example/a", externalId = "A", region = "Wuyi"))
        val d1 = matchService.proposeFor(requireNotNull(r1.id), runId)
        reviewService.approveNew(requireNotNull(d1.id), "op")
        val teaId = assertNotNull(sealAndApply().results.single().teaId) // run 1 -> applied; region = Wuyi (selected)

        // A LATER run re-scrapes the same record with a corrected region -> a new immutable revision that
        // re-enters review even though the record was already approved (decision #137-C5).
        val run2 = requireNotNull(importService.startRun("s", "op", "t", "p-1", allowRobots(), dryRun = false).id)
        val r2 = importService.ingest(run2, obs("https://s.example/a", externalId = "A", region = "Wuyishan"))
        assertEquals("reparse_pending", r2.status)
        val d2 = matchService.proposeFor(r2.id!!, run2)
        assertTrue(d2.id != d1.id, "a changed revision opens a fresh decision, not the approved one")
        assertEquals(1, matchDecisionRepository.countByImportRunIdAndDecision(run2, "pending"))

        // Approving the correction into the same tea records the changed value as a conflict claim (region
        // already had a value, so it is kept, not overwritten).
        reviewService.approveMerge(requireNotNull(d2.id), "op", targetTeaId = teaId)
        sealAndApply(run2)
        val tea = teaRepository.findById(teaId).orElseThrow()
        assertEquals("Wuyi", tea.region, "the existing value is never overwritten by a correction")
        val conflict = provenanceRepository.findByTeaId(teaId)
            .firstOrNull { it.fieldName == "region" && !it.selected && it.claimedValue == "Wuyishan" }
        assertNotNull(conflict, "the losing value is kept as a non-selected conflict claim")
    }

    @Test
    fun `approving a decision whose content changed since review is rejected as stale`() {
        startRun()
        val r1 = importService.ingest(runId, obs("https://s.example/a", externalId = "A", region = "Wuyi"))
        val d1 = matchService.proposeFor(requireNotNull(r1.id), runId)
        // A new revision arrives but the operator approves the OLD decision without re-proposing.
        importService.ingest(runId, obs("https://s.example/a", externalId = "A", region = "Wuyishan"))
        assertFailsWith<StaleDecisionException> { reviewService.approveNew(requireNotNull(d1.id), "op") }
    }

    // ---- C5.6: source-identity reconciliation ----

    @Test
    fun `a slug rename reconciles the canonical url and archives the old one`() {
        startRun()
        val first = importService.ingest(runId, obs("https://s.example/old", externalId = "A"))
        val renamed = importService.ingest(runId, obs("https://s.example/new", externalId = "A"))

        assertEquals(first.id, renamed.id, "same external id -> same record across a rename")
        assertEquals("https://s.example/new", renamed.canonicalUrl)
        assertTrue(
            urlHistoryRepository.findBySourceRecordId(requireNotNull(renamed.id)).any { it.canonicalUrl == "https://s.example/old" },
            "the old url is archived",
        )
    }

    @Test
    fun `a newly discovered external id attaches to a url-only record`() {
        startRun()
        val first = importService.ingest(runId, obs("https://s.example/c", externalId = null))
        val withId = importService.ingest(runId, obs("https://s.example/c", externalId = "C"))
        assertEquals(first.id, withId.id)
        assertEquals("C", withId.externalId)
    }

    @Test
    fun `an identity collision is surfaced, not silently overwritten`() {
        startRun()
        importService.ingest(runId, obs("https://s.example/d", externalId = "Z"))
        importService.ingest(runId, obs("https://s.example/e", externalId = "W"))
        // External id Z -> record1, but its 'renamed' url /e already belongs to record2: a hard conflict.
        assertFailsWith<SourceIdentityConflictException> {
            importService.ingest(runId, obs("https://s.example/e", externalId = "Z"))
        }
    }

    // ---- C6: value-bearing claims on merge ----

    @Test
    fun `merge fills a null field and records a selected value-bearing claim`() {
        startRun()
        val teaId = seedTea("sen cha")
        aliasService.addAuthoritative(teaId, "ru", "Сэн Ча", romanizationSystem = "palladius")

        val record = importService.ingest(
            runId,
            obs("https://s.example/m", externalId = "M", names = listOf(ScrapedName("ru", "Сэн Ча", true)), region = "Fujian"),
        )
        val decision = matchService.proposeFor(requireNotNull(record.id), runId)
        assertEquals("authoritative", decision.matchTier)
        reviewService.approveMerge(requireNotNull(decision.id), "op")
        sealAndApply()

        val tea = teaRepository.findById(teaId).orElseThrow()
        assertEquals("Fujian", tea.region, "the null field was filled by the merge")
        val claim = provenanceRepository.findByTeaId(teaId).firstOrNull { it.fieldName == "region" && it.selected }
        assertEquals("Fujian", assertNotNull(claim).claimedValue, "the filled field has a selected value-bearing claim")
    }

    @Test
    fun `merge records a corroboration claim when an independent source agrees (decision 139-R4)`() {
        startRun()
        val teaId = seedTea("sen cha")
        teaRepository.findById(teaId).orElseThrow().also { it.originCountry = "CN"; teaRepository.saveAndFlush(it) }
        aliasService.addAuthoritative(teaId, "ru", "Сэн Ча", romanizationSystem = "palladius")

        // obs() carries originCountry = "CN", which AGREES with the existing value.
        val record = importService.ingest(
            runId,
            obs("https://s.example/c1", externalId = "C1", names = listOf(ScrapedName("ru", "Сэн Ча", true))),
        )
        val decision = matchService.proposeFor(requireNotNull(record.id), runId)
        reviewService.approveMerge(requireNotNull(decision.id), "op")
        sealAndApply()

        val tea = teaRepository.findById(teaId).orElseThrow()
        assertEquals("CN", tea.originCountry, "the existing value is unchanged")
        val originClaims = provenanceRepository.findByTeaId(teaId).filter { it.fieldName == "origin_country" }
        assertTrue(
            originClaims.any { !it.selected && it.claimedValue == "CN" },
            "an independent agreeing source is kept as a corroboration claim, not dropped",
        )
    }

    @Test
    fun `apply re-guards the exact revision and rejects facts that fail semantic validation (decision 141)`() {
        startRun()
        val record = importService.ingest(runId, obs("https://s.example/g", externalId = "G"))
        val decision = matchService.proposeFor(requireNotNull(record.id), runId)
        reviewService.approveNew(requireNotNull(decision.id), "op")
        // Tamper the reviewed revision's stored facts to carry an unknown type (bypassing ingest's gate) to
        // prove the apply-time re-guard is the backstop -- mirrors the retracted-target tombstone test below.
        val revision = revisionRepository.findById(requireNotNull(record.currentRevisionId)).orElseThrow()
        revision.rawFacts = """{"names":[{"locale":"en","value":"Sen Cha","isPrimary":true}],"type":"PURPLE"}"""
        revisionRepository.saveAndFlush(revision)
        assertFailsWith<FactsValidationException> { sealAndApply() }
    }

    @Test
    fun `approving a merge into a retracted target is rejected (tombstone guard, decision 137-C3)`() {
        startRun()
        val teaId = seedTea("sen cha")
        aliasService.addAuthoritative(teaId, "ru", "Сэн Ча", romanizationSystem = "palladius")
        // Tombstone the tea AFTER the alias exists.
        teaRepository.findById(teaId).orElseThrow().also { it.status = "retracted"; teaRepository.saveAndFlush(it) }

        val record = importService.ingest(
            runId,
            obs("https://s.example/t", externalId = "T", names = listOf(ScrapedName("ru", "Сэн Ча", true))),
        )
        val decision = matchService.proposeFor(requireNotNull(record.id), runId)
        // Active-only matching (decision #141 / FND-P1-1) means the matcher no longer proposes the retracted
        // tea -- it sees no active match and proposes create_new. The operator then OVERRIDES with an explicit
        // (retracted) target, and the apply-time tombstone guard is the backstop that must still refuse it.
        assertEquals("create_new", decision.proposedKind)
        reviewService.approveMerge(requireNotNull(decision.id), "op", targetTeaId = teaId)
        assertFailsWith<InactiveMergeTargetException> { sealAndApply() }
    }
}
