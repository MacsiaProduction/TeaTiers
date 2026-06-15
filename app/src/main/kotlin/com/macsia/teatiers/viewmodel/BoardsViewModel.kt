package com.macsia.teatiers.viewmodel

import androidx.lifecycle.ViewModel
import com.macsia.teatiers.data.sample.SampleBoardProvider
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject

/** Holds the boards-list state (the app's home). */
@HiltViewModel
class BoardsViewModel @Inject constructor(
    sampleBoardProvider: SampleBoardProvider,
) : ViewModel() {

    private val _boards = MutableStateFlow(sampleBoardProvider.boards().map { it.toSummary() })
    val boards: StateFlow<List<BoardSummary>> = _boards.asStateFlow()
}
