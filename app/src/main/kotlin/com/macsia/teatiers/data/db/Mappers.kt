package com.macsia.teatiers.data.db

import com.macsia.teatiers.domain.model.Board
import com.macsia.teatiers.domain.model.FlavorDimension
import com.macsia.teatiers.domain.model.FlavorScore
import com.macsia.teatiers.domain.model.PurchaseLocation
import com.macsia.teatiers.domain.model.Tea
import com.macsia.teatiers.domain.model.TeaType
import com.macsia.teatiers.domain.model.Tier

/** Stable text values for [PurchaseLocationEntity.kind] (decisions.md #20). */
private object PurchaseKindDb {
    const val URL = "URL"
    const val TEXT = "TEXT"
}

// --- Entities -> domain -------------------------------------------------------------------

fun BoardWithChildren.toDomain(): Board {
    val sortedTiers = tiers.sortedBy { it.position }
    val teasByTier = teas.sortedBy { it.tea.position }.groupBy { it.tea.tierId }
    return Board(
        id = board.id,
        name = board.name,
        tiers = sortedTiers.map { it.toDomain() },
        placements = sortedTiers.associate { tier ->
            tier.id to teasByTier[tier.id].orEmpty().map { it.toDomain() }
        },
        unranked = teasByTier[null].orEmpty().map { it.toDomain() },
    )
}

fun TierEntity.toDomain(): Tier = Tier(id = id, label = label, position = position, colorArgb = colorArgb)

fun TeaWithChildren.toDomain(): Tea = Tea(
    id = tea.id,
    nameRu = tea.nameRu,
    nameZh = tea.nameZh,
    pinyin = tea.pinyin,
    nameEn = tea.nameEn,
    type = TeaType.valueOf(tea.type),
    origin = tea.origin,
    shortBlurb = tea.shortBlurb,
    flavor = flavors.sortedBy { it.position }
        .map { FlavorScore(FlavorDimension.valueOf(it.dimension), it.intensity) },
    notes = tea.notes,
    purchaseLocations = purchases.sortedBy { it.position }.map { it.toDomain() },
)

fun PurchaseLocationEntity.toDomain(): PurchaseLocation = when (kind) {
    PurchaseKindDb.URL -> PurchaseLocation.Marketplace(url = value, label = label)
    else -> PurchaseLocation.FreeText(text = value, label = label)
}

// --- Domain -> entities -------------------------------------------------------------------

/** Flattened rows for one tea, ready to insert together. */
data class TeaEntities(
    val tea: TeaEntity,
    val flavors: List<FlavorEntity>,
    val purchases: List<PurchaseLocationEntity>,
)

/** All rows for a first-run seed, grouped by table for batched inserts. */
data class SeedEntities(
    val boards: List<BoardEntity>,
    val tiers: List<TierEntity>,
    val teas: List<TeaEntity>,
    val flavors: List<FlavorEntity>,
    val purchases: List<PurchaseLocationEntity>,
)

fun Tea.toEntities(rowId: String, boardId: String, tierId: String?, position: Int): TeaEntities {
    val teaEntity = TeaEntity(
        id = rowId,
        boardId = boardId,
        tierId = tierId,
        position = position,
        nameRu = nameRu,
        nameZh = nameZh,
        pinyin = pinyin,
        nameEn = nameEn,
        type = type.name,
        origin = origin,
        shortBlurb = shortBlurb,
        notes = notes,
    )
    val flavorRows = flavor.mapIndexed { index, score ->
        FlavorEntity(teaId = rowId, dimension = score.dimension.name, intensity = score.intensity, position = index)
    }
    val purchaseRows = purchaseLocations.mapIndexed { index, location ->
        location.toEntity(teaId = rowId, position = index)
    }
    return TeaEntities(teaEntity, flavorRows, purchaseRows)
}

fun PurchaseLocation.toEntity(teaId: String, position: Int): PurchaseLocationEntity = when (this) {
    is PurchaseLocation.Marketplace ->
        PurchaseLocationEntity("$teaId-p$position", teaId, position, PurchaseKindDb.URL, label, url)
    is PurchaseLocation.FreeText ->
        PurchaseLocationEntity("$teaId-p$position", teaId, position, PurchaseKindDb.TEXT, label, text)
}

/**
 * Flattens the seed boards into table rows. Each board's copy of a tea gets a board-unique row id
 * ("<boardId>-<teaId>") because teas are board-scoped, while [Tea.position] is assigned per board
 * in placement-then-tray order so the UI ordering round-trips.
 */
fun List<Board>.toSeedEntities(): SeedEntities {
    val boardRows = mutableListOf<BoardEntity>()
    val tierRows = mutableListOf<TierEntity>()
    val teaRows = mutableListOf<TeaEntity>()
    val flavorRows = mutableListOf<FlavorEntity>()
    val purchaseRows = mutableListOf<PurchaseLocationEntity>()

    forEachIndexed { boardIndex, board ->
        boardRows += BoardEntity(id = board.id, name = board.name, position = boardIndex)
        board.tiers.forEach { tier ->
            tierRows += TierEntity(tier.id, board.id, tier.label, tier.position, tier.colorArgb)
        }
        var position = 0
        val placedInTierOrder: List<Pair<Tea, String?>> = board.tiers.sortedBy { it.position }
            .flatMap { tier -> board.placements[tier.id].orEmpty().map { tea -> tea to tier.id } }
        val tray: List<Pair<Tea, String?>> = board.unranked.map { tea -> tea to null }
        (placedInTierOrder + tray).forEach { (tea, tierId) ->
            val entities = tea.toEntities("${board.id}-${tea.id}", board.id, tierId, position++)
            teaRows += entities.tea
            flavorRows += entities.flavors
            purchaseRows += entities.purchases
        }
    }
    return SeedEntities(boardRows, tierRows, teaRows, flavorRows, purchaseRows)
}
