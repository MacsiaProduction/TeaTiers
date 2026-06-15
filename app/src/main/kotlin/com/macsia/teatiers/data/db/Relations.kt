package com.macsia.teatiers.data.db

import androidx.room.Embedded
import androidx.room.Relation

/** A tea with its flavor scores and purchase locations, fetched in one @Transaction read. */
data class TeaWithChildren(
    @Embedded val tea: TeaEntity,
    @Relation(parentColumn = "id", entityColumn = "teaId")
    val flavors: List<FlavorEntity>,
    @Relation(parentColumn = "id", entityColumn = "teaId")
    val purchases: List<PurchaseLocationEntity>,
)

/** A board with its tiers and (nested) teas — the full aggregate the UI renders. */
data class BoardWithChildren(
    @Embedded val board: BoardEntity,
    @Relation(parentColumn = "id", entityColumn = "boardId")
    val tiers: List<TierEntity>,
    @Relation(entity = TeaEntity::class, parentColumn = "id", entityColumn = "boardId")
    val teas: List<TeaWithChildren>,
)
