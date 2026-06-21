package com.macsia.teatiers.repository

import com.macsia.teatiers.domain.TeaFieldProvenance
import org.springframework.data.jpa.repository.JpaRepository

interface TeaFieldProvenanceRepository : JpaRepository<TeaFieldProvenance, Long> {
    fun findByTeaId(teaId: Long): List<TeaFieldProvenance>

    /** All claims for one field of a tea -- used to deselect the prior selected claim before a new one. */
    fun findByTeaIdAndFieldName(teaId: Long, fieldName: String): List<TeaFieldProvenance>
}
