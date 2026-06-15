package com.macsia.teatiers

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.macsia.teatiers.ui.TeaTiersApp
import com.macsia.teatiers.ui.theme.TeaTiersTheme
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            TeaTiersTheme {
                TeaTiersApp()
            }
        }
    }
}
