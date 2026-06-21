package com.macsia.teatiers.service

import com.macsia.teatiers.AbstractIntegrationTest
import com.macsia.teatiers.dto.RobotsEvidence
import com.macsia.teatiers.dto.ScrapedFacts
import com.macsia.teatiers.dto.ScrapedName
import com.macsia.teatiers.dto.SourceObservation
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.transaction.annotation.Transactional
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/**
 * Decision #137-C4: the import run is a transaction invariant, not audit metadata. Robots must 'allow'
 * before a run starts; ingest must be bound to the run's site/parser/state; a dry/blocked/failed run can
 * never have its records applied to the public catalog; finishRun rejects unknown/missing states.
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

    private val fetchedAt = Instant.parse("2026-06-21T09:00:00Z")

    private fun eligible(code: String) {
        siteService.register(code, "Site $code", "https://$code.example")
        siteService.signOffTerms(code, "owner@teatiers")
        siteService.setActive(code, true)
    }

    private fun allow() = RobotsEvidence("allow", fetchedAt, 200, "robots-hash")

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
                importService.startRun("a", "op", "t", "p-1", RobotsEvidence(decision, fetchedAt))
            }
        }
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
    fun `ingest rejects a finished run`() {
        eligible("a")
        val run = importService.startRun("a", "op", "t", "p-1", allow())
        importService.finishRun(requireNotNull(run.id), "succeeded")
        assertFailsWith<RunStateException> { importService.ingest(run.id!!, obs("a")) }
    }

    @Test
    fun `ingest rejects a missing run`() {
        eligible("a") // the site gate passes; the run lookup is what must fail
        assertFailsWith<RunStateException> { importService.ingest(9_999_999L, obs("a")) }
    }

    @Test
    fun `finishRun rejects an unknown status`() {
        eligible("a")
        val run = importService.startRun("a", "op", "t", "p-1", allow())
        assertFailsWith<RunStateException> { importService.finishRun(requireNotNull(run.id), "done") }
    }

    @Test
    fun `finishRun rejects a missing run`() {
        assertFailsWith<RunStateException> { importService.finishRun(9_999_999L, "succeeded") }
    }

    @Test
    fun `a dry run may stage and propose but its records can never be applied`() {
        eligible("a")
        val run = importService.startRun("a", "op", "t", "p-1", allow(), dryRun = true)
        val record = importService.ingest(requireNotNull(run.id), obs("a"))
        val decision = matchService.proposeFor(requireNotNull(record.id), run.id)
        assertEquals("pending", decision.decision, "a dry run still stages + proposes for review")

        // ...but approval must refuse to write the catalog from a dry-run record.
        assertFailsWith<CanonicalApplyForbiddenException> {
            reviewService.approveNew(requireNotNull(decision.id), "operator")
        }
    }
}
