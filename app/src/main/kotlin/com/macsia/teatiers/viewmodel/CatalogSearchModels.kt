package com.macsia.teatiers.viewmodel

import com.macsia.teatiers.domain.model.CatalogTea
import com.macsia.teatiers.domain.model.CatalogTeaDetail

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

/**
 * UI state of the catalog detail sheet (M3). The sheet is shown whenever the state is not [Hidden];
 * the user opens it from a search result's info action and may pull the tea into the add form.
 */
sealed interface CatalogDetailUiState {
    /** Sheet closed. */
    data object Hidden : CatalogDetailUiState

    /** Detail request in flight. */
    data object Loading : CatalogDetailUiState

    /** Detail loaded. */
    data class Loaded(val detail: CatalogTeaDetail) : CatalogDetailUiState

    /** The catalog entry was withdrawn (retracted/merged). No content to show; not retryable. */
    data object Withdrawn : CatalogDetailUiState

    /** Network unreachable; the sheet offers a retry. */
    data object Offline : CatalogDetailUiState

    /** Server answered with an error; the sheet offers a retry. */
    data object Error : CatalogDetailUiState
}

/** Shortest query that triggers a catalog search; 1-char queries are too noisy. */
const val MIN_CATALOG_QUERY_LEN = 2

/** Debounce before firing a search, so typing doesn't spam the API (plan §6 "never per keystroke"). */
const val CATALOG_SEARCH_DEBOUNCE_MS = 300L
