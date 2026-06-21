package com.macsia.teatiers.repository

import com.macsia.teatiers.domain.SourceRecord
import org.springframework.data.jpa.repository.JpaRepository

interface SourceRecordRepository : JpaRepository<SourceRecord, Long> {

    /** Idempotency lookup: a source usually has a stable external id. */
    fun findBySourceSiteIdAndExternalId(sourceSiteId: Long, externalId: String): SourceRecord?

    /** Idempotency fallback when the source has no stable external id. */
    fun findBySourceSiteIdAndCanonicalUrl(sourceSiteId: Long, canonicalUrl: String): SourceRecord?

    fun findByStatus(status: String): List<SourceRecord>
}
