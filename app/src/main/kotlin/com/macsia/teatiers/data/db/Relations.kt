package com.macsia.teatiers.data.db

import androidx.room.Embedded
import androidx.room.Relation

/** A user sample with its flavor scores, purchase locations, and photos. One @Transaction read. */
data class TeaWithChildren(
    @Embedded val tea: TeaSampleEntity,
    @Relation(parentColumn = "id", entityColumn = "teaId")
    val flavors: List<FlavorEntity>,
    @Relation(parentColumn = "id", entityColumn = "teaId")
    val purchases: List<PurchaseLocationEntity>,
    @Relation(parentColumn = "id", entityColumn = "teaId")
    val photos: List<PhotoEntity>,
)

/**
 * A placement with the sample it points at. The tea side is a List by Room convention, but
 * placement → sample is 1:1 (FK + UNIQUE(boardId, teaId)) so the mapper just takes the first
 * element. Drives the nested fetch in [BoardWithChildren].
 */
data class PlacementWithTea(
    @Embedded val placement: PlacementEntity,
    @Relation(
        entity = TeaSampleEntity::class,
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
