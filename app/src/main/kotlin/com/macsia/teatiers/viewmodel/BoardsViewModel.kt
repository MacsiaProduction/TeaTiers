package com.macsia.teatiers.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.macsia.teatiers.data.repository.TeaBoardRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

/** Holds the boards-list state (the app's home), derived from the repository. */
@HiltViewModel
class BoardsViewModel @Inject constructor(
    repository: TeaBoardRepository,
) : ViewModel() {

    val boards: StateFlow<List<BoardSummary>> = repository.boards
        .map { boards -> boards.map { it.toSummary() } }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            // Seed from the current snapshot so the list never flashes empty on open.
            initialValue = repository.boards.value.map { it.toSummary() },
        )
}
