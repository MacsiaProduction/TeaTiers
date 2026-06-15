package com.macsia.teatiers.viewmodel

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.macsia.teatiers.R
import com.macsia.teatiers.data.repository.TeaBoardRepository
import com.macsia.teatiers.domain.model.FlavorDimension
import com.macsia.teatiers.domain.model.PhotoSource
import com.macsia.teatiers.domain.model.TeaPhoto
import com.macsia.teatiers.domain.model.Tier
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.UUID
import javax.inject.Inject

/**
 * Owns the add/edit form state. Add mode wants a [boardId] (the new placement targets that
 * board); edit mode wants only a [editingTeaId] because the user-tea is shared across boards
 * (decisions.md #42) — saving in edit mode never reorders any board, the tier picker is
 * hidden, and the change ripples to every placement.
 */
@HiltViewModel
class AddTeaViewModel @Inject constructor(
    private val repository: TeaBoardRepository,
) : ViewModel() {

    private val eventHost = UiEventHost()
    /** One-shot UI events (snackbars). Screen-side `LaunchedEffect` collects this. */
    val events get() = eventHost.events

    /** Toggled by the screen when the user taps Save with an invalid form. The screen reads
     *  this once via [consumeNameRequiredFocus] and routes focus to the nameRu field. */
    private var pendingNameFocus = false
    fun consumeNameRequiredFocus(): Boolean {
        val taken = pendingNameFocus
        pendingNameFocus = false
        return taken
    }

    private val _form = MutableStateFlow(AddTeaForm())
    val form: StateFlow<AddTeaForm> = _form.asStateFlow()

    private val boardId = MutableStateFlow<String?>(null)
    private val _editingTeaId = MutableStateFlow<String?>(null)
    val editingTeaId: StateFlow<String?> = _editingTeaId.asStateFlow()

    /**
     * How many boards the edited user-tea currently sits on; drives the
     * "Изменения видны во всех подборках" caption (shown when > 1, decisions.md #42). Zero in
     * add mode and while the load is in flight.
     */
    private val _placementCount = MutableStateFlow(0)
    val placementCount: StateFlow<Int> = _placementCount.asStateFlow()

    /**
     * Add-mode draft list of pending photos: the picker ran but the tea row does not exist yet,
     * so we cannot copy the bytes through `PhotoStore.addPhoto(teaId, …)` — we stage the URIs
     * here and `submit()` materializes them after the tea is saved (one-by-one; a copy failure
     * is logged + skipped, the tea is still saved). Empty in edit mode.
     */
    private val _draftPhotos = MutableStateFlow<List<DraftPhoto>>(emptyList())
    val draftPhotos: StateFlow<List<DraftPhoto>> = _draftPhotos.asStateFlow()

    /**
     * Photos visible on the strip. In edit mode this projects from the repository's user-tea
     * (so a successful `addPhoto`/`removePhoto`/`reorderPhotos` immediately shows up); in add
     * mode it projects from [_draftPhotos] using `DraftPhoto.id` as the strip id.
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    val photos: StateFlow<List<TeaPhoto>> = _editingTeaId
        .flatMapLatest { id ->
            if (id == null) {
                _draftPhotos.mapLatest { drafts -> drafts.mapIndexed { i, d -> d.asTeaPhoto(i) } }
            } else {
                repository.boards.mapLatest {
                    repository.tea(id)?.photos.orEmpty()
                }
            }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /**
     * Binds the form. Always resets first so a recomposition with new ids cannot leak stale
     * state from a previous binding (the VM is reused across navigations under the host
     * activity's `viewModelStore`). The tea load is async — the form stays empty until the
     * load resolves and we double-check the binding hasn't changed in between.
     */
    fun bind(boardId: String? = null, teaId: String? = null) {
        this.boardId.value = boardId
        _editingTeaId.value = teaId
        _form.value = AddTeaForm()
        _placementCount.value = 0
        _draftPhotos.value = emptyList()
        if (teaId != null) {
            viewModelScope.launch {
                val tea = repository.tea(teaId)
                val count = repository.placementCountForTea(teaId)
                if (_editingTeaId.value == teaId) {
                    _form.value = tea?.toForm() ?: AddTeaForm()
                    _placementCount.value = count
                }
            }
        }
    }

    /** Tiers of the bound board for the optional tier picker; empty until the board loads. */
    val tiers: StateFlow<List<Tier>> = combine(repository.boards, boardId) { boards, id ->
        id?.let { boards.firstOrNull { board -> board.id == it } }
            ?.tiers
            ?.sortedBy { it.position }
            .orEmpty()
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun update(transform: (AddTeaForm) -> AddTeaForm) {
        _form.update(transform)
    }

    fun setFlavor(dimension: FlavorDimension, intensity: Int) {
        _form.update { it.copy(flavors = it.flavors + (dimension to intensity)) }
    }

    fun addPurchase() {
        _form.update { it.copy(purchases = it.purchases + PurchaseDraft()) }
    }

    fun updatePurchase(index: Int, transform: (PurchaseDraft) -> PurchaseDraft) {
        _form.update { form ->
            if (index !in form.purchases.indices) return@update form
            form.copy(purchases = form.purchases.toMutableList().also { it[index] = transform(it[index]) })
        }
    }

    fun removePurchase(index: Int) {
        _form.update { form ->
            if (index !in form.purchases.indices) return@update form
            form.copy(purchases = form.purchases.toMutableList().also { it.removeAt(index) })
        }
    }

    /**
     * Persists the tea (insert in add mode, in-place update in edit mode), then [onSaved].
     * Invalid forms surface a snackbar + arm focus-on-error so the user knows why nothing
     * happened (#43 polish lane 2). Repository failures surface a generic snackbar and leave
     * the form intact so the user can retry without re-typing.
     */
    fun submit(onSaved: () -> Unit) {
        val form = _form.value
        if (!form.isValid) {
            pendingNameFocus = true
            eventHost.emit(UiEvent.ShowSnackbar(R.string.add_tea_error_name_required))
            return
        }
        val editing = _editingTeaId.value
        val board = boardId.value
        viewModelScope.launch {
            val ok = runCatching {
                if (editing != null) {
                    repository.updateTea(editing, form.toTea())
                } else if (board != null) {
                    val newTeaId = repository.addTea(board, form.toTea(), form.tierId) ?: return@runCatching false
                    // A failed copy on any single picked URI surfaces as a snackbar but does
                    // not abort the save: the tea row is already in DB.
                    _draftPhotos.value.forEach { draft ->
                        val path = repository.addPhoto(newTeaId, draft.uri)
                        if (path == null) eventHost.emit(UiEvent.ShowSnackbar(R.string.error_photo_copy_failed))
                    }
                    _draftPhotos.value = emptyList()
                } else {
                    return@runCatching false
                }
                true
            }.getOrElse {
                eventHost.emit(UiEvent.ShowSnackbar(R.string.error_generic))
                false
            }
            if (ok) onSaved()
        }
    }

    /**
     * Stages a picked photo. In edit mode the bytes are copied + a row inserted right away so
     * the strip shows it immediately; in add mode it joins the draft list and waits for save.
     * A copy failure (e.g. revoked URI permission) surfaces as a snackbar.
     */
    fun onAddPhoto(uri: Uri) {
        val editing = _editingTeaId.value
        if (editing != null) {
            viewModelScope.launch {
                val path = runCatching { repository.addPhoto(editing, uri) }.getOrNull()
                if (path == null) eventHost.emit(UiEvent.ShowSnackbar(R.string.error_photo_copy_failed))
            }
        } else {
            _draftPhotos.update { it + DraftPhoto(id = "draft-${UUID.randomUUID()}", uri = uri) }
        }
    }

    /** Removes one strip photo. In add mode this just drops the draft entry. */
    fun onRemovePhoto(photoId: String) {
        val editing = _editingTeaId.value
        if (editing != null) {
            viewModelScope.launch {
                runCatching { repository.removePhoto(editing, photoId) }
                    .onFailure { eventHost.emit(UiEvent.ShowSnackbar(R.string.error_generic)) }
            }
        } else {
            _draftPhotos.update { drafts -> drafts.filterNot { it.id == photoId } }
        }
    }

    /** Reorders the photo strip to match [orderedPhotoIds]. */
    fun onReorderPhotos(orderedPhotoIds: List<String>) {
        val editing = _editingTeaId.value
        if (editing != null) {
            viewModelScope.launch {
                runCatching { repository.reorderPhotos(editing, orderedPhotoIds) }
                    .onFailure { eventHost.emit(UiEvent.ShowSnackbar(R.string.error_generic)) }
            }
        } else {
            _draftPhotos.update { drafts ->
                val byId = drafts.associateBy(DraftPhoto::id)
                val ordered = orderedPhotoIds.mapNotNull(byId::get)
                ordered + drafts.filterNot { it.id in orderedPhotoIds.toSet() }
            }
        }
    }

    /**
     * Deletes the user-tea everywhere from the edit screen — every board the tea sits on loses
     * its placement (FK cascade). Destructive; the screen gates this behind a confirm dialog.
     */
    fun deleteTea(onDeleted: () -> Unit) {
        val editing = _editingTeaId.value ?: return
        viewModelScope.launch {
            val ok = runCatching { repository.deleteTea(editing); true }
                .getOrElse { eventHost.emit(UiEvent.ShowSnackbar(R.string.error_generic)); false }
            if (ok) onDeleted()
        }
    }
}

/**
 * One pending add-mode photo. The id is a stable UI handle for drag-reorder; the URI is what the
 * picker handed us and what `PhotoStore` will copy in once the tea row exists.
 */
data class DraftPhoto(val id: String, val uri: Uri) {
    fun asTeaPhoto(index: Int): TeaPhoto = TeaPhoto(
        id = id,
        uri = uri.toString(),
        position = index,
        source = PhotoSource.USER,
    )
}
