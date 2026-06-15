package com.macsia.teatiers.ui

import androidx.activity.compose.BackHandler
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.saveable.rememberSaveable
import com.macsia.teatiers.ui.board.AddTeaScreen
import com.macsia.teatiers.ui.board.BoardScreen
import com.macsia.teatiers.ui.board.BoardsScreen
import com.macsia.teatiers.ui.board.TeaDetailScreen
import com.macsia.teatiers.ui.board.TierEditorScreen
import com.macsia.teatiers.ui.nav.BackStackSaver
import com.macsia.teatiers.ui.nav.Destination

/**
 * Top-level destination host. Holds a small saveable back stack and renders the top entry;
 * system back pops it. A NavHost (navigation-compose) can replace this if the graph grows.
 */
@Composable
fun TeaTiersApp() {
    val backStack = rememberSaveable(saver = BackStackSaver) {
        mutableStateListOf<Destination>(Destination.Boards)
    }

    fun navigate(destination: Destination) = backStack.add(destination)
    fun pop() {
        if (backStack.size > 1) backStack.removeAt(backStack.lastIndex)
    }

    Surface(color = MaterialTheme.colorScheme.background) {
        when (val current = backStack.last()) {
            Destination.Boards ->
                BoardsScreen(onOpenBoard = { navigate(Destination.Board(it)) })

            is Destination.Board ->
                BoardScreen(
                    boardId = current.boardId,
                    onBack = ::pop,
                    onOpenTea = { teaId -> navigate(Destination.TeaDetail(teaId)) },
                    onAddTea = { navigate(Destination.AddTea(current.boardId)) },
                    onEditTiers = { navigate(Destination.TierEditor(current.boardId)) },
                )

            is Destination.TeaDetail ->
                TeaDetailScreen(
                    teaId = current.teaId,
                    onBack = ::pop,
                    onEdit = { teaId -> navigate(Destination.EditTea(teaId)) },
                )

            is Destination.AddTea ->
                AddTeaScreen(
                    boardId = current.boardId,
                    onBack = ::pop,
                    onSaved = ::pop,
                )

            is Destination.EditTea ->
                AddTeaScreen(
                    boardId = null,
                    onBack = ::pop,
                    onSaved = ::pop,
                    teaId = current.teaId,
                )

            is Destination.TierEditor ->
                TierEditorScreen(
                    boardId = current.boardId,
                    onBack = ::pop,
                )
        }
    }

    BackHandler(enabled = backStack.size > 1) { pop() }
}
