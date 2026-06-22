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

    /** At most one non-terminal run per source (decision #139-R3); a friendly pre-check before the DB index. */
    fun existsBySourceSiteIdAndStatus(sourceSiteId: Long, status: String): Boolean
}
