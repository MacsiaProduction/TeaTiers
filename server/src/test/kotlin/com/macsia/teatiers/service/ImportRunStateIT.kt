package com.macsia.teatiers.service

import com.macsia.teatiers.AbstractIntegrationTest
import com.macsia.teatiers.dto.FetchEvidence
import com.macsia.teatiers.dto.RobotsEvidence
import com.macsia.teatiers.dto.ScrapedFacts
import com.macsia.teatiers.dto.ScrapedName
import com.macsia.teatiers.dto.SourceObservation
import com.macsia.teatiers.repository.ImportRunRepository
import com.macsia.teatiers.repository.MatchDecisionRepository
import com.macsia.teatiers.repository.RawEvidenceRepository
import com.macsia.teatiers.repository.SourceRecordRepository
import com.macsia.teatiers.repository.SourceRecordRevisionRepository
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.transaction.annotation.Transactional
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull

/**
 * Decision #137-C4 / refresh ING-P0-1: the import run is the unit of apply-authorization. Robots must
 * 'allow' before a run starts; ingest is bound to the run's site/parser/state; and -- the keystone -- the
 * catalog may be written ONLY from a run that has been sealed (ingestion stopped) and reviewed (every
 * decision resolved). A dry / ingesting / unreviewed run can never publish.
 *
 * Each test makes at most ONE throwing @Transactional call after its writes, so a doomed (rollback-only)
 * inner transaction never collides with later DB work in the same rolled-back test transaction.
 */
@Transactional
class ImportRunStateIT : AbstractIntegrationTest() {

    @Autowired lateinit var siteService: SourceSiteService

    @Autowired lateinit var importService: CatalogImportService

    @Autowired lateinit var matchService: IdentityMatchService

    @Autowired lateinit var reviewService: ReviewService

    @Autowired lateinit var sourceRecordRepository: SourceRecordRepository

    @Autowired lateinit var importRunRepository: ImportRunRepository

    @Autowired lateinit var revisionRepository: SourceRecordRevisionRepository

    @Autowired lateinit var rawEvidenceRepository: RawEvidenceRepository

    @Autowired lateinit var matchDecisionRepository: MatchDecisionRepository

    // Fresh per-test so the run's robots snapshot passes the #139-R3 freshness window.
    private val fetchedAt = Instant.now().minusSeconds(60)

    private fun eligible(code: String) {
        siteService.register(code, "Site $code", "https://$code.example")
        siteService.setAllowedHosts(code, listOf("$code.example")) // before sign-off so invalidation is a no-op
        siteService.signOffTerms(code, "owner@teatiers")
        siteService.setActive(code, true)
    }

    private fun allow() =
        RobotsEvidence("allow", "https://a.example/robots.txt", "TeaTiers/test", fetchedAt, 200, "robots-hash")

    private fun obs(code: String, parser: String = "p-1", url: String = "https://$code.example/x") =
        obsWithId(code, "EX-1", url, parser)

    private fun obsWithId(code: String, externalId: String, url: String, parser: String = "p-1") =
        SourceObservation(
            sourceSiteCode = code,
            canonicalUrl = url,
            externalId = externalId,
            retrievedAt = fetchedAt,
            parserVersion = parser,
            facts = ScrapedFacts(names = listOf(ScrapedName("en", "Some Tea", true)), type = "OOLONG"),
            evidence = FetchEvidence(contentHash = "a".repeat(64), httpStatus = 200, contentType = "text/html"),
        )

    @Test
    fun `a run fails closed when robots does not allow`() {
        eligible("a")
        for (decision in listOf("disallow", "fail_closed", "not_checked")) {
            assertFailsWith<RobotsGateException> {
                importService.startRun(
                    "a", "op", "t", "p-1",
                    RobotsEvidence(decision, "https://a.example/robots.txt", "TeaTiers/test", fetchedAt),
                )
            }
        }
    }

    @Test
    fun `a fresh run starts at preflight_allowed and the first observation moves it to ingesting`() {
        eligible("a")
        val run = importService.startRun("a", "op", "t", "p-1", allow())
        assertEquals(ImportRunState.PREFLIGHT_ALLOWED.code, run.status)
        importService.ingest(requireNotNull(run.id), obs("a"))
        val reread = importRunRepository.findById(run.id!!).orElseThrow()
        assertEquals(ImportRunState.INGESTING.code, reread.status, "the first observation advances the run to ingesting")
    }

    @Test
    fun `ingest rejects an observation whose site does not match the run`() {
        eligible("a")
        eligible("b")
        val run = importService.startRun("a", "op", "t", "p-1", allow())
        assertFailsWith<RunStateException> { importService.ingest(requireNotNull(run.id), obs("b")) }
    }

    @Test
    fun `ingest rejects a parser-version mismatch`() {
        eligible("a")
        val run = importService.startRun("a", "op", "t", "p-1", allow())
        assertFailsWith<RunStateException> { importService.ingest(requireNotNull(run.id), obs("a", parser = "p-2")) }
    }

    @Test
    fun `ingest rejects a sealed (awaiting_review) run`() {
        eligible("a")
        val run = importService.startRun("a", "op", "t", "p-1", allow())
        importService.ingest(requireNotNull(run.id), obs("a"))
        importService.closeIngestion(run.id!!)
        assertFailsWith<RunStateException> { importService.ingest(run.id!!, obs("a", url = "https://a.example/y")) }
    }

    @Test
    fun `ingest rejects a missing run`() {
        eligible("a") // the site gate passes; the run lookup is what must fail
        assertFailsWith<RunStateException> { importService.ingest(9_999_999L, obs("a")) }
    }

    @Test
    fun `failRun rejects a missing run`() {
        assertFailsWith<RunStateException> { importService.failRun(9_999_999L) }
    }

    @Test
    fun `a terminal run cannot be re-finished (decision 137-C4 immutable terminals)`() {
        eligible("a")
        val run = importService.startRun("a", "op", "t", "p-1", allow())
        importService.failRun(requireNotNull(run.id))
        assertFailsWith<RunStateException> { importService.blockRun(run.id!!) }
    }

    @Test
    fun `a robots snapshot that is allow but incomplete is rejected (decision 139-R3)`() {
        eligible("a")
        val incomplete = RobotsEvidence("allow", "https://a.example/robots.txt", "TeaTiers/test", fetchedAt) // no 2xx/hash
        assertFailsWith<RobotsGateException> { importService.startRun("a", "op", "t", "p-1", incomplete) }
    }

    @Test
    fun `a stale robots snapshot is rejected (decision 139-R3)`() {
        eligible("a")
        val stale = RobotsEvidence(
            "allow", "https://a.example/robots.txt", "TeaTiers/test", Instant.now().minusSeconds(7200), 200, "robots-hash",
        )
        assertFailsWith<RobotsGateException> { importService.startRun("a", "op", "t", "p-1", stale) }
    }

    @Test
    fun `a second active run for the same source is rejected (decision 137-C4)`() {
        eligible("a")
        importService.startRun("a", "op", "t", "p-1", allow())
        assertFailsWith<RunStateException> { importService.startRun("a", "op", "t", "p-1", allow()) }
    }

    @Test
    fun `re-registering a source with a material change invalidates its approval (decision 139-R3)`() {
        eligible("a") // register + sign-off + activate
        // A changed base URL is a material change -> approval cleared, site deactivated.
        siteService.register("a", "Site a", "https://a-renamed.example")
        assertFailsWith<ImportGateException> { importService.startRun("a", "op", "t", "p-1", allow()) }
    }

    @Test
    fun `changing an approved source's allowed_hosts invalidates its approval (decision 141)`() {
        eligible("a") // approved with allowlist [a.example]
        // Widening the fetch allowlist is a material change -> approval cleared, site deactivated.
        siteService.setAllowedHosts("a", listOf("a.example", "cdn.a.example"))
        assertFailsWith<ImportGateException> { importService.startRun("a", "op", "t", "p-1", allow()) }
    }

    @Test
    fun `markReviewed refuses a run that still has a pending decision (decision 137-C4 completeness)`() {
        eligible("a")
        val run = importService.startRun("a", "op", "t", "p-1", allow(), dryRun = false)
        val record = importService.ingest(requireNotNull(run.id), obs("a"))
        matchService.proposeFor(requireNotNull(record.id), run.id) // a pending decision the operator never resolved
        importService.closeIngestion(run.id!!)
        assertFailsWith<RunStateException> { reviewService.markReviewed(run.id!!) }
    }

    @Test
    fun `markReviewed refuses a run with an ingested-but-never-proposed record (decision 137-C4 completeness)`() {
        eligible("a")
        val run = importService.startRun("a", "op", "t", "p-1", allow(), dryRun = false)
        // Two DISTINCT records ingested; only one is proposed + decided. The other has NO decision at all --
        // it must still block review, otherwise a half-proposed run would publish as though fully reviewed.
        val r1 = importService.ingest(requireNotNull(run.id), obsWithId("a", "EX-1", "https://a.example/1"))
        importService.ingest(run.id!!, obsWithId("a", "EX-2", "https://a.example/2"))
        val d1 = matchService.proposeFor(requireNotNull(r1.id), run.id)
        reviewService.approveNew(requireNotNull(d1.id), "op")
        importService.closeIngestion(run.id!!)
        assertFailsWith<RunStateException> { reviewService.markReviewed(run.id!!) }
    }

    @Test
    fun `apply is rejected before the run is sealed and reviewed (the keystone gate)`() {
        eligible("a")
        val run = importService.startRun("a", "op", "t", "p-1", allow(), dryRun = false)
        val record = importService.ingest(requireNotNull(run.id), obs("a"))
        val decision = matchService.proposeFor(requireNotNull(record.id), run.id)
        reviewService.approveNew(requireNotNull(decision.id), "op") // a decision exists, but the run is still 'ingesting'
        // Applying an ingesting (never-sealed, never-reviewed) run is impossible: the state machine forbids it.
        assertFailsWith<RunStateException> { reviewService.applyRun(run.id!!) }
    }

    @Test
    fun `the full lifecycle seals, reviews, and applies an approved decision to the catalog`() {
        eligible("a")
        val run = importService.startRun("a", "op", "t", "p-1", allow(), dryRun = false)
        val record = importService.ingest(requireNotNull(run.id), obs("a"))
        val decision = matchService.proposeFor(requireNotNull(record.id), run.id)
        reviewService.approveNew(requireNotNull(decision.id), "op")

        importService.closeIngestion(run.id!!)
        reviewService.markReviewed(run.id!!)
        val applied = reviewService.applyRun(run.id!!)

        assertEquals(1, applied.appliedCount, "the approved decision is materialized")
        val teaId = assertNotNull(applied.results.single().teaId)
        val linked = sourceRecordRepository.findById(requireNotNull(record.id)).orElseThrow()
        assertEquals("linked", linked.status)
        assertEquals(teaId, linked.teaId)
    }

    @Test
    fun `ingest rejects an observation whose host is off the source allowlist (decision 141 SSRF)`() {
        eligible("a") // allowlist = [a.example]
        val run = importService.startRun("a", "op", "t", "p-1", allow(), dryRun = false)
        assertFailsWith<UrlSafetyException> {
            importService.ingest(requireNotNull(run.id), obsWithId("a", "EX-9", "https://evil.example/x"))
        }
    }

    @Test
    fun `ingest rejects a non-2xx fetch envelope (decision 141 evidence)`() {
        eligible("a")
        val run = importService.startRun("a", "op", "t", "p-1", allow(), dryRun = false)
        val bad = obsWithId("a", "EX-9", "https://a.example/x")
            .copy(evidence = FetchEvidence(contentHash = "a".repeat(64), httpStatus = 404))
        assertFailsWith<FetchEvidenceException> { importService.ingest(requireNotNull(run.id), bad) }
    }

    @Test
    fun `startRun rejects a robots url whose host is off the allowlist (decision 141)`() {
        eligible("a") // allowlist = [a.example]
        assertFailsWith<UrlSafetyException> {
            importService.startRun(
                "a", "op", "t", "p-1",
                RobotsEvidence("allow", "https://evil.example/robots.txt", "TeaTiers/test", fetchedAt, 200, "robots-hash"),
            )
        }
    }

    @Test
    fun `ingest rejects a fetch envelope with a non-sha256 body hash (decision 141)`() {
        eligible("a")
        val run = importService.startRun("a", "op", "t", "p-1", allow(), dryRun = false)
        val bad = obsWithId("a", "EX-9", "https://a.example/x")
            .copy(evidence = FetchEvidence(contentHash = "not-a-hash", httpStatus = 200))
        assertFailsWith<FetchEvidenceException> { importService.ingest(requireNotNull(run.id), bad) }
    }

    @Test
    fun `apply fails closed when the bound evidence belongs to a different run (decision 141)`() {
        eligible("a")
        eligible("b")
        val run = importService.startRun("a", "op", "t", "p-1", allow(), dryRun = false)
        val record = importService.ingest(requireNotNull(run.id), obsWithId("a", "EX-9", "https://a.example/x"))
        val decision = matchService.proposeFor(requireNotNull(record.id), run.id)
        reviewService.approveNew(requireNotNull(decision.id), "op")
        importService.closeIngestion(run.id!!)
        reviewService.markReviewed(run.id!!)
        // A second REAL run, so the foreign id satisfies the raw_evidence FK; then re-point the envelope at it.
        val run2 = importService.startRun(
            "b", "op", "t", "p-1",
            RobotsEvidence("allow", "https://b.example/robots.txt", "TeaTiers/test", fetchedAt, 200, "robots-hash"),
            dryRun = false,
        )
        val revision = revisionRepository.findById(requireNotNull(record.currentRevisionId)).orElseThrow()
        val evidence = rawEvidenceRepository.findById(revision.rawEvidenceId).orElseThrow()
        evidence.importRunId = requireNotNull(run2.id)
        rawEvidenceRepository.saveAndFlush(evidence)

        assertFailsWith<CanonicalApplyForbiddenException> { reviewService.applyRun(run.id!!) }
    }

    @Test
    fun `apply fails closed when the bound evidence has a blank body hash (decision 141)`() {
        eligible("a")
        val run = importService.startRun("a", "op", "t", "p-1", allow(), dryRun = false)
        val record = importService.ingest(requireNotNull(run.id), obsWithId("a", "EX-9", "https://a.example/x"))
        val decision = matchService.proposeFor(requireNotNull(record.id), run.id)
        reviewService.approveNew(requireNotNull(decision.id), "op")
        importService.closeIngestion(run.id!!)
        reviewService.markReviewed(run.id!!)
        val revision = revisionRepository.findById(requireNotNull(record.currentRevisionId)).orElseThrow()
        val evidence = rawEvidenceRepository.findById(revision.rawEvidenceId).orElseThrow()
        evidence.contentHash = ""
        rawEvidenceRepository.saveAndFlush(evidence)

        assertFailsWith<CanonicalApplyForbiddenException> { reviewService.applyRun(run.id!!) }
    }

    @Test
    fun `ingest writes and binds an immutable fetch-evidence envelope to the revision (decision 141)`() {
        eligible("a")
        val run = importService.startRun("a", "op", "t", "p-1", allow(), dryRun = false)
        val record = importService.ingest(requireNotNull(run.id), obsWithId("a", "EX-9", "https://a.example/x"))

        val revision = revisionRepository.findById(requireNotNull(record.currentRevisionId)).orElseThrow()
        val evidence = rawEvidenceRepository.findById(revision.rawEvidenceId).orElseThrow()
        assertEquals("https://a.example/x", evidence.canonicalUrl)
        assertEquals(200, evidence.httpStatus)
        assertEquals(run.id, evidence.importRunId, "evidence is bound to the producing run")
        assertEquals(revision.rawEvidenceId, record.rawEvidenceId, "the record carries the denormalized pointer")
    }

    @Test
    fun `a dry run may stage, propose, and review but can never be applied`() {
        eligible("a")
        val run = importService.startRun("a", "op", "t", "p-1", allow(), dryRun = true)
        val record = importService.ingest(requireNotNull(run.id), obs("a"))
        val decision = matchService.proposeFor(requireNotNull(record.id), run.id)
        assertEquals("pending", decision.decision, "a dry run still stages + proposes for review")
        reviewService.approveNew(requireNotNull(decision.id), "op")
        importService.closeIngestion(run.id!!)
        reviewService.markReviewed(run.id!!)

        // ...but the apply phase must refuse to write the catalog from a dry-run record.
        assertFailsWith<CanonicalApplyForbiddenException> { reviewService.applyRun(run.id!!) }
    }

    @Test
    fun `apply refuses a record whose decision was re-pointed to a run for a different site`() {
        eligible("a")
        eligible("b")
        // A record staged + approved under site a; its decision points at run a.
        val runA = importService.startRun("a", "op", "t", "p-1", allow(), dryRun = false)
        val record = importService.ingest(requireNotNull(runA.id), obsWithId("a", "EX-9", "https://a.example/x"))
        val decision = matchService.proposeFor(requireNotNull(record.id), runA.id)
        reviewService.approveNew(requireNotNull(decision.id), "op")
        importService.closeIngestion(runA.id!!)
        reviewService.markReviewed(runA.id!!)
        // A separate REAL, reviewed run for a DIFFERENT site b.
        val runB = importService.startRun(
            "b", "op", "t", "p-1",
            RobotsEvidence("allow", "https://b.example/robots.txt", "TeaTiers/test", fetchedAt, 200, "robots-hash"),
            dryRun = false,
        )
        importService.closeIngestion(runB.id!!)
        reviewService.markReviewed(runB.id!!)
        // Re-point the site-a decision at run b (import_run_id has no site check) and apply run b: the apply
        // gate must refuse to write a site-a record via a site-b run.
        val repointed = matchDecisionRepository.findById(decision.id!!).orElseThrow()
        repointed.importRunId = requireNotNull(runB.id)
        matchDecisionRepository.saveAndFlush(repointed)

        assertFailsWith<CanonicalApplyForbiddenException> { reviewService.applyRun(runB.id!!) }
    }
}
