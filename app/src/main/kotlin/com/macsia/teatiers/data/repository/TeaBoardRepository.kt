package com.macsia.teatiers.data.repository

import com.macsia.teatiers.data.db.BoardWithChildren
import com.macsia.teatiers.data.db.TeaDao
import com.macsia.teatiers.data.db.TeaPlacement
import com.macsia.teatiers.data.db.toDomain
import com.macsia.teatiers.data.db.toEntities
import com.macsia.teatiers.data.db.toSeedEntities
import com.macsia.teatiers.data.sample.SampleBoardProvider
import com.macsia.teatiers.di.AppScope
import com.macsia.teatiers.domain.model.Board
import com.macsia.teatiers.domain.model.Tea
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Room-backed single source of truth for boards (M1). Boards are exposed as a hot [StateFlow]
 * collected on the app scope so the synchronous reads below see loaded data and an added tea
 * shows up on every screen at once. State now survives process death; the public surface is
 * unchanged from the Phase 0 in-memory version, so callers did not change.
 *
 * On first run (empty DB) the store is seeded from [SampleBoardProvider]; later runs read what
 * the user saved.
 */
@Singleton
class TeaBoardRepository @Inject constructor(
    private val dao: TeaDao,
    @AppScope private val scope: CoroutineScope,
    seed: SampleBoardProvider,
) {

    val boards: StateFlow<List<Board>> = dao.observeBoards()
        .map { rows -> rows.map(BoardWithChildren::toDomain) }
        .stateIn(scope, SharingStarted.Eagerly, emptyList())

    init {
        scope.launch {
            if (dao.boardCount() == 0) {
                val entities = seed.boards().toSeedEntities()
                dao.seed(entities.boards, entities.tiers, entities.teas, entities.flavors, entities.purchases)
            }
        }
    }

    fun board(boardId: String): Board? = boards.value.firstOrNull { it.id == boardId }

    fun tea(boardId: String, teaId: String): Tea? {
        val board = board(boardId) ?: return null
        return (board.placements.values.flatten() + board.unranked).firstOrNull { it.id == teaId }
    }

    /**
     * Appends [tea] to [boardId]. With a known [tierId] the tea joins that tier; an unknown tier
     * (or null) lands it in the unranked tray.
     */
    suspend fun addTea(boardId: String, tea: Tea, tierId: String?) {
        val resolvedTier = tierId?.takeIf { id -> board(boardId)?.tiers?.any { it.id == id } == true }
        val position = dao.nextTeaPosition(boardId)
        val entities = tea.toEntities(rowId = tea.id, boardId = boardId, tierId = resolvedTier, position = position)
        dao.addTea(entities.tea, entities.flavors, entities.purchases)
    }

    /**
     * Drag-to-rank: moves [teaId] within [boardId] to [targetTierId] (null = the unranked tray) at
     * [targetIndex], the slot among the *other* teas in the target group. An unknown tier falls back
     * to the tray. No-op when the board or tea is unknown, or the placement would not change.
     */
    suspend fun moveTea(boardId: String, teaId: String, targetTierId: String?, targetIndex: Int) {
        val board = board(boardId) ?: return
        val placements = computeMovePlacements(board, teaId, targetTierId, targetIndex)
        if (placements.isNotEmpty()) dao.applyPlacements(placements)
    }
}

/**
 * Pure reorder math for [TeaBoardRepository.moveTea], split out so it is unit-testable without a
 * DAO. Removes [teaId] from its current group, inserts it into the target group (an unknown tier
 * becomes the tray) at [targetIndex] clamped into range, and returns contiguous 0..n positions for
 * every tea in the affected group(s). Ordering within a group is all that matters — [toDomain]
 * sorts by position inside each group — so only touched groups are renumbered. Returns empty when
 * the move changes nothing (tea absent, or already at that exact slot).
 */
internal fun computeMovePlacements(
    board: Board,
    teaId: String,
    targetTierId: String?,
    targetIndex: Int,
): List<TeaPlacement> {
    val target = if (targetTierId != null && board.tiers.any { tier -> tier.id == targetTierId }) targetTierId else null

    val groups = LinkedHashMap<String?, MutableList<String>>()
    board.tiers.forEach { tier -> groups[tier.id] = board.placements[tier.id].orEmpty().map { it.id }.toMutableList() }
    groups[null] = board.unranked.map { it.id }.toMutableList()

    // Resolve the source entry before reading its key: the tray's key is null, so an elvis on
    // `?.key` would treat "tea sits in the tray" the same as "tea not found" and bail out.
    val sourceEntry = groups.entries.firstOrNull { it.value.contains(teaId) } ?: return emptyList()
    val sourceKey = sourceEntry.key
    val sourceList = sourceEntry.value
    val sourceIndex = sourceList.indexOf(teaId)
    sourceList.remove(teaId)

    val targetList = groups.getValue(target)
    val index = targetIndex.coerceIn(0, targetList.size)
    if (sourceKey == target && index == sourceIndex) return emptyList()
    targetList.add(index, teaId)

    return setOf(sourceKey, target).flatMap { key ->
        groups.getValue(key).mapIndexed { position, id -> TeaPlacement(id, key, position) }
    }
}
