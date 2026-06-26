package com.macsia.teatiers.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.macsia.teatiers.R
import com.macsia.teatiers.data.repository.DeletedBoard
import com.macsia.teatiers.data.repository.TeaBoardRepository
import com.macsia.teatiers.data.repository.TeaEnrichmentManager
import com.macsia.teatiers.data.settings.SettingsRepository
import com.macsia.teatiers.domain.model.TierTemplate
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/** Holds the boards-list state (the app's home), derived from the repository. */
@HiltViewModel
class BoardsViewModel @Inject constructor(
    private val repository: TeaBoardRepository,
    private val settings: SettingsRepository,
    enrichmentManager: TeaEnrichmentManager,
) : ViewModel() {

    private val eventHost = UiEventHost()
    val events get() = eventHost.events

    // One-shot navigation: a freshly created board is opened so the user lands on it ready to add
    // teas, instead of guessing where it went in the list (audit P2).
    private val createdBoardChannel = Channel<String>(Channel.BUFFERED)
    val createdBoard: Flow<String> = createdBoardChannel.receiveAsFlow()

    init {
        // The boards list is the app's home, so this is the earliest reliable "app opened" hook:
        // re-dispatch any tea left QUEUED (offline) or PENDING (killed mid-enrich) so it never waits
        // for the user to open a specific board (#28). Idempotent — the manager drops in-flight dups.
        enrichmentManager.resumePending()
    }

    val boards: StateFlow<List<BoardSummary>> = repository.boards
        .map { boards -> boards.map { it.toSummary() } }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            // Seed from the current snapshot so the list never flashes empty on open.
            initialValue = repository.boards.value.map { it.toSummary() },
        )

    /** First-run intro card visibility — shown until the user taps "Понятно" (audit: onboarding). */
    val showIntro: StateFlow<Boolean> = settings.introDismissed
        .map { dismissed -> !dismissed }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            // Default hidden so the card fades in once the flag loads rather than flashing then hiding.
            initialValue = false,
        )

    fun dismissIntro() {
        viewModelScope.launch { runCatching { settings.setIntroDismissed() } }
    }

    /**
     * Creates a board with the given label and the tier set prescribed by [template]. Blank
     * labels are filtered out by the repository, so the dialog can dispatch optimistically.
     */
    fun createBoard(label: String, template: TierTemplate) {
        viewModelScope.launch {
            runCatching { repository.createBoard(label, template) }
                .onSuccess { id -> if (id != null) createdBoardChannel.trySend(id) }
                .onFailure { eventHost.emit(ShowSnackbar(R.string.error_generic)) }
        }
    }

    /** Renames a board; blank names are ignored by the repository, so the dialog can dispatch directly. */
    fun renameBoard(boardId: String, name: String) {
        viewModelScope.launch {
            runCatching { repository.renameBoard(boardId, name) }
                .onFailure { eventHost.emit(ShowSnackbar(R.string.error_generic)) }
        }
    }

    /**
     * Deletes a board (tier-list). The board's teas persist (shared, #42); only its arrangement
     * goes. Offers an Undo snackbar that reinstates the whole board from the returned snapshot —
     * deleting a whole arrangement is high-stakes, so the confirm dialog and Undo both guard it.
     */
    fun deleteBoard(boardId: String) {
        viewModelScope.launch {
            val deleted = runCatching { repository.deleteBoard(boardId) }
                .getOrElse {
                    eventHost.emit(ShowSnackbar(R.string.error_generic))
                    return@launch
                }
            if (deleted != null) {
                eventHost.emit(
                    ShowSnackbar(
                        messageRes = R.string.snackbar_board_deleted,
                        actionLabelRes = R.string.action_undo,
                        onAction = { restoreBoard(deleted) },
                    ),
                )
            }
        }
    }

    private fun restoreBoard(deleted: DeletedBoard) {
        viewModelScope.launch {
            runCatching { repository.restoreBoard(deleted) }
                .onFailure { eventHost.emit(ShowSnackbar(R.string.error_generic)) }
        }
    }
}
