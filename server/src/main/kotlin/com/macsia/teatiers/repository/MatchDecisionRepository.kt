package com.macsia.teatiers.repository

import com.macsia.teatiers.domain.MatchDecision
import org.springframework.data.domain.Limit
import org.springframework.data.jpa.repository.JpaRepository

interface MatchDecisionRepository : JpaRepository<MatchDecision, Long> {

    fun findBySourceRecordId(sourceRecordId: Long): List<MatchDecision>

    /** The operator review queue: oldest pending first. */
    fun findByDecisionOrderByCreatedAtAsc(decision: String, limit: Limit): List<MatchDecision>

    fun countByDecision(decision: String): Long

    /** Run-scoped review completeness (decision #137-C4): a run may be reviewed only with 0 pending. */
    fun countByImportRunIdAndDecision(importRunId: Long, decision: String): Long

    /** The approved decisions a run's apply phase must materialize. */
    fun findByImportRunIdAndDecisionIn(importRunId: Long, decisions: Collection<String>): List<MatchDecision>
}
