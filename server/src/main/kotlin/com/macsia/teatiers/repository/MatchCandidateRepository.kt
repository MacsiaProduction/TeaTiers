package com.macsia.teatiers.repository

import com.macsia.teatiers.domain.MatchCandidate
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param

interface MatchCandidateRepository : JpaRepository<MatchCandidate, Long> {

    /** The ranked candidate set for a decision, best first (decision #141 / FND-P1-1). */
    fun findByMatchDecisionIdOrderByRankAsc(matchDecisionId: Long): List<MatchCandidate>

    /**
     * Clear a decision's candidate set before re-pointing it (the decision is reused in place across
     * revisions). A bulk DELETE so the (match_decision_id, rank) unique can't see stale rows on reinsert.
     */
    @Modifying
    @Query("delete from MatchCandidate c where c.matchDecisionId = :id")
    fun deleteByMatchDecisionId(@Param("id") id: Long)
}
