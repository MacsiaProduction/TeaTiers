package com.macsia.teatiers.repository

import com.macsia.teatiers.domain.RawEvidence
import org.springframework.data.jpa.repository.JpaRepository

interface RawEvidenceRepository : JpaRepository<RawEvidence, Long>
