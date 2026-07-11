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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.clickable
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
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
import androidx.compose.ui.semantics.Role
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
import com.macsia.teatiers.ui.components.BoardChoice
import com.macsia.teatiers.ui.components.BoardPickerDialog
import com.macsia.teatiers.ui.components.TypeChip
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
    val typeFilter by viewModel.typeFilter.collectAsStateWithLifecycle()
    val availableTypes by viewModel.availableTypes.collectAsStateWithLifecycle()
    val catalogDetail by viewModel.catalogDetail.collectAsStateWithLifecycle()
    var teaToPlace by remember { mutableStateOf<CatalogTea?>(null) }
    // The CatalogTea the detail sheet was opened for (R4-JRN-2), so "use this tea" routes into the picker.
    var detailTea by remember { mutableStateOf<CatalogTea?>(null) }

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
                // R4-JRN-2: name what this screen IS — a shared catalog, not the user's own collection —
                // which a first-time user otherwise can't tell apart from My Teas.
                supportingText = { Text(stringResource(R.string.browse_catalog_search_hint)) },
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
            // UX-F-1: type-filter chips, populated from the catalog's own facets — no scrolling
            // through pages first just to discover which types exist.
            if (availableTypes.isNotEmpty()) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState())
                        .padding(horizontal = 16.dp, vertical = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    FilterChip(
                        selected = typeFilter == null,
                        onClick = { viewModel.setTypeFilter(null) },
                        label = { Text(stringResource(R.string.my_teas_filter_all)) },
                    )
                    availableTypes.forEach { type ->
                        FilterChip(
                            selected = typeFilter == type,
                            onClick = { viewModel.setTypeFilter(if (typeFilter == type) null else type) },
                            label = { Text(stringResource(type.labelRes)) },
                        )
                    }
                }
            }
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
                    BrowseCatalogUiState.RateLimited ->
                        RetryMessage(stringResource(R.string.catalog_detail_rate_limited), viewModel::retry)
                    BrowseCatalogUiState.Error ->
                        RetryMessage(stringResource(R.string.browse_catalog_error), viewModel::retry)
                    is BrowseCatalogUiState.Loaded ->
                        BrowseList(
                            state = s,
                            onPick = { teaToPlace = it },
                            onInfo = { detailTea = it; viewModel.openCatalogDetail(it.id) },
                            onLoadMore = viewModel::loadMore,
                            onRetryMore = viewModel::retry,
                        )
                }
            }
        }
    }

    teaToPlace?.let { tea ->
        BoardPickerDialog(
            title = stringResource(R.string.browse_pick_board_title, tea.displayName),
            boards = boards.map { BoardChoice(it.id, it.name) },
            emptyText = stringResource(R.string.browse_pick_board_empty),
            onPick = { boardId ->
                teaToPlace = null
                onAddToBoard(boardId, tea.id)
            },
            onDismiss = { teaToPlace = null },
            // "Create a board" is always offered (UX2-P1-2); send the user back to the home screen,
            // which owns board creation. UX3-P2-8: the hint explains — in every case, not just the
            // empty-list one — that this drops the current pick and the tea is re-added afterward.
            onCreateBoard = {
                teaToPlace = null
                onBack()
            },
            hint = stringResource(R.string.browse_pick_board_create_hint),
        )
    }

    // R4-JRN-2: preview a catalog tea before committing. "Use this tea" closes the sheet and routes the
    // tea into the same board picker a direct tap would, so the preview is a non-committing detour.
    CatalogDetailSheet(
        state = catalogDetail,
        onDismiss = {
            detailTea = null
            viewModel.closeCatalogDetail()
        },
        onUse = {
            val tea = detailTea
            detailTea = null
            viewModel.closeCatalogDetail()
            if (tea != null) teaToPlace = tea
        },
        onRetry = viewModel::retryCatalogDetail,
    )
}

@Composable
private fun BrowseList(
    state: BrowseCatalogUiState.Loaded,
    onPick: (CatalogTea) -> Unit,
    onInfo: (CatalogTea) -> Unit,
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
            BrowseTeaRow(tea = tea, onClick = { onPick(tea) }, onInfo = { onInfo(tea) })
        }
        when {
            state.appending -> item("appending") { FooterProgress() }
            state.appendFailed -> item("append-failed") { FooterRetry(onRetryMore) }
        }
    }
}

@Composable
private fun BrowseTeaRow(tea: CatalogTea, onClick: () -> Unit, onInfo: () -> Unit) {
    val addDescription = stringResource(R.string.a11y_browse_add, tea.displayName)
    val infoDescription = stringResource(R.string.a11y_catalog_info, tea.displayName)
    Surface(
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 1.dp,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            Modifier.padding(start = 14.dp, top = 4.dp, bottom = 4.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            // Tapping the body adds the tea to a board; the info button (a separate a11y node) previews it.
            Column(
                modifier = Modifier
                    .weight(1f)
                    .clickable(role = Role.Button, onClick = onClick)
                    .semantics(mergeDescendants = true) { contentDescription = addDescription }
                    .padding(vertical = 10.dp),
            ) {
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
            TypeChip(type = tea.type)
            IconButton(onClick = onInfo) {
                Icon(imageVector = Icons.Filled.Info, contentDescription = infoDescription)
            }
        }
    }
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
