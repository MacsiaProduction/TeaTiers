package com.macsia.teatiers.ui

import androidx.activity.compose.BackHandler
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import com.macsia.teatiers.ui.board.BoardScreen
import com.macsia.teatiers.ui.board.BoardsScreen

/**
 * Top-level destination switch. Phase 0 keeps navigation deliberately tiny (a single nullable
 * "open board" id) to avoid pulling in navigation-compose before it earns its place; a real
 * NavHost with typed routes arrives with the editing flows in M1.
 */
@Composable
fun TeaTiersApp() {
    var openBoardId: String? by rememberSaveable { mutableStateOf<String?>(null) }

    Surface(color = MaterialTheme.colorScheme.background) {
        if (openBoardId == null) {
            BoardsScreen(onOpenBoard = { openBoardId = it })
        } else {
            BoardScreen(onBack = { openBoardId = null })
        }
    }

    BackHandler(enabled = openBoardId != null) { openBoardId = null }
}
