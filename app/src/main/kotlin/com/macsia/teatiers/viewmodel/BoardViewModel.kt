package com.macsia.teatiers.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.macsia.teatiers.data.repository.TeaBoardRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Board-detail state for the board identified by [bind]. Emits null until a board is bound and
 * when the id is unknown, so the screen can show a placeholder. Reacts to repository edits
 * (e.g. a newly added tea, or a drag-to-rank move) automatically.
 */
@HiltViewModel
class BoardViewModel @Inject constructor(
    private val repository: TeaBoardRepository,
) : ViewModel() {

    private val boardId = MutableStateFlow<String?>(null)

    fun bind(id: String) {
        boardId.value = id
    }

    val uiState: StateFlow<BoardUiState?> = combine(repository.boards, boardId) { boards, id ->
        id?.let { boards.firstOrNull { board -> board.id == it }?.toUiState() }
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = null,
    )

    /**
     * Re-ranks a tea after a drag (or an accessibility move action). [targetTierId] is null for the
     * unranked tray; [targetIndex] is the slot among the other teas in the target group. The Room
     * write re-emits [uiState] with the new order.
     */
    fun moveTea(teaId: String, targetTierId: String?, targetIndex: Int) {
        val board = boardId.value ?: return
        viewModelScope.launch { repository.moveTea(board, teaId, targetTierId, targetIndex) }
    }
}
