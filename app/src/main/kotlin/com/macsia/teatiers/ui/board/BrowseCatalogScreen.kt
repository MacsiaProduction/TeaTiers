package com.macsia.teatiers.ui.board

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.macsia.teatiers.R
import com.macsia.teatiers.domain.model.CatalogTea
import com.macsia.teatiers.ui.components.TypeChip
import com.macsia.teatiers.viewmodel.BoardPick
import com.macsia.teatiers.viewmodel.BrowseCatalogUiState
import com.macsia.teatiers.viewmodel.BrowseCatalogViewModel

/** Load the next page once the user scrolls within this many items of the end. */
private const val LOAD_MORE_PREFETCH = 6

/**
 * Browse the whole shared catalog (#42 follow-up): a cursor-paginated list of every catalog tea
 * (not just the user's). Tapping a tea asks which board to add it to, then opens the add form
 * pre-picked so the user reviews/places it exactly as if they had searched for it.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BrowseCatalogScreen(
    onBack: () -> Unit,
    onAddToBoard: (boardId: String, catalogTeaId: Long) -> Unit,
    modifier: Modifier = Modifier,
    viewModel: BrowseCatalogViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val boards by viewModel.boards.collectAsStateWithLifecycle()
    val query by viewModel.query.collectAsStateWithLifecycle()
    var teaToPlace by remember { mutableStateOf<CatalogTea?>(null) }

    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.browse_catalog_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.a11y_back),
                        )
                    }
                },
            )
        },
    ) { innerPadding ->
        val focusManager = LocalFocusManager.current
        Column(Modifier.fillMaxSize().padding(innerPadding)) {
            OutlinedTextField(
                value = query,
                onValueChange = viewModel::setQuery,
                label = { Text(stringResource(R.string.catalog_search_label)) },
                singleLine = true,
                // Search is live/debounced; the IME action just collapses the keyboard (audit).
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { focusManager.clearFocus() }),
                leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
                trailingIcon = {
                    if (query.isNotEmpty()) {
                        IconButton(onClick = { viewModel.setQuery("") }) {
                            Icon(Icons.Filled.Clear, contentDescription = stringResource(R.string.a11y_search_clear))
                        }
                    }
                },
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
            )
            Box(Modifier.fillMaxSize()) {
                when (val s = state) {
                    BrowseCatalogUiState.Loading -> CenteredProgress()
                    BrowseCatalogUiState.Empty -> CenteredMessage(
                        stringResource(
                            if (query.isNotBlank()) R.string.my_teas_none_found
                            else R.string.browse_catalog_empty,
                        ),
                    )
                    BrowseCatalogUiState.Offline ->
                        RetryMessage(stringResource(R.string.browse_catalog_offline), viewModel::retry)
                    BrowseCatalogUiState.Error ->
                        RetryMessage(stringResource(R.string.browse_catalog_error), viewModel::retry)
                    is BrowseCatalogUiState.Loaded ->
                        BrowseList(
                            state = s,
                            onPick = { teaToPlace = it },
                            onLoadMore = viewModel::loadMore,
                            onRetryMore = viewModel::retry,
                        )
                }
            }
        }
    }

    teaToPlace?.let { tea ->
        BoardPickerDialog(
            tea = tea,
            boards = boards,
            onDismiss = { teaToPlace = null },
            onPick = { boardId ->
                teaToPlace = null
                onAddToBoard(boardId, tea.id)
            },
            // No boards yet: send the user back to the home screen, which owns board creation.
            onGoCreateBoard = {
                teaToPlace = null
                onBack()
            },
        )
    }
}

@Composable
private fun BrowseList(
    state: BrowseCatalogUiState.Loaded,
    onPick: (CatalogTea) -> Unit,
    onLoadMore: () -> Unit,
    onRetryMore: () -> Unit,
) {
    val listState = rememberLazyListState()
    // Near-end detection off the layout (not a captured list size) so the closure never goes stale.
    val reachedEnd by remember {
        derivedStateOf {
            val info = listState.layoutInfo
            val last = info.visibleItemsInfo.lastOrNull()?.index ?: return@derivedStateOf false
            last >= info.totalItemsCount - 1 - LOAD_MORE_PREFETCH
        }
    }
    LaunchedEffect(reachedEnd, state.canLoadMore) {
        if (reachedEnd && state.canLoadMore) onLoadMore()
    }

    LazyColumn(
        state = listState,
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        if (state.truncated) {
            item("truncated-hint") {
                Text(
                    text = stringResource(R.string.catalog_search_truncated_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        items(state.teas, key = { it.id }) { tea ->
            BrowseTeaRow(tea = tea, onClick = { onPick(tea) })
        }
        when {
            state.appending -> item("appending") { FooterProgress() }
            state.appendFailed -> item("append-failed") { FooterRetry(onRetryMore) }
        }
    }
}

@Composable
private fun BrowseTeaRow(tea: CatalogTea, onClick: () -> Unit) {
    val addDescription = stringResource(R.string.a11y_browse_add, tea.displayName)
    Surface(
        onClick = onClick,
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 1.dp,
        modifier = Modifier
            .fillMaxWidth()
            .semantics(mergeDescendants = true) { contentDescription = addDescription },
    ) {
        Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(
                    text = tea.displayName,
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (tea.secondaryName.isNotBlank()) {
                    Text(
                        text = tea.secondaryName,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                if (tea.isUnverified) {
                    Text(
                        text = stringResource(R.string.catalog_result_unverified),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Spacer(Modifier.width(10.dp))
            TypeChip(type = tea.type)
        }
    }
}

/**
 * "Add to which board?" — boards are listed as tappable rows. With no boards yet, the dialog
 * explains the user must create one first (the boards home owns creation).
 */
@Composable
private fun BoardPickerDialog(
    tea: CatalogTea,
    boards: List<BoardPick>,
    onDismiss: () -> Unit,
    onPick: (String) -> Unit,
    onGoCreateBoard: () -> Unit,
) {
    val hasBoards = boards.isNotEmpty()
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.browse_pick_board_title, tea.displayName)) },
        text = {
            if (!hasBoards) {
                Text(stringResource(R.string.browse_pick_board_empty))
            } else {
                Column(
                    modifier = Modifier
                        .heightIn(max = 320.dp)
                        .verticalScroll(rememberScrollState()),
                ) {
                    // TextButton rows read as tappable (the bare-Text rows did not), full-width left-aligned.
                    boards.forEach { board ->
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
        },
        // With no boards, the primary action takes the user to where boards are created.
        confirmButton = {
            if (!hasBoards) {
                TextButton(onClick = onGoCreateBoard) {
                    Text(stringResource(R.string.boards_create_action))
                }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.browse_pick_board_cancel))
            }
        },
    )
}

@Composable
private fun CenteredProgress() {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        CircularProgressIndicator()
    }
}

@Composable
private fun CenteredMessage(message: String) {
    Box(Modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
        Text(
            text = message,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
    }
}

@Composable
private fun RetryMessage(message: String, onRetry: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize().padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = message,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(16.dp))
        OutlinedButton(onClick = onRetry) {
            Text(stringResource(R.string.browse_catalog_retry))
        }
    }
}

@Composable
private fun FooterProgress() {
    Box(Modifier.fillMaxWidth().padding(16.dp), contentAlignment = Alignment.Center) {
        CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 2.dp)
    }
}

@Composable
private fun FooterRetry(onRetry: () -> Unit) {
    Box(Modifier.fillMaxWidth().padding(16.dp), contentAlignment = Alignment.Center) {
        OutlinedButton(onClick = onRetry) {
            Text(stringResource(R.string.browse_catalog_load_more_retry))
        }
    }
}
