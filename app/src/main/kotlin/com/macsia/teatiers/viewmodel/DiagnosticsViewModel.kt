package com.macsia.teatiers.viewmodel

import androidx.lifecycle.ViewModel
import com.macsia.teatiers.data.diagnostics.DiagnosticsPreferences
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.StateFlow
import org.acra.ACRA
import javax.inject.Inject

/**
 * Backs the opt-in diagnostics toggle in Settings (decision #111). Diagnostics are off by default;
 * flipping this on takes effect for the migration sentinel immediately and for ACRA crash capture on
 * the next launch (ACRA installs its hook in Application.attachBaseContext).
 */
@HiltViewModel
class DiagnosticsViewModel @Inject constructor(
    private val preferences: DiagnosticsPreferences,
) : ViewModel() {

    val enabled: StateFlow<Boolean> = preferences.enabled

    fun setEnabled(value: Boolean) {
        preferences.setEnabled(value)
        // Honor "turn off at any time": if ACRA was installed this launch, flip its crash hook live
        // so opting out stops crash sending immediately (not just on the next process start). The
        // migration sentinel already reads the flag live. Turning ON mid-session only fully arms ACRA
        // on the next launch (it installs in attachBaseContext), which is the safe direction.
        if (ACRA.isInitialised) {
            ACRA.errorReporter.setEnabled(value)
        }
    }
}
