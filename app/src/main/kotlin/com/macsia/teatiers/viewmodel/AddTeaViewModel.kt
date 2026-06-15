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

/** Owns the add-tea form state and persists a valid tea to the repository (Room) on save. */
@HiltViewModel
class AddTeaViewModel @Inject constructor(
    private val repository: TeaBoardRepository,
) : ViewModel() {

    private val _form = MutableStateFlow(AddTeaForm())
    val form: StateFlow<AddTeaForm> = _form.asStateFlow()

    private val boardId = MutableStateFlow<String?>(null)

    fun bind(id: String) {
        boardId.value = id
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

    /** Persists the tea when the form is valid and a board is bound, then invokes [onSaved]. */
    fun submit(onSaved: () -> Unit) {
        val form = _form.value
        val board = boardId.value
        if (board == null || !form.isValid) return
        viewModelScope.launch {
            repository.addTea(board, form.toTea(), form.tierId)
            onSaved()
        }
    }
}
