package com.macsia.teatiers.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.macsia.teatiers.R
import com.macsia.teatiers.data.repository.CatalogDetailResult
import com.macsia.teatiers.data.repository.CatalogRepository
import com.macsia.teatiers.data.repository.TeaBoardRepository
import com.macsia.teatiers.data.repository.TeaEnrichmentManager
import com.macsia.teatiers.domain.model.Board
import com.macsia.teatiers.domain.model.CatalogImage
import com.macsia.teatiers.domain.model.CatalogTeaDetail
import com.macsia.teatiers.domain.model.FlavorScore
import com.macsia.teatiers.domain.model.Tea
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/** Load state for the detail screen, so it can show a spinner / "not found" instead of a blank screen. */
sealed interface TeaDetailUiState {
    data object Loading : TeaDetailUiState
    data object NotFound : TeaDetailUiState
    data class Loaded(val tea: Tea) : TeaDetailUiState
}

/**
 * Resolves the user-tea identified by [bind] (decisions.md #42 — board-agnostic). The flow
 * re-runs the lookup whenever the repository's boards emit so an edit on any board ripples to
 * this screen too.
 */
@HiltViewModel
class TeaDetailViewModel @Inject constructor(
    private val repository: TeaBoardRepository,
    private val catalog: CatalogRepository,
    private val enrichmentManager: TeaEnrichmentManager,
) : ViewModel() {

    private val eventHost = UiEventHost()
    val events get() = eventHost.events

    private val teaId = MutableStateFlow<String?>(null)

    fun bind(teaId: String) {
        this.teaId.value = teaId
    }

    /** Boards offered as "add to board" targets (UX3-P1-1). */
    val boards: StateFlow<List<Board>> = repository.boards

    /**
     * Places the shown tea onto [boardId] — the explicit "add to board" action (UX3-P1-1). This is the
     * discoverable path back for an orphaned tea (removed from every board, or stranded by a deleted
     * board), which the detail/My-Teas screens otherwise couldn't re-place except via the silent
     * name/catalog re-resolve. Idempotent: re-adding a tea already on the board changes nothing and
     * still confirms with the same snackbar.
     */
    fun addToBoard(boardId: String) {
        val id = teaId.value ?: return
        viewModelScope.launch {
            runCatching { repository.placeExistingTeaOnBoard(boardId, id) }
                .onSuccess { placed ->
                    eventHost.emit(ShowSnackbar(if (placed) R.string.detail_added_to_board else R.string.error_generic))
                }
                .onFailure { eventHost.emit(ShowSnackbar(R.string.error_generic)) }
        }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    val uiState: StateFlow<TeaDetailUiState> = teaId
        .flatMapLatest { id ->
            if (id == null) {
                flowOf(TeaDetailUiState.Loading)
            } else {
                // mapLatest so a re-emit of `boards` cancels the in-flight lookup; fine because
                // `repository.tea` is mostly an in-memory hit anyway. A missing id resolves to
                // NotFound (e.g. the tea was deleted) so the screen never sits blank forever.
                repository.boards.mapLatest {
                    repository.tea(id)?.let(TeaDetailUiState::Loaded) ?: TeaDetailUiState.NotFound
                }
            }
        }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = TeaDetailUiState.Loading,
        )

    val tea: StateFlow<Tea?> = uiState
        .map { (it as? TeaDetailUiState.Loaded)?.tea }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = null,
        )

    /**
     * Reference detail of the linked catalog tea (decisions.md #23), fetched on demand when the
     * user-tea carries a [Tea.catalogTeaId]. Keyed on the catalog id (distinct) so a boards re-emit
     * never re-fetches. Network-only and fail-closed: unlinked/offline/error yield null, so the
     * screen simply omits the catalog reference block. One fetch backs both flavors and images.
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    private val referenceDetail: StateFlow<CatalogTeaDetail?> = tea
        .map { it?.catalogTeaId }
        .distinctUntilChanged()
        .mapLatest { catalogId ->
            if (catalogId == null) null
            else (catalog.detail(catalogId) as? CatalogDetailResult.Loaded)?.detail
        }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = null,
        )

    /** Reference flavor profile from [referenceDetail]; empty when unlinked/offline/error. */
    val referenceFlavors: StateFlow<List<FlavorScore>> = referenceDetail
        .map { it?.flavors.orEmpty() }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /**
     * Curated CC catalog images, shown on the detail screen only when the user has taken no photos
     * of their own (audit #9). Not persisted onto the user-tea — display-only, with attribution.
     */
    val referenceImages: StateFlow<List<CatalogImage>> = referenceDetail
        .map { it?.images.orEmpty() }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /**
     * Retries background enrichment of the shown tea from the detail screen (UX3-P1-4) — the board
     * card's retry lived only in its overflow, so a tea opened from My Teas or reached directly had no
     * way to re-drive a FAILED/QUEUED/RATE_LIMITED resolve. Mirrors [BoardViewModel.retryEnrichment]:
     * a redundant tap while one is already in flight gives feedback instead of silently no-oping.
     */
    fun retryEnrichment() {
        val id = teaId.value ?: return
        if (enrichmentManager.isInFlight(id)) {
            eventHost.emit(ShowSnackbar(R.string.enrichment_already_retrying))
            return
        }
        enrichmentManager.retry(id)
    }

    /**
     * Copies the catalog reference profile into the user's own ratings (#23 one-tap suggestion),
     * offered only when the user has no rating yet. A failure surfaces as a snackbar and leaves
     * the tea untouched.
     */
    fun useReferenceAsMyRating() {
        val id = teaId.value ?: return
        val reference = referenceFlavors.value
        if (reference.isEmpty()) return
        viewModelScope.launch {
            // UX2-P0-2: writes only the flavor rows, not the whole Tea — round-tripping the full row
            // through a stale `tea.value` snapshot could silently clobber a concurrent edit elsewhere.
            runCatching { repository.updateFlavor(id, reference) }
                .onSuccess { eventHost.emit(ShowSnackbar(R.string.detail_reference_applied)) }
                .onFailure { eventHost.emit(ShowSnackbar(R.string.error_generic)) }
        }
    }

    /**
     * Deletes the user-tea everywhere from the detail screen — every board the tea sits on
     * loses its placement (FK cascade). Destructive; UI gates this behind a confirm dialog.
     *
     * No Undo snackbar here: this path pops back to the previous screen on success, so an in-place
     * snackbar would vanish with it. The confirm dialog is the safety net for this entry point; the
     * in-place board delete (BoardViewModel) offers Undo instead. The snapshot return is ignored.
     */
    fun deleteTea(onDeleted: () -> Unit) {
        val id = teaId.value ?: return
        viewModelScope.launch {
            val ok = runCatching { repository.deleteTea(id); true }
                .getOrElse { eventHost.emit(ShowSnackbar(R.string.error_generic)); false }
            if (ok) onDeleted()
        }
    }
}
