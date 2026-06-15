package com.macsia.teatiers.viewmodel

import androidx.lifecycle.ViewModel
import com.macsia.teatiers.data.sample.SampleBoardProvider
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject

/**
 * Holds the board screen state. Phase 0 shows the first sample board; board selection by id
 * and editing arrive with navigation + Room in M1.
 */
@HiltViewModel
class BoardViewModel @Inject constructor(
    sampleBoardProvider: SampleBoardProvider,
) : ViewModel() {

    private val _uiState = MutableStateFlow(sampleBoardProvider.boards().first().toUiState())
    val uiState: StateFlow<BoardUiState> = _uiState.asStateFlow()
}
