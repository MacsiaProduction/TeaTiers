package com.macsia.teatiers.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.macsia.teatiers.R
import com.macsia.teatiers.data.db.PlacementEntity
import com.macsia.teatiers.data.repository.DeletedTea
import com.macsia.teatiers.data.repository.DeletedTier
import com.macsia.teatiers.data.repository.TeaBoardRepository
import com.macsia.teatiers.data.repository.TeaEnrichmentManager
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
    private val enrichmentManager: TeaEnrichmentManager,
) : ViewModel() {

    private val eventHost = UiEventHost()
    val events get() = eventHost.events

    private val boardId = MutableStateFlow<String?>(null)

    fun bind(id: String) {
        boardId.value = id
        // Re-dispatch any tea left PENDING/QUEUED by a prior run (process death / offline), so a tea
        // is never stuck "enriching" (#28). UX2-P2-18: no per-VM-instance gate here — the manager's
        // own cooldown (resumeCooldownMs) is the single throttle across every board-open / screen,
        // so a per-VM "once" flag on top of it only made the narrower case (same board reopened after
        // reconnecting, within the VM's lifetime) harder to self-heal.
        enrichmentManager.resumePending()
    }

    /** Retries enrichment of a tea whose background resolve FAILED (per-card overflow action, #28). */
    fun retryEnrichment(teaId: String) {
        // UX2-P2-15: a rapid double-tap silently no-op'd on the second tap with zero feedback.
        if (enrichmentManager.isInFlight(teaId)) {
            eventHost.emit(ShowSnackbar(R.string.enrichment_already_retrying))
            return
        }
        enrichmentManager.retry(teaId)
    }

    /** Wraps a repo mutation; surfaces a generic snackbar on failure so silent drops do not
     *  hide bugs from the user. Local helper, kept private so VMs do not catch each other. */
    private fun guarded(block: suspend () -> Unit) = viewModelScope.launch {
        runCatching { block() }
            .onFailure { eventHost.emit(ShowSnackbar(R.string.error_generic)) }
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
     * their copy). Used by the per-card "Убрать с подборки" action. The action is immediate (no
     * confirm dialog); an undo snackbar restores it, which is friendlier than a modal for a
     * reversible change.
     */
    fun removePlacement(placementId: String) = viewModelScope.launch {
        val removed = runCatching { repository.removePlacement(placementId) }
            .getOrElse {
                eventHost.emit(ShowSnackbar(R.string.error_generic))
                return@launch
            }
        if (removed != null) {
            eventHost.emit(
                ShowSnackbar(
                    messageRes = R.string.snackbar_placement_removed,
                    actionLabelRes = R.string.action_undo,
                    onAction = { restorePlacement(removed) },
                ),
            )
        }
    }

    private fun restorePlacement(placement: PlacementEntity) {
        guarded { repository.restorePlacement(placement) }
    }

    /**
     * Deletes the user-tea everywhere — every board the tea was placed on loses its placement via
     * FK cascade. Offers an Undo snackbar that reinstates the tea with its flavors, photos, and
     * every placement. UI also gates this behind a confirmation dialog.
     */
    fun deleteTea(teaId: String) {
        viewModelScope.launch {
            val deleted = runCatching { repository.deleteTea(teaId) }
                .getOrElse {
                    eventHost.emit(ShowSnackbar(R.string.error_generic))
                    return@launch
                }
            if (deleted != null) {
                eventHost.emit(
                    ShowSnackbar(
                        messageRes = R.string.snackbar_tea_deleted,
                        actionLabelRes = R.string.action_undo,
                        onAction = { restoreTea(deleted) },
                    ),
                )
            }
        }
    }

    private fun restoreTea(deleted: DeletedTea) {
        guarded { repository.restoreTea(deleted) }
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

    /**
     * Removes a tier; its placements fall into the unranked tray (they are not deleted). Offers an
     * Undo snackbar that restores the tier and pulls those placements back into it.
     */
    fun removeTier(tierId: String) {
        val board = boardId.value ?: return
        viewModelScope.launch {
            val deleted = runCatching { repository.removeTier(board, tierId) }
                .getOrElse {
                    eventHost.emit(ShowSnackbar(R.string.error_generic))
                    return@launch
                }
            if (deleted != null) {
                eventHost.emit(
                    ShowSnackbar(
                        messageRes = R.string.snackbar_tier_deleted,
                        actionLabelRes = R.string.action_undo,
                        onAction = { restoreTier(deleted) },
                    ),
                )
            }
        }
    }

    private fun restoreTier(deleted: DeletedTier) {
        guarded { repository.restoreTier(deleted) }
    }
}
