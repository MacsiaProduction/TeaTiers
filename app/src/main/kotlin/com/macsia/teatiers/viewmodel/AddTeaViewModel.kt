package com.macsia.teatiers.viewmodel

import androidx.lifecycle.ViewModel
import com.macsia.teatiers.data.repository.TeaBoardRepository
import com.macsia.teatiers.domain.model.FlavorDimension
import com.macsia.teatiers.domain.model.Tier
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import javax.inject.Inject

/** Owns the add-tea form state and writes a valid tea back to the repository. */
@HiltViewModel
class AddTeaViewModel @Inject constructor(
    private val repository: TeaBoardRepository,
) : ViewModel() {

    private val _form = MutableStateFlow(AddTeaForm())
    val form: StateFlow<AddTeaForm> = _form.asStateFlow()

    fun update(transform: (AddTeaForm) -> AddTeaForm) {
        _form.update(transform)
    }

    fun setFlavor(dimension: FlavorDimension, intensity: Int) {
        _form.update { it.copy(flavors = it.flavors + (dimension to intensity)) }
    }

    /** Tiers of the target board, for the optional tier picker (empty if the board is unknown). */
    fun tiersOf(boardId: String): List<Tier> = repository.board(boardId)?.tiers.orEmpty()

    /** Adds the tea to [boardId] when the form is valid; returns whether it was added. */
    fun submit(boardId: String): Boolean {
        val form = _form.value
        if (!form.isValid) return false
        repository.addTea(boardId, form.toTea(), form.tierId)
        return true
    }
}
