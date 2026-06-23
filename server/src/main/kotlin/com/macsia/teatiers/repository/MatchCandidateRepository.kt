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
     * `flushAutomatically` is REQUIRED with `clearAutomatically` (H7): the caller saves the (possibly
     * re-pointed) MatchDecision just before this runs; without the flush, clearing the persistence context
     * would discard that decision's pending UPDATE (e.g. its re-pointed import_run_id) -- silently losing a
     * cross-run re-propose. Flush first, then clear.
     */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("delete from MatchCandidate c where c.matchDecisionId = :id")
    fun deleteByMatchDecisionId(@Param("id") id: Long)
}
