package com.macsia.teatiers.domain

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.math.BigDecimal
import java.time.Instant

/**
 * The human review/conflict queue (V8, decision #136). In the pilot nothing auto-merges or auto-creates:
 * every cross-script / fuzzy candidate lands here as 'pending' and an operator decides. [matchTier]
 * records WHY it was proposed; [candidateTeaId] is null for a create-new proposal.
 */
@Entity
@Table(name = "match_decision")
class MatchDecision(
    @Column(name = "source_record_id", nullable = false)
    var sourceRecordId: Long,

    @Column(name = "match_tier", nullable = false)
    var matchTier: String,

    @Column(name = "proposed_kind", nullable = false)
    var proposedKind: String,

    @Column(name = "normalized_candidate_id")
    var normalizedCandidateId: Long? = null,

    @Column(name = "candidate_tea_id")
    var candidateTeaId: Long? = null,

    @Column(name = "match_score")
    var matchScore: BigDecimal? = null,

    @Column(nullable = false)
    var decision: String = "pending",

    var reviewer: String? = null,

    @Column(name = "decided_at")
    var decidedAt: Instant? = null,

    @Column(name = "import_run_id")
    var importRunId: Long? = null,

    @Column(name = "created_at", nullable = false)
    var createdAt: Instant = Instant.now(),

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long? = null,
)
