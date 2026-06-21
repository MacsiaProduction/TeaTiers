package com.macsia.teatiers.repository

import com.macsia.teatiers.domain.TeaFieldProvenance
import org.springframework.data.jpa.repository.JpaRepository

interface TeaFieldProvenanceRepository : JpaRepository<TeaFieldProvenance, Long> {
    fun findByTeaId(teaId: Long): List<TeaFieldProvenance>
}
