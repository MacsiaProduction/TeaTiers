package com.macsia.teatiers.dto

import java.math.BigDecimal

/** One pending item in the operator review queue (decision #136). Read-only context for a decision. */
data class PendingMatchDto(
    val decisionId: Long,
    val sourceRecordId: Long,
    val canonicalUrl: String,
    val matchTier: String,
    val proposedKind: String,
    val matchScore: BigDecimal?,
    val candidateTeaId: Long?,
    // The observed names (facts), so the operator can judge without opening the source.
    val names: List<ScrapedName>,
    val candidate: TeaSummaryDto?,
    // The full ranked candidate set within the winning tier (decision #141 / FND-P1-1). On a 'conflict'
    // proposal the operator picks an explicit target from here; empty for a create_new. Best first.
    val candidates: List<CandidateHitDto> = emptyList(),
)

/** One ranked match candidate the operator can choose among (decision #141 / FND-P1-1). [rank] 0 = best. */
data class CandidateHitDto(
    val teaId: Long,
    val matchTier: String,
    val matchScore: BigDecimal?,
    val rank: Int,
    val candidate: TeaSummaryDto?,
)

/**
 * Result of a review DECISION (decision #137-C4 two-phase). Approve/reject only record the operator's intent
 * -- no catalog is written -- so [teaId] is null here. The catalog write happens later in the run's apply
 * phase; [RunApplyResultDto] carries the resulting tea ids.
 */
data class ReviewResultDto(
    val decisionId: Long,
    val decision: String,
    val teaId: Long?,
)

/**
 * Result of applying a fully-reviewed run to the canonical catalog (decision #137-C4). The ONLY path that
 * writes teas. [results] is one entry per approved decision actually materialized (with its new/target tea
 * id); [skippedCount] counts approved decisions whose source record was already linked (idempotent re-apply);
 * [failures] is one entry per approved decision quarantined by an identity collision (H3) -- the rest of the
 * run still applies, and the operator resolves these by merging the colliding identities.
 */
data class RunApplyResultDto(
    val runId: Long,
    val appliedCount: Int,
    val skippedCount: Int,
    val results: List<ReviewResultDto>,
    val failures: List<ApplyFailureDto> = emptyList(),
)

/**
 * One approved decision the apply phase could NOT materialize (H3, decision #141 review): its identity
 * collides with an active tea (a duplicate authoritative alias, or a create_new dedup_key collision) that the
 * operator must resolve by merging. Reported instead of aborting the whole run, so one poison decision never
 * blocks the rest.
 */
data class ApplyFailureDto(val decisionId: Long, val reason: String)
