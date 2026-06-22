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
 * One ranked candidate the matcher surfaced for a [MatchDecision] (V15, decision #141 / FND-P1-1). The set
 * holds the winning tier's distinct-owner teas in rank order ([rank] 0 = best); it is review context so the
 * operator can see the runners-up and pick an explicit target on a multi-owner 'conflict'. The chosen/best
 * candidate is still denormalized on the decision itself; this never feeds the apply path.
 */
@Entity
@Table(name = "match_candidate")
class MatchCandidate(
    @Column(name = "match_decision_id", nullable = false)
    var matchDecisionId: Long,

    @Column(name = "tea_id", nullable = false)
    var teaId: Long,

    @Column(name = "match_tier", nullable = false)
    var matchTier: String,

    @Column(name = "match_score")
    var matchScore: BigDecimal? = null,

    @Column(nullable = false)
    var rank: Int,

    @Column(name = "created_at", nullable = false)
    var createdAt: Instant = Instant.now(),

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long? = null,
)
