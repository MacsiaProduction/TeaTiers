package com.macsia.teatiers.data.update

import android.content.Context
import android.util.Log
import androidx.core.content.FileProvider
import dagger.hilt.android.qualifiers.ApplicationContext
import ru.solrudev.ackpine.installer.PackageInstaller
import ru.solrudev.ackpine.installer.createSession
import ru.solrudev.ackpine.session.Session
import ru.solrudev.ackpine.session.await
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "AppInstaller"

/**
 * Installs an already-verified update APK via Ackpine's GMS-free PackageInstaller (decision #119).
 * The user still sees the system install confirmation and must have granted "install unknown apps"
 * for this app — we never install silently (unprivileged). Verification ([ApkVerifier]) happens
 * BEFORE this; the installer is handed only a sha256-/signer-checked file.
 */
@Singleton
class AppInstaller @Inject constructor(
    @ApplicationContext private val context: Context,
) {

    sealed interface Outcome {
        data object Success : Outcome

        /** The install did not complete (declined, signature mismatch, OEM block, …). */
        data class Failed(val reason: String) : Outcome
    }

    /**
     * Drives an Ackpine install session for [apk] to its terminal state via the ktx `await()`. A
     * `CancellationException` (the caller's scope was cancelled — e.g. the user navigated away)
     * propagates per structured concurrency; Ackpine then cancels the session.
     */
    suspend fun install(apk: File): Outcome {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", apk)
        val session = PackageInstaller.getInstance(context).createSession(uri) {
            // Defaults: SESSION_BASED installer, user confirmation shown (we're unprivileged).
            name = "TeaTiers"
        }
        return when (val state = session.await()) {
            Session.State.Succeeded -> Outcome.Success
            is Session.State.Failed -> {
                // UX2-P2-23: previously dropped — the raw reason never reached logcat, so a
                // support report of "update failed on my phone" had no diagnostic trail.
                Log.w(TAG, "install failed: ${state.failure}")
                Outcome.Failed(state.failure.toString())
            }
            else -> {
                Log.w(TAG, "install: unexpected installer state $state")
                Outcome.Failed("unexpected installer state: $state")
            }
        }
    }
}
