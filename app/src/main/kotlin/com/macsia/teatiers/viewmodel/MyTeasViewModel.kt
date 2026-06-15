package com.macsia.teatiers.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.macsia.teatiers.data.repository.TeaBoardRepository
import com.macsia.teatiers.domain.model.TeaType
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

/**
 * Backs the cross-board "my teas" view (decisions.md #27): a searchable, type-filterable list of
 * every user-tea. Read-only — tapping a row opens the shared tea detail, which owns edit/delete.
 */
@HiltViewModel
class MyTeasViewModel @Inject constructor(
    repository: TeaBoardRepository,
) : ViewModel() {

    private val query = MutableStateFlow("")
    private val typeFilter = MutableStateFlow<TeaType?>(null)

    fun setQuery(value: String) {
        query.value = value
    }

    /** Tapping the active chip clears the filter; tapping another switches to it. */
    fun toggleType(type: TeaType) {
        typeFilter.value = if (typeFilter.value == type) null else type
    }

    val state: StateFlow<MyTeasUiState> = combine(
        repository.allTeas,
        repository.boards,
        query,
        typeFilter,
    ) { teas, boards, q, type ->
        val counts = placementCounts(boards)
        MyTeasUiState(
            query = q,
            typeFilter = type,
            items = filterMyTeas(teas, q, type).map { MyTeaItem(it, counts[it.id] ?: 0) },
            availableTypes = TeaType.entries.filter { candidate -> teas.any { it.type == candidate } },
            collectionEmpty = teas.isEmpty(),
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), MyTeasUiState())
}
