package com.macsia.teatiers.repository

import com.macsia.teatiers.domain.TeaLegacyIdMap
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import java.util.UUID

interface TeaLegacyIdMapRepository : JpaRepository<TeaLegacyIdMap, Long> {

    /**
     * Insert a numeric id -> public_id pairing, ignoring a pre-existing row for [legacyId]. Returns the
     * number of rows inserted (1 = new, 0 = a row already existed). Use [recordOnce], which checks the
     * existing row for a conflicting public_id.
     */
    @Modifying
    @Query(
        value = "INSERT INTO tea_legacy_id_map (legacy_id, public_id) VALUES (:legacyId, :publicId) " +
            "ON CONFLICT (legacy_id) DO NOTHING",
        nativeQuery = true,
    )
    fun insertIfAbsent(@Param("legacyId") legacyId: Long, @Param("publicId") publicId: UUID): Int

    /**
     * Record a numeric id -> public_id pairing once (decision #137-C1). Re-recording the SAME pairing is
     * an idempotent no-op (e.g. a reseed). Re-recording the same [legacyId] with a DIFFERENT [publicId]
     * means a numeric id is being reused for another tea -- a corruption that would silently make an old
     * client resolve to the wrong tea -- so it FAILS LOUDLY rather than retaining a now-wrong mapping.
     */
    fun recordOnce(legacyId: Long, publicId: UUID) {
        if (insertIfAbsent(legacyId, publicId) == 0) {
            val existing = findById(legacyId).orElse(null)?.publicId
            if (existing != null && existing != publicId) {
                throw LegacyIdReuseException(legacyId, existing, publicId)
            }
        }
    }
}

/**
 * A numeric tea id is being remapped to a different public_id (decision #137-C1) -- numeric-id reuse that
 * would silently resolve an old client to the wrong tea. A plain [IllegalArgumentException] would be
 * rewrapped by the repository's persistence-exception translation, so this is its own type.
 */
class LegacyIdReuseException(legacyId: Long, existing: UUID, attempted: UUID) :
    RuntimeException("legacy_id $legacyId already maps to $existing; refusing to remap to $attempted (numeric-id reuse)")
