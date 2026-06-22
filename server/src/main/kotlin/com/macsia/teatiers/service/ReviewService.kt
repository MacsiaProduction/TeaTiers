package com.macsia.teatiers.service

import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.macsia.teatiers.domain.ImportRun
import com.macsia.teatiers.domain.MatchDecision
import com.macsia.teatiers.domain.SourceRecord
import com.macsia.teatiers.domain.SourceRecordRevision
import com.macsia.teatiers.dto.PendingMatchDto
import com.macsia.teatiers.dto.ReviewResultDto
import com.macsia.teatiers.dto.RunApplyResultDto
import com.macsia.teatiers.dto.ScrapedFacts
import com.macsia.teatiers.repository.MatchDecisionRepository
import com.macsia.teatiers.repository.SourceRecordRepository
import com.macsia.teatiers.repository.SourceRecordRevisionRepository
import org.springframework.data.domain.Limit
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Instant

/**
 * The operator review queue (decision #136 + #137-C4 two-phase). Nothing auto-merges: the matcher proposes,
 * an operator DECIDES each item (approve-merge into an existing tea, approve-new, or reject) -- which records
 * intent only, no catalog write. The run is then sealed ([markReviewed]) and [applyRun] materializes the
 * approved decisions into the catalog. Apply is the ONLY path that writes a canonical tea, always
 * 'unverified', and only from a reviewed/apply-authorized run.
 */
@Service
class ReviewService(
    private val matchDecisionRepository: MatchDecisionRepository,
    private val sourceRecordRepository: SourceRecordRepository,
    private val revisionRepository: SourceRecordRevisionRepository,
    private val canonicalUpsertService: CanonicalUpsertService,
    private val catalogService: TeaCatalogService,
    private val importRunStateMachine: ImportRunStateMachine,
) {

    private val factsMapper = jacksonObjectMapper()
        .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)

    @Transactional(readOnly = true)
    fun pending(limit: Int = DEFAULT_LIMIT): List<PendingMatchDto> =
        matchDecisionRepository
            .findByDecisionOrderByCreatedAtAsc("pending", Limit.of(limit.coerceIn(1, MAX_LIMIT)))
            .map { it.toPendingDto() }

    @Transactional(readOnly = true)
    fun pendingCount(): Long = matchDecisionRepository.countByDecision("pending")

    /**
     * DECIDE: approve creating a brand-new canonical tea from the reviewed revision. Records intent only --
     * no catalog write (that happens in [applyRun]). Rejects a stale decision up front (decision #137-C5).
     */
    @Transactional
    fun approveNew(decisionId: Long, reviewer: String): ReviewResultDto {
        val decision = pendingDecision(decisionId)
        reviewedRevision(decision, record(decision)) // fail closed early if the content changed since review
        finishReview(decision, "approved_new", reviewer)
        return ReviewResultDto(decisionId, "approved_new", null)
    }

    /**
     * DECIDE: approve merging the reviewed revision into an existing tea (operator may override the proposed
     * target). Records intent + the chosen target only; the merge itself happens in [applyRun].
     */
    @Transactional
    fun approveMerge(decisionId: Long, reviewer: String, targetTeaId: Long? = null): ReviewResultDto {
        val decision = pendingDecision(decisionId)
        // A brand/vendor conflict never auto-collapses into the proposed candidate: force an explicit
        // target so the operator consciously chooses to fold a differently-branded lot in.
        require(decision.proposedKind != "conflict" || targetTeaId != null) {
            "decision $decisionId is a brand/vendor conflict; pass an explicit targetTeaId to merge"
        }
        val target = targetTeaId ?: decision.candidateTeaId
            ?: throw IllegalArgumentException("decision $decisionId has no merge target")
        reviewedRevision(decision, record(decision)) // fail closed early if the content changed since review
        decision.candidateTeaId = target // persist the chosen target so the apply phase merges into it
        finishReview(decision, "approved_merge", reviewer)
        return ReviewResultDto(decisionId, "approved_merge", null)
    }

    @Transactional
    fun reject(decisionId: Long, reviewer: String): ReviewResultDto {
        val decision = pendingDecision(decisionId)
        finishReview(decision, "rejected", reviewer)
        return ReviewResultDto(decisionId, "rejected", null)
    }

    /**
     * Seal review (decision #137-C4): a run can be applied ONLY once EVERY record it staged has a terminal
     * review decision for its current revision -- not just "no pending decision", but "no record left
     * undecided", so a record that was ingested but never proposed also blocks. Moves the run
     * awaiting_review -> reviewed. This is the completeness gate -- you cannot publish a half-reviewed run.
     */
    @Transactional
    fun markReviewed(runId: Long): ImportRun {
        val unreviewed = sourceRecordRepository.countUnreviewedRecords(runId)
        if (unreviewed > 0) {
            throw RunStateException(
                "import run $runId has $unreviewed record(s) without a terminal review decision; review them before applying",
            )
        }
        return importRunStateMachine.transition(runId, ImportRunState.REVIEWED)
    }

    /**
     * APPLY: materialize a fully-reviewed run into the canonical catalog (decision #137-C4) -- the ONLY path
     * that writes teas. The run must be 'reviewed'; it moves to 'applying' for the writes then 'applied' once
     * they commit. Each approved decision is applied for the EXACT revision it reviewed (re-checked here, so a
     * post-review revision can never publish), and already-linked records are skipped (idempotent). Atomic:
     * any failure rolls the whole apply back, leaving the run 'reviewed' to retry.
     */
    @Transactional
    fun applyRun(runId: Long, reviewer: String = "system:apply"): RunApplyResultDto {
        importRunStateMachine.transition(runId, ImportRunState.APPLYING)
        val approved = matchDecisionRepository.findByImportRunIdAndDecisionIn(runId, APPROVED)
        val results = mutableListOf<ReviewResultDto>()
        var skipped = 0
        for (decision in approved) {
            val record = record(decision)
            val who = decision.reviewer ?: reviewer
            val teaId = when (decision.decision) {
                "approved_new" -> {
                    // A create-new whose record is already linked would mint a duplicate -> skip (idempotent).
                    if (record.teaId != null) { skipped++; continue }
                    canonicalUpsertService.applyApprovedNew(record, reviewedRevision(decision, record), decision.id, who)
                }
                // A merge may target an already-linked record (a correction in a later run) -- never skipped.
                "approved_merge" -> canonicalUpsertService.applyApprovedMerge(
                    record, reviewedRevision(decision, record), decision.id, who,
                    decision.candidateTeaId ?: throw IllegalStateException("approved_merge ${decision.id} has no target"),
                )
                else -> continue
            }
            results += ReviewResultDto(requireNotNull(decision.id), decision.decision, teaId)
        }
        importRunStateMachine.transition(runId, ImportRunState.APPLIED)
        return RunApplyResultDto(runId, results.size, skipped, results)
    }

    private fun finishReview(decision: MatchDecision, outcome: String, reviewer: String) {
        decision.decision = outcome
        decision.reviewer = reviewer
        decision.decidedAt = Instant.now()
        matchDecisionRepository.save(decision)
    }

    private fun MatchDecision.toPendingDto(): PendingMatchDto {
        val record = sourceRecordRepository.findById(sourceRecordId).orElse(null)
        val facts = record?.let { runCatching { factsMapper.readValue(it.rawFacts, ScrapedFacts::class.java) }.getOrNull() }
        return PendingMatchDto(
            decisionId = requireNotNull(id),
            sourceRecordId = sourceRecordId,
            canonicalUrl = record?.canonicalUrl ?: "",
            matchTier = matchTier,
            proposedKind = proposedKind,
            matchScore = matchScore,
            candidateTeaId = candidateTeaId,
            names = facts?.names ?: emptyList(),
            candidate = candidateTeaId?.let { catalogService.summary(it) },
        )
    }

    private fun pendingDecision(decisionId: Long): MatchDecision {
        val decision = matchDecisionRepository.findById(decisionId)
            .orElseThrow { IllegalArgumentException("no match decision $decisionId") }
        require(decision.decision == "pending") { "decision $decisionId already ${decision.decision}" }
        return decision
    }

    private fun record(decision: MatchDecision): SourceRecord =
        sourceRecordRepository.findById(decision.sourceRecordId)
            .orElseThrow { IllegalStateException("source_record ${decision.sourceRecordId} missing") }

    /**
     * The exact revision this decision reviewed -- rejecting a STALE approval (decision #137-C5): if the
     * record got a newer revision since the operator looked, the decision no longer reflects current
     * content and must not publish the old value. Re-propose to get a fresh decision for the new revision.
     */
    private fun reviewedRevision(decision: MatchDecision, record: SourceRecord): SourceRecordRevision {
        val reviewed = decision.sourceRecordRevisionId
        if (reviewed == null || reviewed != record.currentRevisionId) {
            throw StaleDecisionException(
                "decision ${decision.id} reviewed revision $reviewed but the record's current revision is " +
                    "${record.currentRevisionId}; re-propose to review the new content",
            )
        }
        return revisionRepository.findById(reviewed)
            .orElseThrow { IllegalStateException("source_record_revision $reviewed missing") }
    }

    private companion object {
        const val DEFAULT_LIMIT = 50
        const val MAX_LIMIT = 200

        /** Review-terminal outcomes the apply phase materializes. */
        val APPROVED = setOf("approved_new", "approved_merge")
    }
}

/** Approval came after the source content changed (decision #137-C5) -- the reviewed revision is stale. */
class StaleDecisionException(message: String) : RuntimeException(message)
