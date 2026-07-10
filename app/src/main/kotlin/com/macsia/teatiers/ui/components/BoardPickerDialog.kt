package com.macsia.teatiers.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.macsia.teatiers.R

/** One board offered as a placement target — just what the picker needs to render + return. */
data class BoardChoice(val id: String, val name: String)

/**
 * Reusable "pick a board to add this tea to" dialog. Shared by Browse Catalog (place a catalog tea)
 * and the tea detail screen's "add to board" (place an existing user-tea back onto a board, UX3-P1-1).
 *
 * When [onCreateBoard] is non-null the confirm button offers "create a board" (Browse Catalog's
 * escape when the user wants a brand-new board); [hint] then explains its consequence in every case,
 * not just the empty-list one (UX3-P2-8). When [onCreateBoard] is null the dialog is pick-or-cancel
 * only — the detail screen's flow, where the tea already exists and the empty case just asks the user
 * to make a board first.
 */
@Composable
fun BoardPickerDialog(
    title: String,
    boards: List<BoardChoice>,
    emptyText: String,
    onPick: (String) -> Unit,
    onDismiss: () -> Unit,
    onCreateBoard: (() -> Unit)? = null,
    hint: String? = null,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column {
                if (boards.isEmpty()) {
                    Text(emptyText)
                } else {
                    Column(
                        modifier = Modifier
                            .heightIn(max = 320.dp)
                            .verticalScroll(rememberScrollState()),
                    ) {
                        boards.forEach { board ->
                            // TextButton rows read as tappable and are full-width left-aligned.
                            TextButton(
                                onClick = { onPick(board.id) },
                                modifier = Modifier.fillMaxWidth(),
                                contentPadding = PaddingValues(vertical = 14.dp, horizontal = 8.dp),
                            ) {
                                Text(
                                    text = board.name,
                                    style = MaterialTheme.typography.bodyLarge,
                                    modifier = Modifier.fillMaxWidth(),
                                    textAlign = TextAlign.Start,
                                )
                            }
                        }
                    }
                }
                if (hint != null) {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = hint,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        },
        confirmButton = {
            if (onCreateBoard != null) {
                TextButton(onClick = onCreateBoard) {
                    Text(stringResource(R.string.boards_create_action))
                }
            } else {
                TextButton(onClick = onDismiss) {
                    Text(stringResource(R.string.action_cancel))
                }
            }
        },
        dismissButton = onCreateBoard?.let {
            {
                TextButton(onClick = onDismiss) {
                    Text(stringResource(R.string.action_cancel))
                }
            }
        },
    )
}
