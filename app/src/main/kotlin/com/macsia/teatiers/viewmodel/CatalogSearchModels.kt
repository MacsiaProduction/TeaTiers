package com.macsia.teatiers.viewmodel

import com.macsia.teatiers.domain.model.CatalogTea

/** UI state of the add-form catalog search box (M3). */
sealed interface CatalogSearchUiState {
    /** Query blank or too short — the box is idle, no results shown. */
    data object Idle : CatalogSearchUiState

    /** A request is in flight. */
    data object Loading : CatalogSearchUiState

    /** Matches found. [fromCache] is true when these came from the offline cache after a network miss. */
    data class Results(val teas: List<CatalogTea>, val fromCache: Boolean) : CatalogSearchUiState

    /** Server reachable, but nothing matched — offer the "add by hand" path the form already is. */
    data object Empty : CatalogSearchUiState

    /** Network unreachable and nothing cached for this query. */
    data object Offline : CatalogSearchUiState

    /** Server answered with an error. */
    data object Error : CatalogSearchUiState
}

/** Shortest query that triggers a catalog search; 1-char queries are too noisy. */
const val MIN_CATALOG_QUERY_LEN = 2

/** Debounce before firing a search, so typing doesn't spam the API (plan §6 "never per keystroke"). */
const val CATALOG_SEARCH_DEBOUNCE_MS = 300L
