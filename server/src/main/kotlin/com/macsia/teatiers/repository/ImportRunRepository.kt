package com.macsia.teatiers.repository

import com.macsia.teatiers.domain.ImportRun
import jakarta.persistence.LockModeType
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Lock
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param

interface ImportRunRepository : JpaRepository<ImportRun, Long> {

    /**
     * Load a run with a pessimistic write lock so a concurrent finish/transition can't race an in-flight
     * ingest's run-state validation (decision #137-C4). Returns null for a missing run.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select r from ImportRun r where r.id = :id")
    fun findByIdForUpdate(@Param("id") id: Long): ImportRun?

    /**
     * At most one ACTIVE (non-terminal) run per source (decision #137-C4); a friendly pre-check before the
     * DB partial-unique. Pass [com.macsia.teatiers.service.ImportRunState.ACTIVE_CODES].
     */
    fun existsBySourceSiteIdAndStatusIn(sourceSiteId: Long, statuses: Collection<String>): Boolean
}
