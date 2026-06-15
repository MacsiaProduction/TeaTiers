package com.macsia.teatiers.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.macsia.teatiers.data.repository.TeaBoardRepository
import com.macsia.teatiers.domain.model.FlavorDimension
import com.macsia.teatiers.domain.model.Tier
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Owns the add/edit form state. With [editingTeaId] null this is the add flow (insert a new tea
 * into the bound board); when non-null the form prefills from the tea and saves with
 * [TeaBoardRepository.updateTea]. The tier picker is only meaningful in add mode (drag-to-rank
 * owns placement on the board).
 */
@HiltViewModel
class AddTeaViewModel @Inject constructor(
    private val repository: TeaBoardRepository,
) : ViewModel() {

    private val _form = MutableStateFlow(AddTeaForm())
    val form: StateFlow<AddTeaForm> = _form.asStateFlow()

    private val boardId = MutableStateFlow<String?>(null)
    private val _editingTeaId = MutableStateFlow<String?>(null)
    val editingTeaId: StateFlow<String?> = _editingTeaId.asStateFlow()

    /**
     * Binds the form to a board (and, in edit mode, an existing tea). [bind] always rewrites the
     * form so a recomposition with a new [boardId]/[teaId] cannot leak stale state from a previous
     * binding (the VM is reused across navigations under the host activity's `viewModelStore`).
     */
    fun bind(boardId: String, teaId: String? = null) {
        this.boardId.value = boardId
        _editingTeaId.value = teaId
        _form.value = if (teaId != null) {
            repository.tea(boardId, teaId)?.toForm() ?: AddTeaForm()
        } else {
            AddTeaForm()
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

    /** Persists the tea (insert in add mode, in-place update in edit mode), then [onSaved]. */
    fun submit(onSaved: () -> Unit) {
        val form = _form.value
        val board = boardId.value
        if (board == null || !form.isValid) return
        val editing = _editingTeaId.value
        viewModelScope.launch {
            if (editing == null) {
                repository.addTea(board, form.toTea(), form.tierId)
            } else {
                repository.updateTea(board, editing, form.toTea())
            }
            onSaved()
        }
    }
}
