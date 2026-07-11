package com.macsia.teatiers.ui.board

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.macsia.teatiers.R
import com.macsia.teatiers.ui.components.EnrichmentStatus
import com.macsia.teatiers.ui.components.LiquorSwatch
import com.macsia.teatiers.ui.components.TooltipIconButton
import com.macsia.teatiers.ui.components.TypeChip
import com.macsia.teatiers.viewmodel.MyTeaItem
import com.macsia.teatiers.viewmodel.MyTeasSortOption
import com.macsia.teatiers.viewmodel.MyTeasViewModel

private val ThumbSize = 44.dp

/**
 * Cross-board "my teas" view (decisions.md #27): a searchable, type-filterable list of every
 * user-tea. Read-only — tapping a row opens the shared tea detail, which owns edit/delete.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MyTeasScreen(
    onBack: () -> Unit,
    onOpenTea: (String) -> Unit,
    modifier: Modifier = Modifier,
    viewModel: MyTeasViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var sortMenuExpanded by remember { mutableStateOf(false) }

    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.my_teas_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.a11y_back),
                        )
                    }
                },
                actions = {
                    // ponytail: material-icons-core has no Sort glyph and pulling in the much larger
                    // -extended artifact for one icon isn't worth it; List reads close enough. R4-JRN-1:
                    // a tooltip surfaces "Сортировка" on long-press/hover so this List glyph isn't
                    // confused with the same glyph used for "Каталог" on the Boards screen.
                    TooltipIconButton(
                        label = stringResource(R.string.a11y_my_teas_sort),
                        icon = Icons.AutoMirrored.Filled.List,
                        onClick = { sortMenuExpanded = true },
                    )
                    DropdownMenu(expanded = sortMenuExpanded, onDismissRequest = { sortMenuExpanded = false }) {
                        SortMenuItem(
                            label = stringResource(R.string.my_teas_sort_name),
                            selected = state.sort == MyTeasSortOption.NAME,
                            onClick = { viewModel.setSort(MyTeasSortOption.NAME); sortMenuExpanded = false },
                        )
                        SortMenuItem(
                            label = stringResource(R.string.my_teas_sort_type),
                            selected = state.sort == MyTeasSortOption.TYPE,
                            onClick = { viewModel.setSort(MyTeasSortOption.TYPE); sortMenuExpanded = false },
                        )
                        SortMenuItem(
                            label = stringResource(R.string.my_teas_sort_created),
                            selected = state.sort == MyTeasSortOption.CREATED,
                            onClick = { viewModel.setSort(MyTeasSortOption.CREATED); sortMenuExpanded = false },
                        )
                    }
                },
            )
        },
    ) { innerPadding ->
        val focusManager = LocalFocusManager.current
        Column(Modifier.fillMaxSize().padding(innerPadding)) {
            OutlinedTextField(
                value = state.query,
                onValueChange = viewModel::setQuery,
                label = { Text(stringResource(R.string.my_teas_search_hint)) },
                singleLine = true,
                // Search is live; the IME action just collapses the keyboard off the list (audit).
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { focusManager.clearFocus() }),
                leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
                trailingIcon = {
                    if (state.query.isNotEmpty()) {
                        IconButton(onClick = { viewModel.setQuery("") }) {
                            Icon(
                                Icons.Filled.Clear,
                                contentDescription = stringResource(R.string.a11y_search_clear),
                            )
                        }
                    }
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 4.dp),
            )

            if (state.availableTypes.isNotEmpty()) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState())
                        .padding(horizontal = 16.dp, vertical = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    FilterChip(
                        selected = state.typeFilter == null,
                        onClick = { viewModel.clearTypeFilter() },
                        label = { Text(stringResource(R.string.my_teas_filter_all)) },
                    )
                    state.availableTypes.forEach { type ->
                        FilterChip(
                            selected = state.typeFilter == type,
                            onClick = { viewModel.toggleType(type) },
                            label = { Text(stringResource(type.labelRes)) },
                        )
                    }
                }
            }

            when {
                // UX3-P2-3: spinner until Room's first read lands, so a cold start doesn't flash the
                // "no teas yet" empty state (which is indistinguishable from a real empty collection).
                state.loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
                state.collectionEmpty -> EmptyMyTeas(
                    title = stringResource(R.string.my_teas_empty_title),
                    hint = stringResource(R.string.my_teas_empty_hint),
                )
                state.items.isEmpty() -> EmptyMyTeas(
                    title = stringResource(R.string.my_teas_none_found),
                    hint = stringResource(R.string.my_teas_none_found_hint),
                )
                else -> LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 24.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    items(state.items, key = { it.tea.id }) { item ->
                        MyTeaRow(
                            item = item,
                            onClick = { onOpenTea(item.tea.id) },
                            modifier = Modifier.animateItem(),
                        )
                    }
                }
            }
        }
    }
}

/** One radio-style row in the sort dropdown (UX-F-2). */
@Composable
private fun SortMenuItem(label: String, selected: Boolean, onClick: () -> Unit) {
    DropdownMenuItem(
        text = { Text(label) },
        leadingIcon = { RadioButton(selected = selected, onClick = null) },
        onClick = onClick,
    )
}

@Composable
private fun MyTeaRow(item: MyTeaItem, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val tea = item.tea
    val boardText = if (item.boardCount > 0) {
        pluralStringResource(R.plurals.my_tea_board_count, item.boardCount, item.boardCount)
    } else {
        stringResource(R.string.my_teas_no_board)
    }
    Surface(
        onClick = onClick,
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 1.dp,
        // Merge into one TalkBack node so the whole row reads as a single button (name, type, count).
        modifier = modifier.fillMaxWidth().semantics(mergeDescendants = true) { role = Role.Button },
    ) {
        Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
            TeaThumb(uri = tea.photos.firstOrNull()?.uri, fallbackType = tea.type)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    text = tea.displayName,
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (tea.secondaryName.isNotEmpty()) {
                    Text(
                        text = tea.secondaryName,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Spacer(Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    TypeChip(type = tea.type)
                    Spacer(Modifier.width(10.dp))
                    Text(
                        text = boardText,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                // Display-only enrichment state (UX3-P1-4): My Teas showed nothing for an in-flight /
                // failed resolve. Retry lives on the detail screen (this row is one merged TalkBack
                // button, so a nested retry control here would break that), reached by tapping through.
                EnrichmentStatus(state = tea.enrichmentState, modifier = Modifier.padding(top = 6.dp))
            }
        }
    }
}

@Composable
private fun TeaThumb(uri: String?, fallbackType: com.macsia.teatiers.domain.model.TeaType) {
    if (uri == null) {
        LiquorSwatch(type = fallbackType, size = ThumbSize)
    } else {
        Box(
            modifier = Modifier
                .size(ThumbSize)
                .clip(MaterialTheme.shapes.small)
                .background(MaterialTheme.colorScheme.surfaceVariant),
        ) {
            // Shared loader: gets the broken-image fallback for a revoked/deleted file (audit #6).
            PhotoImage(uri = uri, modifier = Modifier.fillMaxSize())
        }
    }
}

@Composable
private fun EmptyMyTeas(title: String, hint: String?) {
    Column(
        modifier = Modifier.fillMaxSize().padding(horizontal = 32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(text = title, style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center)
        if (hint != null) {
            Spacer(Modifier.height(8.dp))
            Text(
                text = hint,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
        }
    }
}
