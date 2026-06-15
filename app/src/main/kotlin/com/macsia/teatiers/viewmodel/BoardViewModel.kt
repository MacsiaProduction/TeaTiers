package com.macsia.teatiers.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.macsia.teatiers.R
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

    private val eventHost = UiEventHost()
    val events get() = eventHost.events

    private val boardId = MutableStateFlow<String?>(null)

    fun bind(id: String) {
        boardId.value = id
    }

    /** Wraps a repo mutation; surfaces a generic snackbar on failure so silent drops do not
     *  hide bugs from the user. Local helper, kept private so VMs do not catch each other. */
    private fun guarded(block: suspend () -> Unit) = viewModelScope.launch {
        runCatching { block() }
            .onFailure { eventHost.emit(UiEvent.ShowSnackbar(R.string.error_generic)) }
    }

    val uiState: StateFlow<BoardUiState?> = combine(repository.boards, boardId) { boards, id ->
        id?.let { boards.firstOrNull { board -> board.id == it }?.toUiState() }
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = null,
    )

    /**
     * Re-ranks a placement after a drag (or an accessibility move action). [targetTierId] is null
     * for the unranked tray; [targetIndex] is the slot among the other placements in the target
     * group. The Room write re-emits [uiState] with the new order. Identified by placementId so
     * the same user-tea on a different board is not affected (decisions.md #42).
     */
    fun movePlacement(placementId: String, targetTierId: String?, targetIndex: Int) {
        val board = boardId.value ?: return
        guarded { repository.moveTea(board, placementId, targetTierId, targetIndex) }
    }

    /**
     * Removes one placement from this board (the user-tea row stays so any other boards keep
     * their copy). Used by the per-card "Убрать с подборки" action.
     */
    fun removePlacement(placementId: String) {
        guarded { repository.removePlacement(placementId) }
    }

    /**
     * Deletes the user-tea everywhere — every board the tea was placed on loses its placement
     * via FK cascade. Destructive; UI gates this behind a confirmation dialog.
     */
    fun deleteTea(teaId: String) {
        guarded { repository.deleteTea(teaId) }
    }

    fun addTier(label: String) {
        val board = boardId.value ?: return
        guarded { repository.addTier(board, label) }
    }

    fun renameTier(tierId: String, label: String) {
        val board = boardId.value ?: return
        guarded { repository.renameTier(board, tierId, label) }
    }

    fun setTierColor(tierId: String, colorArgb: Long?) {
        val board = boardId.value ?: return
        guarded { repository.setTierColor(board, tierId, colorArgb) }
    }

    fun reorderTiers(orderedTierIds: List<String>) {
        val board = boardId.value ?: return
        guarded { repository.reorderTiers(board, orderedTierIds) }
    }

    fun removeTier(tierId: String) {
        val board = boardId.value ?: return
        guarded { repository.removeTier(board, tierId) }
    }
}
