package com.macsia.teatiers.data.repository

import com.macsia.teatiers.data.db.BoardWithChildren
import com.macsia.teatiers.data.db.TeaDao
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
}
