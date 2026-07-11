package com.macsia.teatiers.data.db

import com.macsia.teatiers.domain.model.Board
import com.macsia.teatiers.domain.model.EnrichmentState
import com.macsia.teatiers.domain.model.FlavorDimension
import com.macsia.teatiers.domain.model.FlavorScore
import com.macsia.teatiers.domain.model.PhotoSource
import com.macsia.teatiers.domain.model.Placement
import com.macsia.teatiers.domain.model.PurchaseLocation
import com.macsia.teatiers.domain.model.Tea
import com.macsia.teatiers.domain.model.TeaPhoto
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
        // Sort by position, with a stable id tie-break so equal positions (e.g. a rare concurrent add
        // that read the same MAX) resolve deterministically instead of by unordered Room row order.
        .sortedWith(compareBy({ it.placement.position }, { it.placement.id }))
        .groupBy { it.placement.tierId }
    return Board(
        id = board.id,
        name = board.name,
        tiers = sortedTiers.map { it.toDomain() },
        placements = sortedTiers.associate { tier ->
            tier.id to placementsByTier[tier.id].orEmpty().mapNotNull { it.toDomainOrNull() }
        },
        unranked = placementsByTier[null].orEmpty().mapNotNull { it.toDomainOrNull() },
    )
}

fun TierEntity.toDomain(): Tier = Tier(id = id, label = label, position = position, colorArgb = colorArgb)

/**
 * Resolves the placement to its single user-tea, or null when the tea side is missing. Room returns
 * the side as a list because `@Relation` is collection-typed, but placement → tea is 1:1 (FK + UNIQUE
 * on placements). A missing row here is an orphan placement (an FK-cascade race, or a partial/restored
 * backup); dropping it keeps the read path crash-safe — one bad row must never throw and brick every
 * board emission, matching the never-throw contract of the sibling mappers (see [TeaWithChildren]).
 */
fun PlacementWithTea.toDomainOrNull(): Placement? {
    val teaWithChildren = tea.firstOrNull() ?: return null
    return Placement(placementId = placement.id, tea = teaWithChildren.toDomain())
}

fun TeaWithChildren.toDomain(): Tea = Tea(
    id = tea.id,
    nameRu = tea.nameRu,
    nameZh = tea.nameZh,
    pinyin = tea.pinyin,
    nameEn = tea.nameEn,
    // Defensive: a backup (or a future-version DB) can carry a type/dimension/intensity this build
    // doesn't know. Read-path mapping must never throw — an unknown type folds to OTHER, an unknown
    // flavor axis is skipped, and intensity is clamped to the 0..5 [FlavorScore] invariant. Otherwise
    // one bad row crashes every render and bricks the app on launch (no way back without a reinstall).
    type = runCatching { TeaType.valueOf(tea.type) }.getOrDefault(TeaType.OTHER),
    origin = tea.origin,
    shortBlurb = tea.shortBlurb,
    flavor = flavors.sortedBy { it.position }.mapNotNull { row ->
        val dimension = runCatching { FlavorDimension.valueOf(row.dimension) }.getOrNull()
            ?: return@mapNotNull null
        FlavorScore(dimension, row.intensity.coerceIn(0, 5))
    },
    notes = tea.notes,
    vendor = tea.vendor,
    product = tea.product,
    harvestYear = tea.harvestYear,
    batch = tea.batch,
    grade = tea.grade,
    purchaseLocations = purchases.sortedBy { it.position }.map { it.toDomain() },
    photos = photos.sortedBy { it.position }.map { it.toDomain() },
    catalogTeaId = tea.catalogTeaId,
    enrichmentState = enrichmentStateFromDb(tea.enrichmentState),
    createdAtEpochMs = tea.createdAtEpochMs,
)

/** Stored enrichment-state name -> enum; an unknown/renamed value falls back to NONE. */
fun enrichmentStateFromDb(raw: String): EnrichmentState =
    runCatching { EnrichmentState.valueOf(raw) }.getOrDefault(EnrichmentState.NONE)

fun PurchaseLocationEntity.toDomain(): PurchaseLocation = when (kind) {
    PurchaseKindDb.URL -> PurchaseLocation.Marketplace(url = value, label = label)
    else -> PurchaseLocation.FreeText(text = value, label = label)
}

fun PhotoEntity.toDomain(): TeaPhoto = TeaPhoto(
    id = id,
    uri = uri,
    position = position,
    source = runCatching { PhotoSource.valueOf(source) }.getOrDefault(PhotoSource.USER),
    license = license,
    sourceUrl = sourceUrl,
)

// --- Domain -> entities -------------------------------------------------------------------

/** Flattened rows for one sample, ready to insert together. */
data class TeaEntities(
    val tea: TeaSampleEntity,
    val flavors: List<FlavorEntity>,
    val purchases: List<PurchaseLocationEntity>,
    val photos: List<PhotoEntity>,
)

/**
 * All rows for a first-run seed, grouped by table for batched inserts. [catalogRefs] must be
 * inserted BEFORE [teas] (the `tea_samples.catalogTeaId` FK → `catalog_refs.id`).
 */
data class SeedEntities(
    val boards: List<BoardEntity>,
    val tiers: List<TierEntity>,
    val catalogRefs: List<CatalogRefEntity>,
    val teas: List<TeaSampleEntity>,
    val placements: List<PlacementEntity>,
    val flavors: List<FlavorEntity>,
    val purchases: List<PurchaseLocationEntity>,
    val photos: List<PhotoEntity>,
)

fun Tea.toEntities(rowId: String = id, nowMs: Long = 0L): TeaEntities {
    val teaEntity = TeaSampleEntity(
        id = rowId,
        nameRu = nameRu,
        nameZh = nameZh,
        pinyin = pinyin,
        nameEn = nameEn,
        type = type.name,
        origin = origin,
        shortBlurb = shortBlurb,
        notes = notes,
        catalogTeaId = catalogTeaId,
        enrichmentState = enrichmentState.name,
        vendor = vendor,
        product = product,
        harvestYear = harvestYear,
        batch = batch,
        grade = grade,
        // Keep an existing timestamp on round-trip; stamp nowMs on a fresh add; seed rows (nowMs=0)
        // stay null (no known creation time). Mirrors the photo createdAt convention above.
        createdAtEpochMs = createdAtEpochMs ?: nowMs.takeIf { it > 0L },
    )
    val flavorRows = flavor.toFlavorEntities(rowId)
    val purchaseRows = purchaseLocations.mapIndexed { index, location ->
        location.toEntity(teaId = rowId, position = index)
    }
    val photoRows = photos.mapIndexed { index, photo ->
        photo.toEntity(teaId = rowId, position = index, fallbackCreatedAtMs = nowMs)
    }
    return TeaEntities(teaEntity, flavorRows, purchaseRows, photoRows)
}

fun List<FlavorScore>.toFlavorEntities(teaId: String): List<FlavorEntity> = mapIndexed { index, score ->
    FlavorEntity(teaId = teaId, dimension = score.dimension.name, intensity = score.intensity, position = index)
}

fun PurchaseLocation.toEntity(teaId: String, position: Int): PurchaseLocationEntity = when (this) {
    is PurchaseLocation.Marketplace ->
        PurchaseLocationEntity("$teaId-p$position", teaId, position, PurchaseKindDb.URL, label, url)
    is PurchaseLocation.FreeText ->
        PurchaseLocationEntity("$teaId-p$position", teaId, position, PurchaseKindDb.TEXT, label, text)
}

fun TeaPhoto.toEntity(teaId: String, position: Int, fallbackCreatedAtMs: Long): PhotoEntity =
    PhotoEntity(
        id = id,
        teaId = teaId,
        uri = uri,
        position = position,
        source = source.name,
        license = license,
        sourceUrl = sourceUrl,
        // Seed/round-trip teas don't carry timestamps; they get a deterministic 0L unless the
        // caller passes a real wall-clock value (the repository does on user uploads).
        createdAtEpochMs = fallbackCreatedAtMs,
    )

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
    val teaRows = mutableListOf<TeaSampleEntity>()
    val flavorRows = mutableListOf<FlavorEntity>()
    val purchaseRows = mutableListOf<PurchaseLocationEntity>()
    val photoRows = mutableListOf<PhotoEntity>()

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
                photoRows += entities.photos
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
    // One catalog_refs stub per DISTINCT linked catalog id (FK target for tea_samples.catalogTeaId);
    // type comes from a linked sample. Custom/seed teas carry no catalogTeaId, so this is usually empty.
    val refRows = teaRows
        .mapNotNull { tea -> tea.catalogTeaId?.let { it to tea.type } }
        .toMap()
        .map { (id, type) -> CatalogRefEntity(id = id, type = type, fetchedAtEpochMs = 0L) }
    return SeedEntities(boardRows, tierRows, refRows, teaRows, placementRows, flavorRows, purchaseRows, photoRows)
}
