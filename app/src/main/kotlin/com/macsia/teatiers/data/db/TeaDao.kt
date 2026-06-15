package com.macsia.teatiers.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Transaction
import kotlinx.coroutines.flow.Flow

/** Where one tea should sit after a move: its tier (null = the unranked tray) and order within it. */
data class TeaPlacement(val teaId: String, val tierId: String?, val position: Int)

/** A tier's new order within its board, used when the tier list is reordered. */
data class TierPosition(val tierId: String, val position: Int)

/**
 * Abstract class (not interface) so the multi-table writes below can run inside a single
 * @Transaction default method.
 */
@Dao
abstract class TeaDao {

    @Transaction
    @Query("SELECT * FROM boards ORDER BY position")
    abstract fun observeBoards(): Flow<List<BoardWithChildren>>

    @Query("SELECT COUNT(*) FROM boards")
    abstract suspend fun boardCount(): Int

    @Query("SELECT COALESCE(MAX(position), -1) + 1 FROM teas WHERE boardId = :boardId")
    abstract suspend fun nextTeaPosition(boardId: String): Int

    @Query("SELECT COALESCE(MAX(position), -1) + 1 FROM tiers WHERE boardId = :boardId")
    abstract suspend fun nextTierPosition(boardId: String): Int

    @Insert
    abstract suspend fun insertBoards(boards: List<BoardEntity>)

    @Insert
    abstract suspend fun insertTiers(tiers: List<TierEntity>)

    @Insert
    abstract suspend fun insertTeas(teas: List<TeaEntity>)

    @Insert
    abstract suspend fun insertFlavors(flavors: List<FlavorEntity>)

    @Insert
    abstract suspend fun insertPurchases(purchases: List<PurchaseLocationEntity>)

    @Query("UPDATE teas SET tierId = :tierId, position = :position WHERE id = :teaId")
    abstract suspend fun updateTeaPlacement(teaId: String, tierId: String?, position: Int)

    @Query("UPDATE tiers SET label = :label WHERE id = :tierId")
    abstract suspend fun updateTierLabel(tierId: String, label: String)

    @Query("UPDATE tiers SET colorArgb = :colorArgb WHERE id = :tierId")
    abstract suspend fun updateTierColor(tierId: String, colorArgb: Long?)

    @Query("UPDATE tiers SET position = :position WHERE id = :tierId")
    abstract suspend fun updateTierPosition(tierId: String, position: Int)

    @Query("DELETE FROM tiers WHERE id = :tierId")
    abstract suspend fun deleteTier(tierId: String)

    /**
     * Rewrites only the user-editable scalar columns of a tea. Leaves boardId/tierId/position
     * (drag-to-rank state) and shortBlurb (catalog/AI-derived) untouched so editing the form
     * never reorders the board.
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
        flavors: List<FlavorEntity>,
        purchases: List<PurchaseLocationEntity>,
    ) {
        insertBoards(boards)
        insertTiers(tiers)
        insertTeas(teas)
        insertFlavors(flavors)
        insertPurchases(purchases)
    }

    @Transaction
    open suspend fun addTea(
        tea: TeaEntity,
        flavors: List<FlavorEntity>,
        purchases: List<PurchaseLocationEntity>,
    ) {
        insertTeas(listOf(tea))
        insertFlavors(flavors)
        insertPurchases(purchases)
    }

    /** Rewrites tier + order for every affected tea in one transaction (used by drag-to-rank). */
    @Transaction
    open suspend fun applyPlacements(placements: List<TeaPlacement>) {
        placements.forEach { updateTeaPlacement(it.teaId, it.tierId, it.position) }
    }

    /** Rewrites the order of every reordered tier in one transaction. */
    @Transaction
    open suspend fun reorderTiers(positions: List<TierPosition>) {
        positions.forEach { updateTierPosition(it.tierId, it.position) }
    }

    /**
     * Deletes a tier and reassigns its teas to the unranked tray in one transaction. Teas have no
     * FK to tiers (only a nullable `tierId` column), so the orphaned teas must be moved explicitly
     * or they would disappear from the board.
     */
    @Transaction
    open suspend fun removeTier(tierId: String, reassignedTeas: List<TeaPlacement>) {
        applyPlacements(reassignedTeas)
        deleteTier(tierId)
    }

    /**
     * Updates a tea's editable fields and replaces its flavor + purchase rows in one transaction.
     * Child rows are wholesale rebuilt (delete + insert) because edits can add, remove, or reorder
     * any number of them — simpler and just as cheap as diffing for the small lists involved.
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
