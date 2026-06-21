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

/** Result of approving/rejecting a review decision. */
data class ReviewResultDto(
    val decisionId: Long,
    val decision: String,
    val teaId: Long?,
)
