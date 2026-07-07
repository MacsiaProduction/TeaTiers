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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
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

    /** True while an export/import/share runs; the screen shows a blocking progress dialog (audit #5).
     *  These ops can take seconds with many photos, and import is a destructive replace-all. */
    private val _busy = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = _busy.asStateFlow()

    /** True once a restore has left an auto safety-backup on hand; gates the "undo last restore" entry. */
    private val _safetyBackupAvailable = MutableStateFlow(false)
    val safetyBackupAvailable: StateFlow<Boolean> = _safetyBackupAvailable.asStateFlow()

    init {
        // A safety copy may survive a process restart, so reflect any existing one on open.
        viewModelScope.launch { _safetyBackupAvailable.value = backupManager.hasSafetyBackup() }
    }

    fun exportTo(uri: Uri) = guardedBusy {
        channel.trySend(BackupEvent.Message(backupManager.exportTo(uri).messageRes(BackupOp.EXPORT)))
    }

    fun importFrom(uri: Uri) = guardedBusy {
        val result = backupManager.importFrom(uri)
        channel.trySend(BackupEvent.Message(result.messageRes(BackupOp.IMPORT)))
        // A completed restore leaves a pre-import safety copy; surface the undo entry.
        _safetyBackupAvailable.value = backupManager.hasSafetyBackup()
    }

    /** Undoes the last restore by reinstating the pre-import safety snapshot (auto safety-backup). */
    fun restoreSafetyBackup() = guardedBusy {
        channel.trySend(BackupEvent.Message(backupManager.restoreSafetyBackup().messageRes(BackupOp.IMPORT)))
        // The snapshot is consumed by a successful undo; refresh so the entry hides and a
        // second tap can't re-restore a now-stale snapshot over newer data (finding #1).
        _safetyBackupAvailable.value = backupManager.hasSafetyBackup()
    }

    fun share() = guardedBusy {
        val uri = backupManager.createShareUri()
        channel.trySend(
            if (uri != null) BackupEvent.Share(uri) else BackupEvent.Message(R.string.backup_share_failed),
        )
    }

    /**
     * Re-entrancy guard (UX2-P1-3, mirrors AddTeaViewModel's `_isSaving` pattern): every op here is
     * either destructive (import/restore replace the whole DB) or slow enough that a recomposition
     * race or fast double-tap could interleave two calls before the blocking dialog even renders.
     * Set synchronously (before the launch) so a second call on the same frame sees it.
     */
    private fun guardedBusy(block: suspend () -> Unit) {
        if (_busy.value) return
        _busy.value = true
        viewModelScope.launch {
            try {
                block()
            } finally {
                _busy.value = false
            }
        }
    }

    /** Which user-initiated operation produced a [BackupResult], so a bare [BackupResult.Failed] can
     *  say WHAT failed (UX-P2-6) instead of one undifferentiated message for export/import/share. */
    private enum class BackupOp { EXPORT, IMPORT }

    @StringRes
    private fun BackupResult.messageRes(op: BackupOp): Int = when (this) {
        is BackupResult.Exported -> R.string.backup_export_done
        is BackupResult.Imported ->
            if (undoUnavailable) R.string.backup_import_done_no_undo else R.string.backup_import_done
        BackupResult.InvalidFile -> R.string.backup_invalid_file
        BackupResult.IncompatibleVersion -> R.string.backup_incompatible_version
        BackupResult.IncompleteArchive -> R.string.backup_incomplete_archive
        BackupResult.TooLarge -> R.string.backup_too_large
        BackupResult.Failed -> when (op) {
            BackupOp.EXPORT -> R.string.backup_export_failed
            BackupOp.IMPORT -> R.string.backup_import_failed
        }
    }
}
