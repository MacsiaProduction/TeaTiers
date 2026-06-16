package com.macsia.teatiers.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Transaction
import kotlinx.coroutines.flow.Flow

/**
 * One placement's new spot: its tier (null = the unranked tray) and its sort position. Used by
 * drag-to-rank and tier-removal transactions so callers can ship a batch of moves through one
 * @Transaction without N round-trips.
 */
data class PlacementMove(val placementId: String, val tierId: String?, val position: Int)

/** A tier's new order within its board, used when the tier list is reordered. */
data class TierPosition(val tierId: String, val position: Int)

/** A photo's new sort position within its tea, used when the photo strip is reordered. */
data class PhotoPosition(val photoId: String, val position: Int)

/**
 * Slim projection of a [TeaEntity] used for the resolve-or-create match (decisions.md #42). The
 * Russian-language match has to be Unicode-case-insensitive, and SQLite's built-in `LOWER` is
 * ASCII-only, so we load the candidate set into Kotlin and match there with [String.lowercase].
 */
data class TeaMatchKeyRow(
    val id: String,
    val nameRu: String,
    val nameZh: String?,
    val pinyin: String?,
)

/**
 * Abstract class (not interface) so the multi-table writes below can run inside a single
 * @Transaction default method.
 */
@Dao
abstract class TeaDao {

    @Transaction
    @Query("SELECT * FROM boards ORDER BY position")
    abstract fun observeBoards(): Flow<List<BoardWithChildren>>

    @Transaction
    @Query("SELECT * FROM teas WHERE id = :teaId")
    abstract suspend fun loadTea(teaId: String): TeaWithChildren?

    /**
     * Every user-tea (the cross-board "my teas" collection, decisions.md #27), including teas
     * with no placement left on any board (removed from every board but not deleted — #42).
     * Ordering is cosmetic and applied in Kotlin so Russian uppercase sorts correctly.
     */
    @Transaction
    @Query("SELECT * FROM teas")
    abstract fun observeAllTeas(): Flow<List<TeaWithChildren>>

    @Query("SELECT id, nameRu, nameZh, pinyin FROM teas")
    abstract suspend fun loadTeaMatchKeys(): List<TeaMatchKeyRow>

    @Query("SELECT COUNT(*) FROM placements WHERE teaId = :teaId")
    abstract suspend fun placementCountForTea(teaId: String): Int

    @Query("SELECT COUNT(*) FROM boards")
    abstract suspend fun boardCount(): Int

    /** Per-board, position is global across tiers + the tray; sort within a (board, tier) group. */
    @Query("SELECT COALESCE(MAX(position), -1) + 1 FROM placements WHERE boardId = :boardId")
    abstract suspend fun nextPlacementPosition(boardId: String): Int

    @Query("SELECT COALESCE(MAX(position), -1) + 1 FROM tiers WHERE boardId = :boardId")
    abstract suspend fun nextTierPosition(boardId: String): Int

    @Insert
    abstract suspend fun insertBoards(boards: List<BoardEntity>)

    @Insert
    abstract suspend fun insertTiers(tiers: List<TierEntity>)

    @Insert
    abstract suspend fun insertTeas(teas: List<TeaEntity>)

    @Insert
    abstract suspend fun insertPlacements(placements: List<PlacementEntity>)

    @Insert
    abstract suspend fun insertFlavors(flavors: List<FlavorEntity>)

    @Insert
    abstract suspend fun insertPurchases(purchases: List<PurchaseLocationEntity>)

    @Insert
    abstract suspend fun insertPhotos(photos: List<PhotoEntity>)

    @Query("SELECT * FROM tea_photos WHERE teaId = :teaId ORDER BY position")
    abstract suspend fun loadPhotos(teaId: String): List<PhotoEntity>

    /** URIs only — used to enumerate files to delete from disk before the row goes. */
    @Query("SELECT uri FROM tea_photos WHERE teaId = :teaId")
    abstract suspend fun loadPhotoUrisFor(teaId: String): List<String>

    @Query("SELECT uri FROM tea_photos WHERE id = :photoId")
    abstract suspend fun loadPhotoUri(photoId: String): String?

    @Query("SELECT COALESCE(MAX(position), -1) + 1 FROM tea_photos WHERE teaId = :teaId")
    abstract suspend fun nextPhotoPosition(teaId: String): Int

    @Query("UPDATE tea_photos SET position = :position WHERE id = :photoId")
    abstract suspend fun updatePhotoPosition(photoId: String, position: Int)

    @Query("DELETE FROM tea_photos WHERE id = :photoId")
    abstract suspend fun deletePhotoRow(photoId: String)

    @Query("UPDATE placements SET tierId = :tierId, position = :position WHERE id = :placementId")
    abstract suspend fun updatePlacement(placementId: String, tierId: String?, position: Int)

    @Query("DELETE FROM placements WHERE id = :placementId")
    abstract suspend fun deletePlacement(placementId: String)

    @Query("DELETE FROM teas WHERE id = :teaId")
    abstract suspend fun deleteTeaRow(teaId: String)

    @Query("UPDATE tiers SET label = :label WHERE id = :tierId")
    abstract suspend fun updateTierLabel(tierId: String, label: String)

    @Query("UPDATE tiers SET colorArgb = :colorArgb WHERE id = :tierId")
    abstract suspend fun updateTierColor(tierId: String, colorArgb: Long?)

    @Query("UPDATE tiers SET position = :position WHERE id = :tierId")
    abstract suspend fun updateTierPosition(tierId: String, position: Int)

    @Query("DELETE FROM tiers WHERE id = :tierId")
    abstract suspend fun deleteTier(tierId: String)

    /**
     * Rewrites only the user-editable scalar columns of a tea. Leaves shortBlurb (catalog/AI)
     * untouched and never touches placements: edits to a tea ripple to every board it sits on.
     */
    @Query(
        "UPDATE teas SET nameRu = :nameRu, nameZh = :nameZh, pinyin = :pinyin, " +
            "nameEn = :nameEn, type = :type, origin = :origin, notes = :notes WHERE id = :teaId",
    )
    abstract suspend fun updateTeaFields(
        teaId: String,
        nameRu: String,
        nameZh: String?,
        pinyin: String?,
        nameEn: String?,
        type: String,
        origin: String?,
        notes: String?,
    )

    /** The raw tea row (no children) — used by the enrichment patch to merge non-blank fields. */
    @Query("SELECT * FROM teas WHERE id = :teaId")
    abstract suspend fun loadTeaRow(teaId: String): TeaEntity?

    /** Flips only the enrichment lifecycle column (PENDING/QUEUED/DONE/FAILED) — no field overwrite. */
    @Query("UPDATE teas SET enrichmentState = :state WHERE id = :teaId")
    abstract suspend fun updateEnrichmentState(teaId: String, state: String)

    /**
     * Patches the catalog-derived fields after a successful resolve and stamps the final state.
     * Names/type/origin/blurb are enriched suggestions (#21); the caller merges them with the
     * user's typed values (keeping a non-blank user value when the catalog has none) before writing.
     */
    @Query(
        "UPDATE teas SET nameRu = :nameRu, nameZh = :nameZh, pinyin = :pinyin, nameEn = :nameEn, " +
            "type = :type, origin = :origin, shortBlurb = :shortBlurb, catalogTeaId = :catalogTeaId, " +
            "enrichmentState = :state WHERE id = :teaId",
    )
    abstract suspend fun patchEnrichment(
        teaId: String,
        nameRu: String,
        nameZh: String?,
        pinyin: String?,
        nameEn: String?,
        type: String,
        origin: String?,
        shortBlurb: String?,
        catalogTeaId: Long?,
        state: String,
    )

    /** Teas left mid-enrichment by a prior run (process death / offline) — re-dispatched on launch. */
    @Query("SELECT * FROM teas WHERE enrichmentState IN ('PENDING', 'QUEUED')")
    abstract suspend fun teasNeedingEnrichment(): List<TeaEntity>

    @Query("DELETE FROM tea_flavors WHERE teaId = :teaId")
    abstract suspend fun deleteFlavorsFor(teaId: String)

    @Query("DELETE FROM purchase_locations WHERE teaId = :teaId")
    abstract suspend fun deletePurchasesFor(teaId: String)

    // --- Full-database export / replace (decisions.md #26) -------------------------------------
    // Plain table dumps, no relation assembly: the backup serializer rebuilds the graph from ids,
    // which keeps the JSON schema a faithful 1:1 of the tables (placement positions/tierIds round
    // trip) and decoupled from the read-side aggregates.

    @Query("SELECT * FROM boards")
    abstract suspend fun allBoards(): List<BoardEntity>

    @Query("SELECT * FROM tiers")
    abstract suspend fun allTiers(): List<TierEntity>

    @Query("SELECT * FROM teas")
    abstract suspend fun allTeaRows(): List<TeaEntity>

    @Query("SELECT * FROM placements")
    abstract suspend fun allPlacements(): List<PlacementEntity>

    @Query("SELECT * FROM tea_flavors")
    abstract suspend fun allFlavors(): List<FlavorEntity>

    @Query("SELECT * FROM purchase_locations")
    abstract suspend fun allPurchases(): List<PurchaseLocationEntity>

    @Query("SELECT * FROM tea_photos")
    abstract suspend fun allPhotos(): List<PhotoEntity>

    @Query("DELETE FROM boards")
    abstract suspend fun deleteAllBoards()

    @Query("DELETE FROM teas")
    abstract suspend fun deleteAllTeas()

    @Transaction
    open suspend fun seed(
        boards: List<BoardEntity>,
        tiers: List<TierEntity>,
        teas: List<TeaEntity>,
        placements: List<PlacementEntity>,
        flavors: List<FlavorEntity>,
        purchases: List<PurchaseLocationEntity>,
        photos: List<PhotoEntity>,
    ) {
        insertBoards(boards)
        insertTiers(tiers)
        insertTeas(teas)
        insertPlacements(placements)
        insertFlavors(flavors)
        insertPurchases(purchases)
        if (photos.isNotEmpty()) insertPhotos(photos)
    }

    /** One consistent read of every table for the export bundle (decisions.md #26). */
    @Transaction
    open suspend fun exportSnapshot(): SeedEntities = SeedEntities(
        boards = allBoards(),
        tiers = allTiers(),
        teas = allTeaRows(),
        placements = allPlacements(),
        flavors = allFlavors(),
        purchases = allPurchases(),
        photos = allPhotos(),
    )

    /**
     * Destructive restore (decisions.md #26): wipes the whole catalog and reinserts [data] in one
     * transaction. Deleting boards then teas cascades tiers/placements/flavors/purchases/photos, so
     * a half-applied import can never leave a mix of old and new rows. Photo *files* on disk are
     * the caller's responsibility (restored before this runs; old ones are orphaned).
     */
    @Transaction
    open suspend fun replaceAll(data: SeedEntities) {
        deleteAllBoards()
        deleteAllTeas()
        seed(
            data.boards,
            data.tiers,
            data.teas,
            data.placements,
            data.flavors,
            data.purchases,
            data.photos,
        )
    }

    /**
     * Atomic board create: inserts the board row and its seeded tiers (may be empty for a blank
     * template) so an interrupted write never leaves a board without its template's tiers.
     */
    @Transaction
    open suspend fun createBoardWithTiers(board: BoardEntity, tiers: List<TierEntity>) {
        insertBoards(listOf(board))
        if (tiers.isNotEmpty()) insertTiers(tiers)
    }

    /**
     * Atomic add: when [tea] is non-null we insert a brand-new user-tea + its flavor and
     * purchase rows; when null the placement attaches to an existing user-tea (auto-link by
     * name, decisions.md #42), so we deliberately leave the existing tea's fields untouched.
     */
    @Transaction
    open suspend fun addTea(
        tea: TeaEntity?,
        flavors: List<FlavorEntity>,
        purchases: List<PurchaseLocationEntity>,
        placement: PlacementEntity,
    ) {
        if (tea != null) {
            insertTeas(listOf(tea))
            insertFlavors(flavors)
            insertPurchases(purchases)
        }
        insertPlacements(listOf(placement))
    }

    /** Rewrites tier + order for every affected placement in one transaction (drag-to-rank). */
    @Transaction
    open suspend fun applyPlacements(moves: List<PlacementMove>) {
        moves.forEach { updatePlacement(it.placementId, it.tierId, it.position) }
    }

    /** Rewrites the order of every reordered tier in one transaction. */
    @Transaction
    open suspend fun reorderTiers(positions: List<TierPosition>) {
        positions.forEach { updateTierPosition(it.tierId, it.position) }
    }

    /**
     * Deletes a tier and reassigns its placements to the unranked tray in one transaction.
     * Placements have no FK to tiers (only a nullable `tierId` column), so the orphaned ones
     * must be moved explicitly or they would disappear from the board.
     */
    @Transaction
    open suspend fun removeTier(tierId: String, reassignedPlacements: List<PlacementMove>) {
        applyPlacements(reassignedPlacements)
        deleteTier(tierId)
    }

    /**
     * Removes a single placement (one tea on one board); the user-tea row stays so any other
     * boards keep their copy. Used by the per-card "Убрать с подборки" action.
     */
    @Transaction
    open suspend fun removePlacement(placementId: String) {
        deletePlacement(placementId)
    }

    /**
     * Deletes the user-tea everywhere: the row goes, FK cascade drops all placements + flavors
     * + purchases. Used by the "Удалить чай совсем" action on the detail / edit screens.
     */
    @Transaction
    open suspend fun deleteTea(teaId: String) {
        deleteTeaRow(teaId)
    }

    /**
     * Updates a user-tea's fields and replaces its flavor + purchase rows in one transaction.
     * Child rows are wholesale rebuilt (delete + insert) because edits can add, remove, or
     * reorder any number of them — simpler and just as cheap as diffing for the small lists.
     * Placements and photos stay untouched: edits ripple, drag-to-rank and the photo strip
     * own their own writes (decisions.md #43).
     */
    @Transaction
    open suspend fun updateTea(
        teaId: String,
        nameRu: String,
        nameZh: String?,
        pinyin: String?,
        nameEn: String?,
        type: String,
        origin: String?,
        notes: String?,
        flavors: List<FlavorEntity>,
        purchases: List<PurchaseLocationEntity>,
    ) {
        updateTeaFields(teaId, nameRu, nameZh, pinyin, nameEn, type, origin, notes)
        deleteFlavorsFor(teaId)
        deletePurchasesFor(teaId)
        insertFlavors(flavors)
        insertPurchases(purchases)
    }

    /**
     * Inserts one photo row at the next available position. The repository copies the bytes
     * to disk first so the URI is already a stable absolute path by the time we write the row.
     */
    @Transaction
    open suspend fun addPhoto(photo: PhotoEntity) {
        insertPhotos(listOf(photo))
    }

    /**
     * Deletes one photo row and contiguously renumbers the survivors so positions stay 0..n
     * for the strip layout. The repository deletes the file on disk separately (best-effort,
     * outside the DB transaction) — keeping I/O outside the @Transaction avoids holding the
     * SQLite write lock for the duration of a filesystem call.
     */
    @Transaction
    open suspend fun removePhoto(photoId: String, survivingOrder: List<PhotoPosition>) {
        deletePhotoRow(photoId)
        survivingOrder.forEach { updatePhotoPosition(it.photoId, it.position) }
    }

    /** Rewrites the order of every photo in the strip in one transaction. */
    @Transaction
    open suspend fun applyPhotoPositions(positions: List<PhotoPosition>) {
        positions.forEach { updatePhotoPosition(it.photoId, it.position) }
    }
}
