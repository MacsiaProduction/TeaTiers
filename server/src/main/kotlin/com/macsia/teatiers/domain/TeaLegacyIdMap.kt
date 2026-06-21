package com.macsia.teatiers.domain

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.util.UUID

/**
 * Durable map of every BIGINT `tea.id` ever returned to a client onto the stable `tea.public_id` (V7,
 * decision #136). It decouples the numeric id (which a DB rebuild / reseed can re-number) from the
 * public id (stable forever), so a client holding an old `catalogTeaId` still resolves after a rebuild.
 * Backfilled for the pre-V7 rows; the importer + seeder add a row for each tea they create.
 */
@Entity
@Table(name = "tea_legacy_id_map")
class TeaLegacyIdMap(
    @Id
    @Column(name = "legacy_id")
    val legacyId: Long,

    @Column(name = "public_id", nullable = false)
    val publicId: UUID,
)
