package com.macsia.teatiers.service

import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.macsia.teatiers.domain.MatchDecision
import com.macsia.teatiers.domain.SourceRecord
import com.macsia.teatiers.domain.SourceRecordRevision
import com.macsia.teatiers.dto.PendingMatchDto
import com.macsia.teatiers.dto.ReviewResultDto
import com.macsia.teatiers.dto.ScrapedFacts
import com.macsia.teatiers.repository.MatchDecisionRepository
import com.macsia.teatiers.repository.SourceRecordRepository
import com.macsia.teatiers.repository.SourceRecordRevisionRepository
import org.springframework.data.domain.Limit
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Instant

/**
 * The operator review queue (decision #136). Nothing in the pilot auto-merges: the matcher proposes, an
 * operator approves (merge into an existing tea, or create a new one) or rejects here. Approval is the
 * ONLY path that writes the canonical catalog (via [CanonicalUpsertService]); it can never mark a row
 * verified.
 */
@Service
class ReviewService(
    private val matchDecisionRepository: MatchDecisionRepository,
    private val sourceRecordRepository: SourceRecordRepository,
    private val revisionRepository: SourceRecordRevisionRepository,
    private val canonicalUpsertService: CanonicalUpsertService,
    private val catalogService: TeaCatalogService,
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

    /** Approve: create a brand-new canonical tea from the reviewed revision. */
    @Transactional
    fun approveNew(decisionId: Long, reviewer: String): ReviewResultDto {
        val decision = pendingDecision(decisionId)
        val record = record(decision)
        val revision = reviewedRevision(decision, record)
        val teaId = canonicalUpsertService.applyApprovedNew(record, revision, decisionId, reviewer)
        finish(decision, "approved_new", reviewer, teaId)
        return ReviewResultDto(decisionId, "approved_new", teaId)
    }

    /** Approve: merge the reviewed revision into an existing tea (operator may override the proposed target). */
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
        val record = record(decision)
        val revision = reviewedRevision(decision, record)
        val teaId = canonicalUpsertService.applyApprovedMerge(record, revision, decisionId, reviewer, target)
        finish(decision, "approved_merge", reviewer, teaId)
        return ReviewResultDto(decisionId, "approved_merge", teaId)
    }

    @Transactional
    fun reject(decisionId: Long, reviewer: String): ReviewResultDto {
        val decision = pendingDecision(decisionId)
        finish(decision, "rejected", reviewer, null)
        return ReviewResultDto(decisionId, "rejected", null)
    }

    private fun finish(decision: MatchDecision, outcome: String, reviewer: String, teaId: Long?) {
        decision.decision = outcome
        decision.reviewer = reviewer
        decision.decidedAt = Instant.now()
        if (teaId != null) decision.candidateTeaId = teaId
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
    }
}

/** Approval came after the source content changed (decision #137-C5) -- the reviewed revision is stale. */
class StaleDecisionException(message: String) : RuntimeException(message)
