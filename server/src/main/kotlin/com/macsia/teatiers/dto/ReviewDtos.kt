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
 * id); [skippedCount] counts approved decisions whose source record was already linked (idempotent re-apply).
 */
data class RunApplyResultDto(
    val runId: Long,
    val appliedCount: Int,
    val skippedCount: Int,
    val results: List<ReviewResultDto>,
)
