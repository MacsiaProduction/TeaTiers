package com.macsia.teatiers.data.repository

import com.macsia.teatiers.data.db.BoardEntity
import com.macsia.teatiers.data.db.BoardWithChildren
import com.macsia.teatiers.data.db.FlavorEntity
import com.macsia.teatiers.data.db.PurchaseLocationEntity
import com.macsia.teatiers.data.db.TeaDao
import com.macsia.teatiers.data.db.TeaEntity
import com.macsia.teatiers.data.db.TeaWithChildren
import com.macsia.teatiers.data.db.TierEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * In-memory [TeaDao] for JVM unit tests. The inherited @Transaction methods (seed/addTea) run
 * unchanged on top of these inserts; [observeBoards] re-emits the rebuilt aggregate after each
 * write, mirroring how Room's relation query reacts to table changes.
 */
class FakeTeaDao : TeaDao() {

    private val boards = mutableListOf<BoardEntity>()
    private val tiers = mutableListOf<TierEntity>()
    private val teas = mutableListOf<TeaEntity>()
    private val flavors = mutableListOf<FlavorEntity>()
    private val purchases = mutableListOf<PurchaseLocationEntity>()

    private val state = MutableStateFlow<List<BoardWithChildren>>(emptyList())

    override fun observeBoards(): Flow<List<BoardWithChildren>> = state

    override suspend fun boardCount(): Int = boards.size

    override suspend fun nextTeaPosition(boardId: String): Int =
        (teas.filter { it.boardId == boardId }.maxOfOrNull { it.position } ?: -1) + 1

    override suspend fun nextTierPosition(boardId: String): Int =
        (tiers.filter { it.boardId == boardId }.maxOfOrNull { it.position } ?: -1) + 1

    override suspend fun insertBoards(boards: List<BoardEntity>) {
        this.boards += boards
        refresh()
    }

    override suspend fun insertTiers(tiers: List<TierEntity>) {
        this.tiers += tiers
        refresh()
    }

    override suspend fun insertTeas(teas: List<TeaEntity>) {
        this.teas += teas
        refresh()
    }

    override suspend fun insertFlavors(flavors: List<FlavorEntity>) {
        this.flavors += flavors
        refresh()
    }

    override suspend fun insertPurchases(purchases: List<PurchaseLocationEntity>) {
        this.purchases += purchases
        refresh()
    }

    override suspend fun updateTeaPlacement(teaId: String, tierId: String?, position: Int) {
        val index = teas.indexOfFirst { it.id == teaId }
        if (index >= 0) {
            teas[index] = teas[index].copy(tierId = tierId, position = position)
            refresh()
        }
    }

    override suspend fun updateTierLabel(tierId: String, label: String) {
        updateTier(tierId) { it.copy(label = label) }
    }

    override suspend fun updateTierColor(tierId: String, colorArgb: Long?) {
        updateTier(tierId) { it.copy(colorArgb = colorArgb) }
    }

    override suspend fun updateTierPosition(tierId: String, position: Int) {
        updateTier(tierId) { it.copy(position = position) }
    }

    override suspend fun deleteTier(tierId: String) {
        if (tiers.removeAll { it.id == tierId }) refresh()
    }

    private fun updateTier(tierId: String, transform: (TierEntity) -> TierEntity) {
        val index = tiers.indexOfFirst { it.id == tierId }
        if (index >= 0) {
            tiers[index] = transform(tiers[index])
            refresh()
        }
    }

    private fun refresh() {
        state.value = boards.sortedBy { it.position }.map { board ->
            BoardWithChildren(
                board = board,
                tiers = tiers.filter { it.boardId == board.id },
                teas = teas.filter { it.boardId == board.id }.map { tea ->
                    TeaWithChildren(
                        tea = tea,
                        flavors = flavors.filter { it.teaId == tea.id },
                        purchases = purchases.filter { it.teaId == tea.id },
                    )
                },
            )
        }
    }
}
