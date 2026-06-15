package com.macsia.teatiers.data.db

import com.macsia.teatiers.domain.model.Board
import com.macsia.teatiers.domain.model.FlavorDimension
import com.macsia.teatiers.domain.model.FlavorScore
import com.macsia.teatiers.domain.model.Placement
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
    val placementsByTier = placements
        .sortedBy { it.placement.position }
        .groupBy { it.placement.tierId }
    return Board(
        id = board.id,
        name = board.name,
        tiers = sortedTiers.map { it.toDomain() },
        placements = sortedTiers.associate { tier ->
            tier.id to placementsByTier[tier.id].orEmpty().map { it.toDomain() }
        },
        unranked = placementsByTier[null].orEmpty().map { it.toDomain() },
    )
}

fun TierEntity.toDomain(): Tier = Tier(id = id, label = label, position = position, colorArgb = colorArgb)

/**
 * Resolves the placement to its single user-tea. Room returns the side as a list because
 * `@Relation` is collection-typed, but placement → tea is 1:1 (FK + UNIQUE on placements):
 * a missing row at this point means the FK cascade lost a race or the seed is malformed.
 */
fun PlacementWithTea.toDomain(): Placement {
    val teaWithChildren = tea.firstOrNull()
        ?: error("Placement ${placement.id} references missing tea ${placement.teaId}")
    return Placement(placementId = placement.id, tea = teaWithChildren.toDomain())
}

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

/** Flattened rows for one user-tea, ready to insert together. */
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
    val placements: List<PlacementEntity>,
    val flavors: List<FlavorEntity>,
    val purchases: List<PurchaseLocationEntity>,
)

fun Tea.toEntities(rowId: String = id): TeaEntities {
    val teaEntity = TeaEntity(
        id = rowId,
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
 * Flattens the seed boards into table rows. Teas are deduplicated by [Tea.id]: the user-tea
 * pool is global (decisions.md #42), so a tea appearing on multiple boards yields one
 * [TeaEntity] + N [PlacementEntity]s. [PlacementEntity.position] is assigned per board in
 * placement-then-tray order so the UI ordering round-trips.
 */
fun List<Board>.toSeedEntities(): SeedEntities {
    val boardRows = mutableListOf<BoardEntity>()
    val tierRows = mutableListOf<TierEntity>()
    val placementRows = mutableListOf<PlacementEntity>()

    val seenTeaIds = mutableSetOf<String>()
    val teaRows = mutableListOf<TeaEntity>()
    val flavorRows = mutableListOf<FlavorEntity>()
    val purchaseRows = mutableListOf<PurchaseLocationEntity>()

    forEachIndexed { boardIndex, board ->
        boardRows += BoardEntity(id = board.id, name = board.name, position = boardIndex)
        board.tiers.forEach { tier ->
            tierRows += TierEntity(tier.id, board.id, tier.label, tier.position, tier.colorArgb)
        }

        var position = 0
        val placedInTierOrder: List<Pair<Placement, String?>> = board.tiers.sortedBy { it.position }
            .flatMap { tier -> board.placements[tier.id].orEmpty().map { p -> p to tier.id } }
        val tray: List<Pair<Placement, String?>> = board.unranked.map { p -> p to null }
        (placedInTierOrder + tray).forEach { (placement, tierId) ->
            val tea = placement.tea
            if (seenTeaIds.add(tea.id)) {
                val entities = tea.toEntities()
                teaRows += entities.tea
                flavorRows += entities.flavors
                purchaseRows += entities.purchases
            }
            placementRows += PlacementEntity(
                id = placement.placementId,
                boardId = board.id,
                teaId = tea.id,
                tierId = tierId,
                position = position++,
            )
        }
    }
    return SeedEntities(boardRows, tierRows, teaRows, placementRows, flavorRows, purchaseRows)
}
