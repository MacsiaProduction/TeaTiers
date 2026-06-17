package com.macsia.teatiers.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.macsia.teatiers.R
import com.macsia.teatiers.data.repository.CatalogDetailResult
import com.macsia.teatiers.data.repository.CatalogRepository
import com.macsia.teatiers.data.repository.TeaBoardRepository
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

/**
 * Resolves the user-tea identified by [bind] (decisions.md #42 — board-agnostic). The flow
 * re-runs the lookup whenever the repository's boards emit so an edit on any board ripples to
 * this screen too.
 */
@HiltViewModel
class TeaDetailViewModel @Inject constructor(
    private val repository: TeaBoardRepository,
    private val catalog: CatalogRepository,
) : ViewModel() {

    private val eventHost = UiEventHost()
    val events get() = eventHost.events

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
     * Reference flavor profile of the linked catalog tea (decisions.md #23), fetched on demand when
     * the user-tea carries a [Tea.catalogTeaId]. Keyed on the catalog id (distinct) so a boards
     * re-emit never re-fetches. Network-only and fail-closed: unlinked/offline/error all yield an
     * empty list, so the screen simply omits the "catalog reference" block.
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    val referenceFlavors: StateFlow<List<FlavorScore>> = tea
        .map { it?.catalogTeaId }
        .distinctUntilChanged()
        .mapLatest { catalogId ->
            if (catalogId == null) {
                emptyList()
            } else {
                when (val result = catalog.detail(catalogId)) {
                    is CatalogDetailResult.Loaded -> result.detail.flavors
                    else -> emptyList()
                }
            }
        }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = emptyList(),
        )

    /**
     * Copies the catalog reference profile into the user's own ratings (#23 one-tap suggestion),
     * offered only when the user has no rating yet. Reuses the existing edit path; a failure
     * surfaces as a snackbar and leaves the tea untouched.
     */
    fun useReferenceAsMyRating() {
        val id = teaId.value ?: return
        val current = tea.value ?: return
        val reference = referenceFlavors.value
        if (reference.isEmpty()) return
        viewModelScope.launch {
            runCatching { repository.updateTea(id, current.copy(flavor = reference)) }
                .onFailure { eventHost.emit(UiEvent.ShowSnackbar(R.string.error_generic)) }
        }
    }

    /**
     * Deletes the user-tea everywhere from the detail screen — every board the tea sits on
     * loses its placement (FK cascade). Destructive; UI gates this behind a confirm dialog.
     */
    fun deleteTea(onDeleted: () -> Unit) {
        val id = teaId.value ?: return
        viewModelScope.launch {
            val ok = runCatching { repository.deleteTea(id); true }
                .getOrElse { eventHost.emit(UiEvent.ShowSnackbar(R.string.error_generic)); false }
            if (ok) onDeleted()
        }
    }
}
