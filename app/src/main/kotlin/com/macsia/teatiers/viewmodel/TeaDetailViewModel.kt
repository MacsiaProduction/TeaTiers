package com.macsia.teatiers.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.macsia.teatiers.data.repository.TeaBoardRepository
import com.macsia.teatiers.domain.model.Tea
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Resolves the user-tea identified by [bind] (decisions.md #42 — board-agnostic). The flow
 * re-runs the lookup whenever the repository's boards emit so an edit on any board ripples to
 * this screen too.
 */
@HiltViewModel
class TeaDetailViewModel @Inject constructor(
    private val repository: TeaBoardRepository,
) : ViewModel() {

    private val teaId = MutableStateFlow<String?>(null)

    fun bind(teaId: String) {
        this.teaId.value = teaId
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    val tea: StateFlow<Tea?> = teaId
        .flatMapLatest { id ->
            if (id == null) flowOf(null)
            // mapLatest so a re-emit of `boards` cancels the in-flight lookup; fine because
            // `repository.tea` is mostly an in-memory hit anyway.
            else repository.boards.mapLatest { repository.tea(id) }
        }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = null,
        )

    /**
     * Deletes the user-tea everywhere from the detail screen — every board the tea sits on
     * loses its placement (FK cascade). Destructive; UI gates this behind a confirm dialog.
     */
    fun deleteTea(onDeleted: () -> Unit) {
        val id = teaId.value ?: return
        viewModelScope.launch {
            repository.deleteTea(id)
            onDeleted()
        }
    }
}
