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
 * Owns the add/edit form state. Add mode wants a [boardId] (the new placement targets that
 * board); edit mode wants only a [editingTeaId] because the user-tea is shared across boards
 * (decisions.md #42) — saving in edit mode never reorders any board, the tier picker is
 * hidden, and the change ripples to every placement.
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
     * How many boards the edited user-tea currently sits on; drives the
     * "Изменения видны во всех подборках" caption (shown when > 1, decisions.md #42). Zero in
     * add mode and while the load is in flight.
     */
    private val _placementCount = MutableStateFlow(0)
    val placementCount: StateFlow<Int> = _placementCount.asStateFlow()

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

    /** Persists the tea (insert in add mode, in-place update in edit mode), then [onSaved]. */
    fun submit(onSaved: () -> Unit) {
        val form = _form.value
        if (!form.isValid) return
        val editing = _editingTeaId.value
        val board = boardId.value
        viewModelScope.launch {
            if (editing != null) {
                repository.updateTea(editing, form.toTea())
            } else if (board != null) {
                repository.addTea(board, form.toTea(), form.tierId)
            } else {
                return@launch
            }
            onSaved()
        }
    }

    /**
     * Deletes the user-tea everywhere from the edit screen — every board the tea sits on loses
     * its placement (FK cascade). Destructive; the screen gates this behind a confirm dialog.
     */
    fun deleteTea(onDeleted: () -> Unit) {
        val editing = _editingTeaId.value ?: return
        viewModelScope.launch {
            repository.deleteTea(editing)
            onDeleted()
        }
    }
}
