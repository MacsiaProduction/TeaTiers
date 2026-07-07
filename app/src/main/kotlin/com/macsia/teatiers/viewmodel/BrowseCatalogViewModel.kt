package com.macsia.teatiers.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.macsia.teatiers.data.repository.CatalogBrowseResult
import com.macsia.teatiers.data.repository.CatalogFacetsResult
import com.macsia.teatiers.data.repository.CatalogRepository
import com.macsia.teatiers.data.repository.CatalogSearchResult
import com.macsia.teatiers.data.repository.TeaBoardRepository
import com.macsia.teatiers.domain.model.CatalogTea
import com.macsia.teatiers.domain.model.TeaType
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/** A board the user can drop a browsed tea onto (the "add to a board" picker entry). */
data class BoardPick(val id: String, val name: String)

/** State of the catalog-browse list (#42 follow-up — browse the whole shared catalog). */
sealed interface BrowseCatalogUiState {
    /** First page in flight; nothing to show yet. */
    data object Loading : BrowseCatalogUiState

    /** First page failed with the network unreachable (list still empty) — offer a retry. */
    data object Offline : BrowseCatalogUiState

    /** First page failed with the per-client/edge budget spent (UX2-P1-5) — offer a retry with a
     *  distinct "try again shortly" message instead of the same one as a real server fault. */
    data object RateLimited : BrowseCatalogUiState

    /** First page failed with a server error (list still empty) — offer a retry. */
    data object Error : BrowseCatalogUiState

    /** The catalog is empty. */
    data object Empty : BrowseCatalogUiState

    /**
     * At least one page is shown. [endReached] is true once the last page was fetched (no footer).
     * [appending] = a load-more is in flight (footer spinner); [appendFailed] = the last load-more
     * failed (footer retry). [canLoadMore] gates the scroll-near-end trigger. [truncated] (UX-F-4) is
     * true for a typed-search result that hit the server's one-page cap — search has no cursor, so
     * a full result count means there is no signal of how many more matches exist beyond it.
     */
    data class Loaded(
        val teas: List<CatalogTea>,
        val endReached: Boolean,
        val appending: Boolean,
        val appendFailed: Boolean,
        val truncated: Boolean = false,
    ) : BrowseCatalogUiState {
        val canLoadMore: Boolean get() = !endReached && !appending && !appendFailed
    }
}

/**
 * Drives the catalog-browse screen: loads the catalog one cursor page at a time and exposes the
 * user's boards for the "add to a board" picker. Adding itself is delegated to the existing add
 * form (the screen navigates to it pre-picked), so this VM only paginates + lists boards.
 */
@OptIn(FlowPreview::class, ExperimentalCoroutinesApi::class)
@HiltViewModel
class BrowseCatalogViewModel @Inject constructor(
    private val catalogRepository: CatalogRepository,
    boardRepository: TeaBoardRepository,
) : ViewModel() {

    private val _state = MutableStateFlow<BrowseCatalogUiState>(BrowseCatalogUiState.Loading)
    val state: StateFlow<BrowseCatalogUiState> = _state.asStateFlow()

    /** Boards offered in the picker; seeded from the current snapshot so it never flashes empty. */
    val boards: StateFlow<List<BoardPick>> = boardRepository.boards
        .map { boards -> boards.map { BoardPick(it.id, it.name) } }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = boardRepository.boards.value.map { BoardPick(it.id, it.name) },
        )

    /** Catalog search box atop the browse list (audit #4). Blank/short => the paginated browse. */
    private val _query = MutableStateFlow("")
    val query: StateFlow<String> = _query.asStateFlow()

    /** Type filter (UX-F-1); null = every type. */
    private val _typeFilter = MutableStateFlow<TeaType?>(null)
    val typeFilter: StateFlow<TeaType?> = _typeFilter.asStateFlow()

    /** Types currently present in the catalog, driving the filter chips. Best-effort: a facets fetch
     *  failure just means no chips are offered, never a blocker to browsing. */
    private val _availableTypes = MutableStateFlow<List<TeaType>>(emptyList())
    val availableTypes: StateFlow<List<TeaType>> = _availableTypes.asStateFlow()

    private var nextCursor: Long? = null
    private var loading = false

    // Bumped whenever a load that redefines the list (first page / search / load-more) starts. A
    // load-more runs in its own coroutine the query collectLatest can't cancel, so it captures the
    // generation and drops its result if a newer load superseded it — instead of merging a stale
    // browse page (and its nextCursor) into a freshly-switched search state.
    private var loadGeneration = 0

    init {
        viewModelScope.launch {
            (catalogRepository.facets() as? CatalogFacetsResult.Loaded)?.let { _availableTypes.value = it.types }
        }
        // collectLatest cancels an in-flight load when the query OR the type filter changes, so a
        // stale result never overwrites a newer one — combining both into one pipeline (rather than
        // setTypeFilter launching its own independent coroutine) is what makes that cancellation
        // actually cover a type-filter change too: two separately-launched coroutines wouldn't cancel
        // each other, so a fast query-then-filter-tap could otherwise race and let the stale one win.
        // A blank/short query shows the cursor-paginated browse; otherwise the same fuzzy catalog
        // search the add form uses, capped at one page (no load-more).
        viewModelScope.launch {
            combine(_query.map { it.trim() }.distinctUntilChanged(), _typeFilter) { q, type -> q to type }
                .debounce { (q, _) -> if (isSearchableQuery(q)) CATALOG_SEARCH_DEBOUNCE_MS else 0L }
                .collectLatest { (q, _) ->
                    if (isSearchableQuery(q)) runSearch(q) else loadFirstPageSuspending()
                }
        }
    }

    fun setQuery(value: String) {
        _query.value = value
    }

    /** Switches the type filter (UX-F-1); the combined query+type pipeline above reruns with it applied. */
    fun setTypeFilter(type: TeaType?) {
        _typeFilter.value = type
    }

    /** (Re)loads the first browse page — on open (blank query) and as the empty-list retry. */
    fun loadFirstPage() {
        viewModelScope.launch { loadFirstPageSuspending() }
    }

    private suspend fun loadFirstPageSuspending() {
        loadGeneration++
        loading = true
        _state.value = BrowseCatalogUiState.Loading
        try {
            applyFirst(catalogRepository.browse(cursor = null, type = _typeFilter.value))
        } finally {
            loading = false
        }
    }

    private suspend fun runSearch(query: String) {
        loadGeneration++
        loading = true
        _state.value = BrowseCatalogUiState.Loading
        try {
            _state.value = when (val result = catalogRepository.search(query, type = _typeFilter.value)) {
                is CatalogSearchResult.Loaded ->
                    if (result.teas.isEmpty()) {
                        BrowseCatalogUiState.Empty
                    } else {
                        // Search returns a single page; mark endReached so the scroll-near-end
                        // trigger and the load-more footer stay off in search mode. A full page (the
                        // server's DEFAULT_LIMIT) means there may be more matches beyond this page —
                        // there is no cursor here to know for sure, so flag it as possibly truncated.
                        BrowseCatalogUiState.Loaded(
                            teas = result.teas,
                            endReached = true,
                            appending = false,
                            appendFailed = false,
                            truncated = result.teas.size >= CatalogRepository.DEFAULT_LIMIT,
                        )
                    }
                CatalogSearchResult.Offline -> BrowseCatalogUiState.Offline
                CatalogSearchResult.RateLimited -> BrowseCatalogUiState.RateLimited
                CatalogSearchResult.Error -> BrowseCatalogUiState.Error
            }
        } finally {
            loading = false
        }
    }

    /** Loads the next page when the list nears its end; a no-op unless the state [canLoadMore]. */
    fun loadMore() {
        val current = _state.value
        if (loading || current !is BrowseCatalogUiState.Loaded || !current.canLoadMore) return
        val cursor = nextCursor ?: return
        val gen = ++loadGeneration
        loading = true
        _state.value = current.copy(appending = true)
        viewModelScope.launch {
            applyMore(catalogRepository.browse(cursor = cursor, type = _typeFilter.value), gen)
            loading = false
        }
    }

    /** Retries the failed page: the active search, else the next page if a load-more failed, else the first. */
    fun retry() {
        val q = _query.value.trim()
        val current = _state.value
        when {
            isSearchableQuery(q) -> viewModelScope.launch { runSearch(q) }
            current is BrowseCatalogUiState.Loaded && current.appendFailed -> {
                _state.value = current.copy(appendFailed = false)
                loadMore()
            }
            else -> loadFirstPage()
        }
    }

    private fun applyFirst(result: CatalogBrowseResult) {
        _state.value = when (result) {
            is CatalogBrowseResult.Loaded -> {
                nextCursor = result.nextCursor
                if (result.teas.isEmpty()) {
                    BrowseCatalogUiState.Empty
                } else {
                    BrowseCatalogUiState.Loaded(
                        teas = result.teas,
                        endReached = result.nextCursor == null,
                        appending = false,
                        appendFailed = false,
                    )
                }
            }
            CatalogBrowseResult.Offline -> BrowseCatalogUiState.Offline
            CatalogBrowseResult.RateLimited -> BrowseCatalogUiState.RateLimited
            CatalogBrowseResult.Error -> BrowseCatalogUiState.Error
        }
    }

    private fun applyMore(result: CatalogBrowseResult, gen: Int) {
        // Superseded: a query change started a search/first-page (bumping the generation) while this
        // browse page was in flight — drop it rather than merge a stale page into the new state.
        if (gen != loadGeneration) return
        val current = _state.value as? BrowseCatalogUiState.Loaded ?: return
        _state.value = when (result) {
            is CatalogBrowseResult.Loaded -> {
                nextCursor = result.nextCursor
                // Defensive de-dup in case a page boundary overlaps; catalog ids are unique.
                val seen = current.teas.mapTo(HashSet()) { it.id }
                val merged = current.teas + result.teas.filterNot { it.id in seen }
                current.copy(
                    teas = merged,
                    endReached = result.nextCursor == null,
                    appending = false,
                    appendFailed = false,
                )
            }
            CatalogBrowseResult.Offline, CatalogBrowseResult.RateLimited, CatalogBrowseResult.Error ->
                current.copy(appending = false, appendFailed = true)
        }
    }
}
