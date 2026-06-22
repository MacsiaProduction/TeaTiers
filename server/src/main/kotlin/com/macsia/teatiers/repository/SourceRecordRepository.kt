package com.macsia.teatiers.repository

import com.macsia.teatiers.domain.SourceRecord
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param

interface SourceRecordRepository : JpaRepository<SourceRecord, Long> {

    /** Idempotency lookup: a source usually has a stable external id. */
    fun findBySourceSiteIdAndExternalId(sourceSiteId: Long, externalId: String): SourceRecord?

    /** Idempotency fallback when the source has no stable external id. */
    fun findBySourceSiteIdAndCanonicalUrl(sourceSiteId: Long, canonicalUrl: String): SourceRecord?

    fun findByStatus(status: String): List<SourceRecord>

    /**
     * Review completeness (decision #137-C4): records staged in this run whose CURRENT revision has no
     * terminal review decision yet -- i.e. either still pending OR never proposed at all. A run may be marked
     * 'reviewed' only when this is zero, so a half-proposed run can't be applied as though fully reviewed.
     * (A record re-imported identically keeps its prior run id, so it is correctly out of this run's scope.)
     */
    @Query(
        value = """
            SELECT count(*) FROM source_record sr
            WHERE sr.import_run_id = :runId
              AND sr.current_revision_id IS NOT NULL
              AND NOT EXISTS (
                SELECT 1 FROM match_decision md
                WHERE md.source_record_revision_id = sr.current_revision_id
                  AND md.decision IN ('approved_new', 'approved_merge', 'rejected')
              )
        """,
        nativeQuery = true,
    )
    fun countUnreviewedRecords(@Param("runId") runId: Long): Long
}
