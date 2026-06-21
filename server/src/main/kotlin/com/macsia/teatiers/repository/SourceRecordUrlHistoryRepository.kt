package com.macsia.teatiers.repository

import com.macsia.teatiers.domain.SourceRecordUrlHistory
import org.springframework.data.jpa.repository.JpaRepository

interface SourceRecordUrlHistoryRepository : JpaRepository<SourceRecordUrlHistory, Long> {

    fun findBySourceRecordId(sourceRecordId: Long): List<SourceRecordUrlHistory>
}
