package com.macsia.teatiers.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.macsia.teatiers.data.repository.TeaBoardRepository
import com.macsia.teatiers.domain.model.Tea
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

/** Resolves the tea identified by [bind] from the repository, reacting to later edits. */
@HiltViewModel
class TeaDetailViewModel @Inject constructor(
    private val repository: TeaBoardRepository,
) : ViewModel() {

    private val key = MutableStateFlow<TeaKey?>(null)

    fun bind(boardId: String, teaId: String) {
        key.value = TeaKey(boardId, teaId)
    }

    val tea: StateFlow<Tea?> = combine(repository.boards, key) { _, k ->
        k?.let { repository.tea(it.boardId, it.teaId) }
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = null,
    )

    private data class TeaKey(val boardId: String, val teaId: String)
}
