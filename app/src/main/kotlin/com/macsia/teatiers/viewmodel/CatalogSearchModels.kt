package com.macsia.teatiers.viewmodel

import com.macsia.teatiers.data.repository.CatalogDetailResult
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

    /** The per-client/edge request budget is spent (UX2-P1-5) — distinct from a generic error. */
    data object RateLimited : CatalogSearchUiState

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

    /** The per-client request budget is spent (UX-P1-6); the sheet offers a retry with a distinct message. */
    data object RateLimited : CatalogDetailUiState

    /** Server answered with an error; the sheet offers a retry. */
    data object Error : CatalogDetailUiState
}

/** Maps a catalog-detail fetch outcome to the sheet's UI state. Shared by the add-form and browse
 *  screens (R4-JRN-2) so both surface the same detail sheet from the same repository call. */
fun CatalogDetailResult.toUiState(): CatalogDetailUiState = when (this) {
    is CatalogDetailResult.Loaded -> CatalogDetailUiState.Loaded(detail)
    is CatalogDetailResult.Retracted -> CatalogDetailUiState.Withdrawn
    CatalogDetailResult.Offline -> CatalogDetailUiState.Offline
    CatalogDetailResult.RateLimited -> CatalogDetailUiState.RateLimited
    CatalogDetailResult.Error -> CatalogDetailUiState.Error
}

/** Shortest query that triggers a catalog search; 1-char Latin/Cyrillic/pinyin queries are too noisy. */
const val MIN_CATALOG_QUERY_LEN = 2

/** Debounce before firing a search, so typing doesn't spam the API (plan §6 "never per keystroke"). */
const val CATALOG_SEARCH_DEBOUNCE_MS = 300L

/**
 * Whether [query] is long enough to search (UX-P1-4). A single Han character is already a complete,
 * meaningful token in Chinese (e.g. `茶`, "tea") — unlike a 1-char Latin/Cyrillic query, which is just
 * noise — and the server's fuzzy path is specifically built to handle short/CJK queries via substring
 * match (`TeaSearchRepositoryImpl`). Below [MIN_CATALOG_QUERY_LEN], only a single Han character bypasses
 * the floor; a mix of a Han character with anything else already clears the floor at length 2.
 */
fun isSearchableQuery(query: String): Boolean =
    query.length >= MIN_CATALOG_QUERY_LEN || (query.length == 1 && query[0].isHanScript())

private fun Char.isHanScript(): Boolean = Character.UnicodeScript.of(this.code) == Character.UnicodeScript.HAN
