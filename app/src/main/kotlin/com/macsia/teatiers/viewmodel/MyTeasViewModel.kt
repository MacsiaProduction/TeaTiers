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
            typeFilter = type,
            sort = sortOption,
            items = filterMyTeas(teas, q, type, sortOption).map { MyTeaItem(it, counts[it.id] ?: 0) },
            availableTypes = TeaType.entries.filter { candidate -> teas.any { it.type == candidate } },
            collectionEmpty = teas.isEmpty(),
            loading = false, // the combine only runs once allTeas has emitted, so the load is done
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), FilteredTeas())

    // `typeFilter`/`sort` come from `filtered` itself, not the raw MutableStateFlows a second time:
    // two independent combine()s both reading the same upstream flows have no ordering guarantee
    // between them, so `state` could otherwise emit a transient frame where the chip/sort already
    // looks selected but `items` still reflects the previous selection (post-merge review). Reading
    // them off `filtered`'s own already-consistent snapshot ties them to the same emission as `items`.
    val state: StateFlow<MyTeasUiState> = combine(query, filtered) { q, f ->
        MyTeasUiState(
            query = q,
            typeFilter = f.typeFilter,
            sort = f.sort,
            items = f.items,
            availableTypes = f.availableTypes,
            collectionEmpty = f.collectionEmpty,
            loading = f.loading,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), MyTeasUiState())

    private data class FilteredTeas(
        val typeFilter: TeaType? = null,
        val sort: MyTeasSortOption = MyTeasSortOption.NAME,
        val items: List<MyTeaItem> = emptyList(),
        val availableTypes: List<TeaType> = emptyList(),
        val collectionEmpty: Boolean = true,
        val loading: Boolean = true,
    )
}
