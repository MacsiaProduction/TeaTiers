package com.macsia.teatiers.data.db

import androidx.room.Embedded
import androidx.room.Relation

/** A user-tea with its flavor scores and purchase locations, fetched in one @Transaction read. */
data class TeaWithChildren(
    @Embedded val tea: TeaEntity,
    @Relation(parentColumn = "id", entityColumn = "teaId")
    val flavors: List<FlavorEntity>,
    @Relation(parentColumn = "id", entityColumn = "teaId")
    val purchases: List<PurchaseLocationEntity>,
)

/**
 * A placement with the user-tea it points at. The tea side is a List by Room convention, but
 * placement → tea is 1:1 (FK + UNIQUE) so the mapper just takes the first element. Drives the
 * nested fetch in [BoardWithChildren].
 */
data class PlacementWithTea(
    @Embedded val placement: PlacementEntity,
    @Relation(
        entity = TeaEntity::class,
        parentColumn = "teaId",
        entityColumn = "id",
    )
    val tea: List<TeaWithChildren>,
)

/** A board with its tiers and placements (each placement carries its user-tea aggregate). */
data class BoardWithChildren(
    @Embedded val board: BoardEntity,
    @Relation(parentColumn = "id", entityColumn = "boardId")
    val tiers: List<TierEntity>,
    @Relation(
        entity = PlacementEntity::class,
        parentColumn = "id",
        entityColumn = "boardId",
    )
    val placements: List<PlacementWithTea>,
)
