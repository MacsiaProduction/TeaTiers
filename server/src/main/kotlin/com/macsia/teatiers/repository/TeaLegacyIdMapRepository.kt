package com.macsia.teatiers.repository

import com.macsia.teatiers.domain.TeaLegacyIdMap
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import java.util.UUID

interface TeaLegacyIdMapRepository : JpaRepository<TeaLegacyIdMap, Long> {

    /**
     * Record a numeric id -> public_id pairing once. Idempotent: a re-run (e.g. a reseed) keeps the
     * existing public_id rather than failing, so a numeric id never flips to a different tea.
     */
    @Modifying
    @Query(
        value = "INSERT INTO tea_legacy_id_map (legacy_id, public_id) VALUES (:legacyId, :publicId) " +
            "ON CONFLICT (legacy_id) DO NOTHING",
        nativeQuery = true,
    )
    fun recordOnce(@Param("legacyId") legacyId: Long, @Param("publicId") publicId: UUID)
}
