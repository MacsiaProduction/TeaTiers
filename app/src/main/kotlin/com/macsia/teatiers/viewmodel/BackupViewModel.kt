package com.macsia.teatiers.viewmodel

import android.net.Uri
import androidx.annotation.StringRes
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.macsia.teatiers.R
import com.macsia.teatiers.data.backup.BackupManager
import com.macsia.teatiers.data.backup.BackupResult
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Drives export/import (decisions.md #26). The screen owns the SAF launchers and passes the picked
 * [Uri]s here; this VM runs the I/O off the main thread and reports back via one-shot [events]
 * (a snackbar message, or a request for the screen to fire the share sheet).
 */
sealed interface BackupEvent {
    data class Message(@StringRes val res: Int) : BackupEvent
    data class Share(val uri: Uri) : BackupEvent
}

@HiltViewModel
class BackupViewModel @Inject constructor(
    private val backupManager: BackupManager,
) : ViewModel() {

    private val channel = Channel<BackupEvent>(capacity = 4)
    val events: Flow<BackupEvent> = channel.receiveAsFlow()

    fun exportTo(uri: Uri) {
        viewModelScope.launch {
            channel.trySend(BackupEvent.Message(backupManager.exportTo(uri).messageRes()))
        }
    }

    fun importFrom(uri: Uri) {
        viewModelScope.launch {
            channel.trySend(BackupEvent.Message(backupManager.importFrom(uri).messageRes()))
        }
    }

    fun share() {
        viewModelScope.launch {
            val uri = backupManager.createShareUri()
            channel.trySend(
                if (uri != null) BackupEvent.Share(uri) else BackupEvent.Message(R.string.backup_failed),
            )
        }
    }

    @StringRes
    private fun BackupResult.messageRes(): Int = when (this) {
        is BackupResult.Exported -> R.string.backup_export_done
        is BackupResult.Imported -> R.string.backup_import_done
        BackupResult.InvalidFile -> R.string.backup_invalid_file
        BackupResult.Failed -> R.string.backup_failed
    }
}
