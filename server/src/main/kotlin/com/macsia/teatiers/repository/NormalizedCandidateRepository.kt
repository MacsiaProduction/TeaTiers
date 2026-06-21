package com.macsia.teatiers.repository

import com.macsia.teatiers.domain.NormalizedCandidate
import org.springframework.data.jpa.repository.JpaRepository

interface NormalizedCandidateRepository : JpaRepository<NormalizedCandidate, Long> {
    fun findBySourceRecordId(sourceRecordId: Long): NormalizedCandidate?
}
