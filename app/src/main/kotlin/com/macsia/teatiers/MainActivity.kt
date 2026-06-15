package com.macsia.teatiers

import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.runtime.getValue
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.macsia.teatiers.ui.TeaTiersApp
import com.macsia.teatiers.ui.theme.TeaTiersTheme
import com.macsia.teatiers.viewmodel.SettingsViewModel
import dagger.hilt.android.AndroidEntryPoint

// AppCompatActivity (not plain ComponentActivity) so AppCompatDelegate.setApplicationLocales drives
// the per-app language across all supported API levels (#28). Compose still hosts every screen.
@AndroidEntryPoint
class MainActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
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
