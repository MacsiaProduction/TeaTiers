package com.macsia.teatiers.service

import com.macsia.teatiers.AbstractIntegrationTest
import com.macsia.teatiers.dto.FetchEvidence
import com.macsia.teatiers.dto.RobotsEvidence
import com.macsia.teatiers.dto.ScrapedFacts
import com.macsia.teatiers.dto.ScrapedName
import com.macsia.teatiers.dto.SourceObservation
import com.macsia.teatiers.repository.MatchDecisionRepository
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.jdbc.core.JdbcTemplate
import java.time.Instant
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * FND-P1-3: a pending review decision must be consumed EXACTLY ONCE even under concurrency. Two operators
 * (two CLI processes = two transactions) racing approve on the same pending decision must not both succeed.
 *
 * Deliberately NOT @Transactional: each [ReviewService.approveNew] call opens its own transaction (as a
 * separate process would), so the pessimistic row lock in `pendingDecision` is actually exercised. Setup
 * commits a pending decision; the two threads then contend; one wins, the other is rejected. The committed
 * rows are cleaned up in [cleanup] so the shared container DB stays pristine for other test classes.
 */
class ReviewDecisionConcurrencyIT : AbstractIntegrationTest() {

    @Autowired lateinit var siteService: SourceSiteService

    @Autowired lateinit var importService: CatalogImportService

    @Autowired lateinit var matchService: IdentityMatchService

    @Autowired lateinit var reviewService: ReviewService

    @Autowired lateinit var matchDecisionRepository: MatchDecisionRepository

    @Autowired lateinit var jdbc: JdbcTemplate

    private val code = "cas-concurrency"
    private var runId: Long = 0

    @Test
    fun `two operators racing approve on one pending decision consume it exactly once`() {
        val decisionId = stageCommittedPendingDecision()

        // Two threads hit approveNew at the same instant; the loser must be rejected, never a second approval.
        val barrier = CyclicBarrier(2)
        val outcomes = ConcurrentLinkedQueue<Result<String>>()
        val pool = Executors.newFixedThreadPool(2)
        try {
            repeat(2) { i ->
                pool.submit {
                    barrier.await(10, TimeUnit.SECONDS)
                    outcomes += runCatching { reviewService.approveNew(decisionId, "op-$i").decision }
                }
            }
            pool.shutdown()
            assertTrue(pool.awaitTermination(30, TimeUnit.SECONDS), "both review attempts finished")
        } finally {
            pool.shutdownNow()
        }

        val successes = outcomes.count { it.isSuccess }
        val failures = outcomes.count { it.isFailure }
        assertEquals(1, successes, "exactly one operator may consume the pending decision")
        assertEquals(1, failures, "the other operator must be rejected, not silently win too")
        assertTrue(
            outcomes.first { it.isFailure }.exceptionOrNull() is IllegalArgumentException,
            "the loser is rejected because the decision is no longer pending",
        )
        assertEquals("approved_new", matchDecisionRepository.findById(decisionId).orElseThrow().decision)
    }

    /** Register an eligible source, stage one observation, and propose -> a COMMITTED pending decision id. */
    private fun stageCommittedPendingDecision(): Long {
        siteService.register(code, "CAS", "https://$code.example")
        siteService.setAllowedHosts(code, listOf("$code.example"))
        siteService.signOffTerms(code, "owner@teatiers")
        siteService.setActive(code, true)
        val robots = RobotsEvidence(
            "allow", "https://$code.example/robots.txt", "TeaTiers/test", Instant.now().minusSeconds(60), 200, "robots-hash",
        )
        runId = requireNotNull(importService.startRun(code, "op", "t", "p-1", robots, dryRun = false).id)
        val obs = SourceObservation(
            sourceSiteCode = code,
            canonicalUrl = "https://$code.example/x",
            externalId = "EX-1",
            retrievedAt = Instant.now().minusSeconds(60),
            parserVersion = "p-1",
            facts = ScrapedFacts(names = listOf(ScrapedName("en", "Cas Tea", true)), type = "OOLONG"),
            evidence = FetchEvidence(contentHash = "a".repeat(64), httpStatus = 200, contentType = "text/html"),
        )
        val record = importService.ingest(runId, obs)
        return requireNotNull(matchService.proposeFor(requireNotNull(record.id), runId).id)
    }

    @AfterTest
    fun cleanup() {
        // Delete the committed rows in FK-safe order (the circular source_record <-> revision ref needs the
        // current-revision pointer nulled first). Scoped to this run/site so other classes are untouched.
        jdbc.update("DELETE FROM match_decision WHERE import_run_id = ?", runId)
        jdbc.update("UPDATE source_record SET current_revision_id = NULL WHERE import_run_id = ?", runId)
        jdbc.update(
            "DELETE FROM normalized_candidate WHERE source_record_id IN (SELECT id FROM source_record WHERE import_run_id = ?)",
            runId,
        )
        jdbc.update("DELETE FROM source_record_revision WHERE import_run_id = ?", runId)
        jdbc.update("DELETE FROM source_record WHERE import_run_id = ?", runId)
        jdbc.update("DELETE FROM raw_evidence WHERE import_run_id = ?", runId)
        jdbc.update("DELETE FROM import_run WHERE id = ?", runId)
        jdbc.update("DELETE FROM source_site WHERE code = ?", code)
    }
}
