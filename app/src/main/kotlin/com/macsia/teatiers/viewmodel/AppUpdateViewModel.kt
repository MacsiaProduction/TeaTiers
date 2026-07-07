package com.macsia.teatiers.viewmodel

import android.os.Build
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.macsia.teatiers.BuildConfig
import com.macsia.teatiers.data.remote.dto.AppManifestDto
import com.macsia.teatiers.data.update.AppInstaller
import com.macsia.teatiers.data.update.AppUpdateChecker
import com.macsia.teatiers.data.update.ApkDownloader
import com.macsia.teatiers.data.update.ApkVerification
import com.macsia.teatiers.data.update.ApkVerifier
import com.macsia.teatiers.data.update.UpdateAvailability
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

/** UI state of the in-app updater (decision #119). */
sealed interface UpdateUiState {
    /** No check run yet, or the device is up to date. */
    data object Idle : UpdateUiState
    data object Checking : UpdateUiState

    /** A newer release is available; [forced] hides the "Later" affordance. */
    data class Available(val manifest: AppManifestDto, val forced: Boolean) : UpdateUiState

    /** Downloading then verifying + installing — the user sees a single "working" state. */
    data object Working : UpdateUiState

    /** The check/download/verify/install failed. [manifest] (if any) lets the UI offer a manual link. */
    data class Failed(val reason: String, val manifest: AppManifestDto?) : UpdateUiState
}

/**
 * Orchestrates the in-app update flow (decision #119): check → download → **verify (sha256 + signer +
 * downgrade)** → install via Ackpine. Every step is best-effort and the collaborators are injected, so
 * the state machine is unit-testable with fakes. Verification is mandatory — a downloaded APK that
 * fails [ApkVerifier] is deleted and never installed.
 */
@HiltViewModel
class AppUpdateViewModel @Inject constructor(
    private val checker: AppUpdateChecker,
    private val downloader: ApkDownloader,
    private val verifier: ApkVerifier,
    private val installer: AppInstaller,
) : ViewModel() {

    private val _state = MutableStateFlow<UpdateUiState>(UpdateUiState.Idle)
    val state: StateFlow<UpdateUiState> = _state.asStateFlow()

    /** Polls the manifest endpoint and surfaces an Available/Idle state. Safe to call repeatedly. */
    fun check() {
        // UX2-P2-20: a rapid re-tap used to launch a second overlapping check with no guard — whichever
        // network call resolved last won, regardless of tap order. Set synchronously before the launch
        // so a second tap on the same frame sees it (mirrors AddTeaViewModel's _isSaving pattern).
        if (_state.value == UpdateUiState.Checking) return
        viewModelScope.launch {
            _state.value = UpdateUiState.Checking
            _state.value = when (val result = checker.check(BuildConfig.VERSION_CODE, Build.VERSION.SDK_INT)) {
                UpdateAvailability.None -> UpdateUiState.Idle
                is UpdateAvailability.Optional -> UpdateUiState.Available(result.manifest, forced = false)
                is UpdateAvailability.Forced -> UpdateUiState.Available(result.manifest, forced = true)
                UpdateAvailability.CheckFailed -> UpdateUiState.Failed("check", manifest = null)
            }
        }
    }

    /**
     * Downloads + verifies + installs the currently-available release. RETAINED but NOT wired to the UI
     * (REL-P0-2, decision 2026-06-23): the SettingsScreen update prompt routes users to GitHub releases /
     * Obtainium instead, because the manifest this trusts is server-selected + un-pinned. Re-surface this
     * once the manifest is an offline Ed25519-signed blob.
     */
    fun installUpdate() {
        val available = _state.value as? UpdateUiState.Available ?: return
        val manifest = available.manifest
        viewModelScope.launch {
            _state.value = UpdateUiState.Working
            val apk = downloader.download(manifest)
            if (apk == null) {
                _state.value = UpdateUiState.Failed("download", manifest)
                return@launch
            }
            // Delete the downloaded blob on EVERY exit (AND-P2-3): verify-reject, install success/fail, an
            // install that throws, OR coroutine cancellation mid-install. Without the finally the verified APK
            // leaked into cache on the throw/cancel paths. On success the installer has already copied it, so
            // deleting our cache copy is safe.
            try {
                when (val verdict = verifier.verify(apk, manifest, BuildConfig.VERSION_CODE.toLong())) {
                    ApkVerification.Ok -> Unit
                    is ApkVerification.Rejected -> {
                        _state.value = UpdateUiState.Failed("verify:${verdict.reason}", manifest)
                        return@launch
                    }
                }
                when (val outcome = installer.install(apk)) {
                    // On success Android installs the new APK; the old process keeps running until
                    // restarted. Drop back to Idle so the prompt dismisses cleanly either way.
                    AppInstaller.Outcome.Success -> _state.value = UpdateUiState.Idle
                    is AppInstaller.Outcome.Failed -> _state.value = UpdateUiState.Failed("install:${outcome.reason}", manifest)
                }
            } finally {
                apk.delete()
            }
        }
    }

    /** Dismiss an OPTIONAL update (a forced one keeps the prompt up). */
    fun dismiss() {
        val current = _state.value
        if (current is UpdateUiState.Available && current.forced) return
        _state.value = UpdateUiState.Idle
    }
}
