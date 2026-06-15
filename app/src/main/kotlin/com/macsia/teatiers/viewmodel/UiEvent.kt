package com.macsia.teatiers.viewmodel

import androidx.annotation.StringRes
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow

/**
 * One-shot UI events emitted by ViewModels for the screen to react to. Today only carries
 * snackbar requests, but the sealed interface leaves room for future "navigate" or "vibrate"
 * events without reshaping the channel surface.
 *
 * The companion [UiEventHost] is a tiny VM-side helper that wraps a buffered channel + the
 * matching [Flow] exposure pattern used across every ViewModel here, so each VM does not
 * have to repeat the four lines that wire it up.
 */
sealed interface UiEvent {
    data class ShowSnackbar(@StringRes val messageRes: Int) : UiEvent
}

/**
 * Per-VM helper around a buffered channel. Emit fires-and-forgets — the screen consumes via
 * [events], so a failure with no active subscriber is dropped instead of stalling the VM.
 * Buffered (capacity 8) so a burst of repo failures does not suspend the producer.
 */
class UiEventHost {
    private val channel = Channel<UiEvent>(capacity = 8)
    val events: Flow<UiEvent> = channel.receiveAsFlow()
    fun emit(event: UiEvent) {
        channel.trySend(event)
    }
}

/**
 * Screen-side helper: collects [events] onto [host] as snackbars. Resolves the string res
 * via the current context so locale changes don't have to flow through the VM. Use inside a
 * top-level composable that owns a [androidx.compose.material3.Scaffold]'s SnackbarHost.
 */
@Composable
fun CollectUiEvents(events: Flow<UiEvent>, host: SnackbarHostState) {
    val resources = LocalContext.current.resources
    LaunchedEffect(events, host) {
        events.collect { event ->
            when (event) {
                is UiEvent.ShowSnackbar -> host.showSnackbar(resources.getString(event.messageRes))
            }
        }
    }
}
