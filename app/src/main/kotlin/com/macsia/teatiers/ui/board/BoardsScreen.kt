package com.macsia.teatiers.ui.board

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.selectable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.macsia.teatiers.R
import com.macsia.teatiers.domain.model.TierTemplate
import com.macsia.teatiers.ui.components.LiquorSwatch
import com.macsia.teatiers.viewmodel.BoardSummary
import com.macsia.teatiers.viewmodel.BoardsViewModel
import com.macsia.teatiers.viewmodel.CollectUiEvents

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BoardsScreen(
    onOpenBoard: (String) -> Unit,
    onOpenMyTeas: () -> Unit,
    onOpenBrowseCatalog: () -> Unit,
    onOpenSettings: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: BoardsViewModel = hiltViewModel(),
) {
    val boards by viewModel.boards.collectAsStateWithLifecycle()
    var showCreateDialog by rememberSaveable { mutableStateOf(false) }
    var boardToDelete by remember { mutableStateOf<BoardSummary?>(null) }
    val snackbarHostState = remember { SnackbarHostState() }
    CollectUiEvents(viewModel.events, snackbarHostState)

    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.boards_title)) },
                actions = {
                    IconButton(onClick = onOpenMyTeas) {
                        Icon(
                            imageVector = Icons.Filled.Search,
                            contentDescription = stringResource(R.string.a11y_my_teas_open),
                        )
                    }
                    IconButton(onClick = onOpenBrowseCatalog) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.List,
                            contentDescription = stringResource(R.string.a11y_browse_catalog_open),
                        )
                    }
                    IconButton(onClick = onOpenSettings) {
                        Icon(
                            imageVector = Icons.Filled.Settings,
                            contentDescription = stringResource(R.string.a11y_settings_open),
                        )
                    }
                },
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = { showCreateDialog = true }) {
                Icon(
                    imageVector = Icons.Filled.Add,
                    contentDescription = stringResource(R.string.a11y_boards_create),
                )
            }
        },
        snackbarHost = { SnackbarHost(hostState = snackbarHostState) },
    ) { innerPadding ->
        if (boards.isEmpty()) {
            EmptyBoards(
                onCreate = { showCreateDialog = true },
                modifier = Modifier.fillMaxSize().padding(innerPadding),
            )
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize().padding(innerPadding),
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 96.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                items(boards, key = { it.id }) { summary ->
                    BoardSummaryCard(
                        summary = summary,
                        onClick = { onOpenBoard(summary.id) },
                        onDelete = { boardToDelete = summary },
                    )
                }
            }
        }
    }

    if (showCreateDialog) {
        CreateBoardDialog(
            onCreate = { label, template ->
                viewModel.createBoard(label, template)
                showCreateDialog = false
            },
            onDismiss = { showCreateDialog = false },
        )
    }

    boardToDelete?.let { board ->
        AlertDialog(
            onDismissRequest = { boardToDelete = null },
            title = { Text(stringResource(R.string.board_delete_confirm_title)) },
            text = { Text(stringResource(R.string.board_delete_confirm_message, board.name)) },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.deleteBoard(board.id)
                    boardToDelete = null
                }) { Text(stringResource(R.string.board_delete_confirm_action)) }
            },
            dismissButton = {
                TextButton(onClick = { boardToDelete = null }) {
                    Text(stringResource(R.string.action_cancel))
                }
            },
        )
    }
}

@Composable
private fun BoardSummaryCard(summary: BoardSummary, onClick: () -> Unit, onDelete: () -> Unit) {
    var menuOpen by remember { mutableStateOf(false) }
    val countText = pluralStringResource(R.plurals.tea_count, summary.teaCount, summary.teaCount)
    // The tappable area (title + count + swatches) is one merged TalkBack node that opens the board;
    // the trailing overflow is a separate "more options" button (delete lives there).
    val a11yLabel = stringResource(R.string.a11y_board_summary, summary.name, summary.teaCount, countText)
    Surface(
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 1.dp,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(verticalAlignment = Alignment.Top) {
            Column(
                modifier = Modifier
                    .weight(1f)
                    .clickable(onClick = onClick)
                    .semantics(mergeDescendants = true) { contentDescription = a11yLabel }
                    .padding(20.dp),
            ) {
                Text(text = summary.name, style = MaterialTheme.typography.titleLarge)
                Spacer(Modifier.height(4.dp))
                Text(
                    text = countText,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (summary.signatureTypes.isNotEmpty()) {
                    Spacer(Modifier.height(14.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        summary.signatureTypes.forEach { type ->
                            LiquorSwatch(
                                type = type,
                                size = 18.dp,
                                ringColor = MaterialTheme.colorScheme.surface,
                                ringWidth = 2.dp,
                            )
                        }
                    }
                }
            }
            Box {
                IconButton(onClick = { menuOpen = true }) {
                    Icon(
                        imageVector = Icons.Filled.MoreVert,
                        contentDescription = stringResource(R.string.a11y_board_more),
                    )
                }
                DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.board_delete)) },
                        onClick = {
                            menuOpen = false
                            onDelete()
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun EmptyBoards(
    onCreate: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.padding(horizontal = 32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = stringResource(R.string.boards_empty_title),
            style = MaterialTheme.typography.titleMedium,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = stringResource(R.string.boards_empty_hint),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(20.dp))
        Button(onClick = onCreate) {
            Text(stringResource(R.string.boards_empty_action))
        }
    }
}

@Composable
private fun CreateBoardDialog(
    onCreate: (label: String, template: TierTemplate) -> Unit,
    onDismiss: () -> Unit,
) {
    // Label is Saveable so a rotation mid-typing does not erase user input. Template is plain
    // remember: the dialog's lifecycle is short-lived enough that re-defaulting to F-S on rotation
    // is acceptable, and skipping a Saver avoids a per-enum custom adapter.
    var label by rememberSaveable { mutableStateOf("") }
    var template by remember { mutableStateOf(TierTemplate.F_TO_S) }
    val canCreate = label.trim().isNotEmpty()

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.boards_create_title)) },
        text = {
            Column(
                modifier = Modifier.imePadding(),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                OutlinedTextField(
                    value = label,
                    onValueChange = { label = it },
                    label = { Text(stringResource(R.string.boards_create_name_label)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Text(
                    text = stringResource(R.string.boards_create_template_label),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                TemplateOption(
                    selected = template == TierTemplate.F_TO_S,
                    title = stringResource(R.string.tier_template_f_s),
                    hint = stringResource(R.string.tier_template_f_s_hint),
                    onSelect = { template = TierTemplate.F_TO_S },
                )
                TemplateOption(
                    selected = template == TierTemplate.ONE_TO_TEN,
                    title = stringResource(R.string.tier_template_one_to_ten),
                    hint = stringResource(R.string.tier_template_one_to_ten_hint),
                    onSelect = { template = TierTemplate.ONE_TO_TEN },
                )
                TemplateOption(
                    selected = template == TierTemplate.BLANK,
                    title = stringResource(R.string.tier_template_blank),
                    hint = stringResource(R.string.tier_template_blank_hint),
                    onSelect = { template = TierTemplate.BLANK },
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onCreate(label.trim(), template) },
                enabled = canCreate,
            ) { Text(stringResource(R.string.action_create)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) }
        },
    )
}

@Composable
private fun TemplateOption(
    selected: Boolean,
    title: String,
    hint: String,
    onSelect: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .selectable(selected = selected, role = Role.RadioButton, onClick = onSelect)
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = null)
        Spacer(Modifier.width(8.dp))
        Column {
            Text(text = title, style = MaterialTheme.typography.bodyLarge)
            Text(
                text = hint,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
