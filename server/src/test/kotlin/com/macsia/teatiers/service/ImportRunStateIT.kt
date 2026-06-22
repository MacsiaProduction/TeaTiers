package com.macsia.teatiers.service

import com.macsia.teatiers.AbstractIntegrationTest
import com.macsia.teatiers.dto.RobotsEvidence
import com.macsia.teatiers.dto.ScrapedFacts
import com.macsia.teatiers.dto.ScrapedName
import com.macsia.teatiers.dto.SourceObservation
import com.macsia.teatiers.repository.ImportRunRepository
import com.macsia.teatiers.repository.SourceRecordRepository
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

    // Fresh per-test so the run's robots snapshot passes the #139-R3 freshness window.
    private val fetchedAt = Instant.now().minusSeconds(60)

    private fun eligible(code: String) {
        siteService.register(code, "Site $code", "https://$code.example")
        siteService.signOffTerms(code, "owner@teatiers")
        siteService.setActive(code, true)
    }

    private fun allow() =
        RobotsEvidence("allow", "https://a.example/robots.txt", "TeaTiers/test", fetchedAt, 200, "robots-hash")

    private fun obs(code: String, parser: String = "p-1", url: String = "https://$code.example/x") =
        SourceObservation(
            sourceSiteCode = code,
            canonicalUrl = url,
            externalId = "EX-1",
            retrievedAt = fetchedAt,
            parserVersion = parser,
            facts = ScrapedFacts(names = listOf(ScrapedName("en", "Some Tea", true)), type = "OOLONG"),
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
    fun `markReviewed refuses a run that still has a pending decision (decision 137-C4 completeness)`() {
        eligible("a")
        val run = importService.startRun("a", "op", "t", "p-1", allow(), dryRun = false)
        val record = importService.ingest(requireNotNull(run.id), obs("a"))
        matchService.proposeFor(requireNotNull(record.id), run.id) // a pending decision the operator never resolved
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
}
