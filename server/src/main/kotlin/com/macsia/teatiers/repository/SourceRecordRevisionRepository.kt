package com.macsia.teatiers.repository

import com.macsia.teatiers.domain.SourceRecordRevision
import org.springframework.data.jpa.repository.JpaRepository

interface SourceRecordRevisionRepository : JpaRepository<SourceRecordRevision, Long> {

    /** Find the revision carrying these exact facts -- a re-import of identical content (decision #137-C5). */
    fun findBySourceRecordIdAndContentHash(sourceRecordId: Long, contentHash: String): SourceRecordRevision?
}
