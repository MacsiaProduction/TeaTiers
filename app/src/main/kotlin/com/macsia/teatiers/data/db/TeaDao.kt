package com.macsia.teatiers.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Transaction
import kotlinx.coroutines.flow.Flow

/** Where one tea should sit after a move: its tier (null = the unranked tray) and order within it. */
data class TeaPlacement(val teaId: String, val tierId: String?, val position: Int)

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
}
