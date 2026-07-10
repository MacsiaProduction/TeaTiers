package com.macsia.teatiers

import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.runtime.getValue
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.macsia.teatiers.data.repository.TeaEnrichmentManager
import com.macsia.teatiers.ui.TeaTiersApp
import com.macsia.teatiers.ui.theme.TeaTiersTheme
import com.macsia.teatiers.viewmodel.SettingsViewModel
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

// AppCompatActivity (not plain ComponentActivity) so AppCompatDelegate.setApplicationLocales drives
// the per-app language across all supported API levels (#28). Compose still hosts every screen.
@AndroidEntryPoint
class MainActivity : AppCompatActivity() {

    @Inject lateinit var enrichmentManager: TeaEnrichmentManager

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Resume any tea stranded PENDING/QUEUED by a prior run once per launch (UX3-P2-16). The
        // boards screen already resumes on open, but a process restored straight onto a non-board
        // screen (detail/add) would otherwise never re-dispatch until the user navigated to a board.
        // The manager's own cooldown keeps this from double-firing with the boards-screen resume.
        enrichmentManager.resumePending()
        enableEdgeToEdge()
        setContent {
            val settingsViewModel: SettingsViewModel = hiltViewModel()
            val settings by settingsViewModel.settings.collectAsStateWithLifecycle()
            TeaTiersTheme(themeMode = settings.themeMode, dynamicColor = settings.dynamicColor) {
                TeaTiersApp()
            }
        }
    }
}
