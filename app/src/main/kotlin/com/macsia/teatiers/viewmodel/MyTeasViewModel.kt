package com.macsia.teatiers.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.macsia.teatiers.data.repository.TeaBoardRepository
import com.macsia.teatiers.domain.model.TeaType
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
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
    private val sort = MutableStateFlow(MyTeasSortOption.NAME)

    fun setQuery(value: String) {
        query.value = value
    }

    /** Tapping the active chip clears the filter; tapping another switches to it. */
    fun toggleType(type: TeaType) {
        typeFilter.value = if (typeFilter.value == type) null else type
    }

    /** The explicit "All" chip: drop any type filter. */
    fun clearTypeFilter() {
        typeFilter.value = null
    }

    /** Switches the ordering (UX-F-2). */
    fun setSort(option: MyTeasSortOption) {
        sort.value = option
    }

    // UX2-P2-16: filtering runs a per-word Levenshtein pass per tea per field (MyTeasModels.filterMyTeas)
    // on every keystroke today, unlike the catalog search box which is explicitly debounced. Debouncing
    // the *filter input* only (not `query` itself, which stays immediate so the text field never lags)
    // mirrors that same pattern: `filtered` recomputes only once typing settles, while `state` below
    // still re-fires on every keystroke via the un-debounced `query` to keep the displayed text live.
    @OptIn(FlowPreview::class)
    private val filtered: StateFlow<FilteredTeas> = combine(
        repository.allTeas,
        repository.boards,
        query.debounce { q -> if (q.isEmpty()) 0L else CATALOG_SEARCH_DEBOUNCE_MS },
        typeFilter,
        sort,
    ) { teas, boards, q, type, sortOption ->
        val counts = placementCounts(boards)
        FilteredTeas(
            items = filterMyTeas(teas, q, type, sortOption).map { MyTeaItem(it, counts[it.id] ?: 0) },
            availableTypes = TeaType.entries.filter { candidate -> teas.any { it.type == candidate } },
            collectionEmpty = teas.isEmpty(),
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), FilteredTeas())

    val state: StateFlow<MyTeasUiState> = combine(query, typeFilter, sort, filtered) { q, type, sortOption, f ->
        MyTeasUiState(
            query = q,
            typeFilter = type,
            sort = sortOption,
            items = f.items,
            availableTypes = f.availableTypes,
            collectionEmpty = f.collectionEmpty,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), MyTeasUiState())

    private data class FilteredTeas(
        val items: List<MyTeaItem> = emptyList(),
        val availableTypes: List<TeaType> = emptyList(),
        val collectionEmpty: Boolean = true,
    )
}
