package com.macsia.teatiers.data.repository

import com.macsia.teatiers.data.sample.SampleBoardProvider
import com.macsia.teatiers.domain.model.Board
import com.macsia.teatiers.domain.model.Tea
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import javax.inject.Inject
import javax.inject.Singleton

/**
 * In-memory single source of truth for boards. Seeded from [SampleBoardProvider] and shared
 * across screens via Hilt's singleton scope so an added tea shows up everywhere immediately.
 * State survives configuration changes but not process death; a Room-backed implementation
 * replaces this in M1 (context/plan.md) behind the same StateFlow surface, so callers don't
 * change.
 */
@Singleton
class TeaBoardRepository @Inject constructor(seed: SampleBoardProvider) {

    private val _boards = MutableStateFlow(seed.boards())
    val boards: StateFlow<List<Board>> = _boards.asStateFlow()

    fun board(boardId: String): Board? = _boards.value.firstOrNull { it.id == boardId }

    fun tea(boardId: String, teaId: String): Tea? {
        val board = board(boardId) ?: return null
        return (board.placements.values.flatten() + board.unranked).firstOrNull { it.id == teaId }
    }

    /**
     * Appends [tea] to [boardId]. With a known [tierId] the tea joins that tier; otherwise it
     * lands in the board's unranked teas.
     */
    fun addTea(boardId: String, tea: Tea, tierId: String?) {
        _boards.update { boards ->
            boards.map { board ->
                if (board.id != boardId) {
                    board
                } else if (tierId != null && board.tiers.any { it.id == tierId }) {
                    val placements = board.placements.toMutableMap()
                    placements[tierId] = placements[tierId].orEmpty() + tea
                    board.copy(placements = placements)
                } else {
                    board.copy(unranked = board.unranked + tea)
                }
            }
        }
    }
}
