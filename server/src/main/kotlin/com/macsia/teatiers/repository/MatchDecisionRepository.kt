package com.macsia.teatiers.repository

import com.macsia.teatiers.domain.MatchDecision
import jakarta.persistence.LockModeType
import org.springframework.data.domain.Limit
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Lock
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param

interface MatchDecisionRepository : JpaRepository<MatchDecision, Long> {

    /**
     * Load a decision with a pessimistic write lock so two operators can't consume the SAME pending decision
     * concurrently (decision #137 / FND-P1-3): the first to lock decides it terminal; the second blocks, then
     * re-reads the now-terminal row and is rejected. Returns null for a missing decision.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select d from MatchDecision d where d.id = :id")
    fun findByIdForUpdate(@Param("id") id: Long): MatchDecision?

    fun findBySourceRecordId(sourceRecordId: Long): List<MatchDecision>

    /** The operator review queue: oldest pending first. */
    fun findByDecisionOrderByCreatedAtAsc(decision: String, limit: Limit): List<MatchDecision>

    fun countByDecision(decision: String): Long

    /** Run-scoped review completeness (decision #137-C4): a run may be reviewed only with 0 pending. */
    fun countByImportRunIdAndDecision(importRunId: Long, decision: String): Long

    /** The approved decisions a run's apply phase must materialize. */
    fun findByImportRunIdAndDecisionIn(importRunId: Long, decisions: Collection<String>): List<MatchDecision>
}
