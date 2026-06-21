package com.macsia.teatiers.service

import com.macsia.teatiers.AbstractIntegrationTest
import com.macsia.teatiers.domain.Tea
import com.macsia.teatiers.domain.TeaName
import com.macsia.teatiers.domain.TeaType
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

    private val fetchedAt = Instant.parse("2026-06-21T09:00:00Z")
    private var runId: Long = 0

    private fun startRun() {
        siteService.register("s", "S", "https://s.example")
        siteService.signOffTerms("s", "owner@teatiers")
        siteService.setActive("s", true)
        runId = requireNotNull(
            importService.startRun("s", "op", "t", "p-1", RobotsEvidence("allow", fetchedAt), dryRun = false).id,
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
    )

    private fun seedTea(pinyin: String): Long {
        val tea = Tea(type = TeaType.OOLONG, source = "curated", dedupKey = "seed-${UUID.randomUUID()}", verificationStatus = "verified")
        tea.addName(TeaName(locale = "pinyin", name = pinyin, isPrimary = true))
        return requireNotNull(teaRepository.saveAndFlush(tea).id)
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
    fun `a correction after approval re-enters review and records a conflict claim`() {
        startRun()
        val r1 = importService.ingest(runId, obs("https://s.example/a", externalId = "A", region = "Wuyi"))
        val d1 = matchService.proposeFor(requireNotNull(r1.id), runId)
        val teaId = assertNotNull(reviewService.approveNew(requireNotNull(d1.id), "op").teaId)

        // The source corrects region; a NEW revision re-enters review even though it was already approved.
        val r2 = importService.ingest(runId, obs("https://s.example/a", externalId = "A", region = "Wuyishan"))
        assertEquals("reparse_pending", r2.status)
        val d2 = matchService.proposeFor(r2.id!!, runId)
        assertTrue(d2.id != d1.id, "a changed revision opens a fresh decision, not the approved one")
        assertEquals(1, matchDecisionRepository.countByDecision("pending"))

        // Approving the correction into the same tea succeeds and records the changed value as a conflict
        // claim (region already had a value, so it is kept, not overwritten).
        reviewService.approveMerge(requireNotNull(d2.id), "op", targetTeaId = teaId)
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

        val tea = teaRepository.findById(teaId).orElseThrow()
        assertEquals("Fujian", tea.region, "the null field was filled by the merge")
        val claim = provenanceRepository.findByTeaId(teaId).firstOrNull { it.fieldName == "region" && it.selected }
        assertEquals("Fujian", assertNotNull(claim).claimedValue, "the filled field has a selected value-bearing claim")
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
        // The matcher still proposes the (retracted) target -- candidate-set status filtering is deferred
        // P1 (FND-P1-1) -- so the apply-time guard is the backstop that must refuse it.
        assertEquals("authoritative", decision.matchTier)
        assertFailsWith<InactiveMergeTargetException> {
            reviewService.approveMerge(requireNotNull(decision.id), "op")
        }
    }
}
