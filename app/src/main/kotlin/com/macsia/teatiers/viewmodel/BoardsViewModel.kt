package com.macsia.teatiers.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.macsia.teatiers.R
import com.macsia.teatiers.data.repository.TeaBoardRepository
import com.macsia.teatiers.data.repository.TeaEnrichmentManager
import com.macsia.teatiers.domain.model.TierTemplate
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/** Holds the boards-list state (the app's home), derived from the repository. */
@HiltViewModel
class BoardsViewModel @Inject constructor(
    private val repository: TeaBoardRepository,
    enrichmentManager: TeaEnrichmentManager,
) : ViewModel() {

    private val eventHost = UiEventHost()
    val events get() = eventHost.events

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

    /**
     * Creates a board with the given label and the tier set prescribed by [template]. Blank
     * labels are filtered out by the repository, so the dialog can dispatch optimistically.
     */
    fun createBoard(label: String, template: TierTemplate) {
        viewModelScope.launch {
            runCatching { repository.createBoard(label, template) }
                .onFailure { eventHost.emit(UiEvent.ShowSnackbar(R.string.error_generic)) }
        }
    }
}
