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

    @Query("DELETE FROM tea_flavors WHERE teaId = :teaId")
    abstract suspend fun deleteFlavorsFor(teaId: String)

    @Query("DELETE FROM purchase_locations WHERE teaId = :teaId")
    abstract suspend fun deletePurchasesFor(teaId: String)

    @Transaction
    open suspend fun seed(
        boards: List<BoardEntity>,
        tiers: List<TierEntity>,
        teas: List<TeaEntity>,
        placements: List<PlacementEntity>,
        flavors: List<FlavorEntity>,
        purchases: List<PurchaseLocationEntity>,
    ) {
        insertBoards(boards)
        insertTiers(tiers)
        insertTeas(teas)
        insertPlacements(placements)
        insertFlavors(flavors)
        insertPurchases(purchases)
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
     * Placements stay untouched: edits ripple, drag-to-rank state does not.
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
}
