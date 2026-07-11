package com.macsia.teatiers.ui.board

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.animation.core.spring
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.macsia.teatiers.R
import com.macsia.teatiers.ui.theme.TeaTheme
import com.macsia.teatiers.ui.theme.TierColorPresetsArgb
import com.macsia.teatiers.ui.theme.pickOnColorArgb
import com.macsia.teatiers.viewmodel.BoardScreenState
import com.macsia.teatiers.viewmodel.BoardViewModel
import com.macsia.teatiers.viewmodel.CollectUiEvents
import com.macsia.teatiers.viewmodel.TierWithPlacements

private val ScreenInset = 16.dp
// Re-exported so the rest of the file keeps its short name; the underlying list lives in
// `ui/theme/TierColorPalette.kt` where the contrast unit test can reach it without pulling
// in Compose UI.
private val TierColorPresets: List<Long> = TierColorPresetsArgb

/**
 * Edits the bound board's tiers: rename, recolor, reorder, add, and remove. Reuses [BoardViewModel]
 * so changes land through the same Room-backed repository the board screen reads. A tier without an
 * explicit color falls back to the position-based ramp, matching how the board renders it.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TierEditorScreen(
    boardId: String,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: BoardViewModel = hiltViewModel(),
) {
    LaunchedEffect(boardId) { viewModel.bind(boardId) }
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val tiers = (state as? BoardScreenState.Loaded)?.board?.tiers.orEmpty()
    val order = tiers.map { it.tier.id }
    val ramp = TeaTheme.colors.tierRamp
    val newTierLabel = stringResource(R.string.tier_new_label)
    val snackbarHostState = remember { SnackbarHostState() }
    CollectUiEvents(viewModel.events, snackbarHostState)

    var colorDialogTierId by remember { mutableStateOf<String?>(null) }
    var deleteDialogTier by remember { mutableStateOf<TierWithPlacements?>(null) }

    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.tier_editor_title)) },
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
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(innerPadding).imePadding(),
            contentPadding = PaddingValues(vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            itemsIndexed(tiers, key = { _, row -> row.tier.id }) { index, row ->
                val rampColor = row.tier.colorArgb?.let { Color(it) } ?: ramp.getOrElse(index) { ramp.last() }
                TierEditRow(
                    row = row,
                    rampColor = rampColor,
                    isFirst = index == 0,
                    isLast = index == tiers.lastIndex,
                    onLabelChange = { viewModel.renameTier(row.tier.id, it) },
                    onMoveUp = { viewModel.reorderTiers(order.swap(index, index - 1)) },
                    onMoveDown = { viewModel.reorderTiers(order.swap(index, index + 1)) },
                    onColorClick = { colorDialogTierId = row.tier.id },
                    onDeleteClick = { deleteDialogTier = row },
                    // animateItem (LazyItemScope) glides each row when the user reorders via the
                    // up/down arrows, so two presses look like one continuous swap instead of
                    // two jump-cuts.
                    modifier = Modifier
                        .padding(horizontal = ScreenInset)
                        .animateItem(
                            placementSpec = spring(dampingRatio = 0.85f, stiffness = 500f),
                        ),
                )
            }
            item {
                OutlinedButton(
                    onClick = { viewModel.addTier(newTierLabel) },
                    modifier = Modifier.fillMaxWidth().padding(horizontal = ScreenInset),
                ) {
                    Icon(Icons.Filled.Add, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.tier_add))
                }
            }
        }
    }

    colorDialogTierId?.let { tierId ->
        val current = tiers.firstOrNull { it.tier.id == tierId }?.tier?.colorArgb
        TierColorDialog(
            currentColor = current,
            onPick = { argb ->
                viewModel.setTierColor(tierId, argb)
                colorDialogTierId = null
            },
            onDismiss = { colorDialogTierId = null },
        )
    }

    deleteDialogTier?.let { row ->
        AlertDialog(
            onDismissRequest = { deleteDialogTier = null },
            title = { Text(stringResource(R.string.tier_delete_title)) },
            text = {
                val count = row.placements.size
                Text(
                    if (count > 0) {
                        pluralStringResource(R.plurals.tier_delete_message_count, count, count, row.tier.label)
                    } else {
                        stringResource(R.string.tier_delete_message, row.tier.label)
                    },
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.removeTier(row.tier.id)
                    deleteDialogTier = null
                }) { Text(stringResource(R.string.action_delete)) }
            },
            dismissButton = {
                TextButton(onClick = { deleteDialogTier = null }) {
                    Text(stringResource(R.string.action_cancel))
                }
            },
        )
    }
}

@Composable
private fun TierEditRow(
    row: TierWithPlacements,
    rampColor: Color,
    isFirst: Boolean,
    isLast: Boolean,
    onLabelChange: (String) -> Unit,
    onMoveUp: () -> Unit,
    onMoveDown: () -> Unit,
    onColorClick: () -> Unit,
    onDeleteClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // Local label state seeded once per tier id; the row keeps the typed text across the board's
    // re-emissions, while each edit also persists through the ViewModel (blank labels are ignored).
    var label by remember(row.tier.id) { mutableStateOf(row.tier.label) }
    val onRamp = Color(pickOnColorArgb(rampColor.luminance()))
    val colorLabel = stringResource(R.string.a11y_tier_color)

    Surface(
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 1.dp,
        modifier = modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.padding(10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Box(
                modifier = Modifier
                    .size(48.dp)
                    .clip(MaterialTheme.shapes.small)
                    .background(rampColor)
                    .clickable(onClickLabel = colorLabel, onClick = onColorClick),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    // Fall back to the saved label so clearing the field to retype doesn't blank the
                    // swatch (and its a11y) while the DB still holds the old name. R4-LOC-4: truncate by
                    // code points, not UTF-16 units, so a leading emoji (e.g. a flag = one surrogate pair)
                    // isn't cut in half into a broken glyph.
                    text = (label.takeIf { it.isNotBlank() } ?: row.tier.label).takeCodePoints(2),
                    color = onRamp,
                    style = MaterialTheme.typography.titleMedium,
                )
                // UX-P2-10: a small edit glyph signals the swatch opens a color picker — previously
                // nothing distinguished it from plain, non-interactive tier-color decoration.
                Icon(
                    imageVector = Icons.Filled.Edit,
                    contentDescription = null, // the box's own onClickLabel already carries the a11y label
                    tint = onRamp.copy(alpha = 0.7f),
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(2.dp)
                        .size(12.dp),
                )
            }
            OutlinedTextField(
                value = label,
                onValueChange = {
                    label = it
                    onLabelChange(it)
                },
                label = { Text(stringResource(R.string.tier_label_field)) },
                isError = label.isBlank(),
                // A blank label is ignored by the repository (the tier keeps its name), so flag it
                // inline rather than silently not-saving while the field shows empty.
                supportingText = { if (label.isBlank()) Text(stringResource(R.string.tier_label_required)) },
                singleLine = true,
                modifier = Modifier.weight(1f),
            )
            Column {
                IconButton(onClick = onMoveUp, enabled = !isFirst) {
                    Icon(
                        imageVector = Icons.Filled.KeyboardArrowUp,
                        contentDescription = stringResource(R.string.a11y_tier_move_up, row.tier.label),
                    )
                }
                IconButton(onClick = onMoveDown, enabled = !isLast) {
                    Icon(
                        imageVector = Icons.Filled.KeyboardArrowDown,
                        contentDescription = stringResource(R.string.a11y_tier_move_down, row.tier.label),
                    )
                }
            }
            // UX2-P1-12: deleting the LAST tier left the board with zero tier rows (every one of its
            // teas dumped into the tray, no ranked structure left) and no guard against it — mirror
            // the move-up/move-down disabled state above (isFirst && isLast means "the only tier").
            val isOnlyTier = isFirst && isLast
            IconButton(onClick = onDeleteClick, enabled = !isOnlyTier) {
                Icon(
                    imageVector = Icons.Filled.Delete,
                    contentDescription = stringResource(R.string.a11y_tier_delete),
                    tint = if (isOnlyTier) {
                        MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
                    } else {
                        MaterialTheme.colorScheme.error
                    },
                )
            }
        }
    }
}

@Composable
private fun TierColorDialog(
    currentColor: Long?,
    onPick: (Long?) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.tier_color_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                // UX3-P1-7: each swatch was a bare clickable Box — a TalkBack user heard nothing and
                // couldn't tell which color was applied. selectable(role=RadioButton) announces the
                // selected state; an ordinal contentDescription distinguishes the otherwise-unnamed
                // presets. (A precise color name per preset would be ideal but brittle for this palette.)
                TierColorPresets.chunked(4).forEachIndexed { rowIndex, rowColors ->
                    Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                        rowColors.forEachIndexed { colIndex, argb ->
                            val selected = currentColor == argb
                            val label = stringResource(
                                R.string.tier_color_swatch_a11y,
                                rowIndex * 4 + colIndex + 1,
                                TierColorPresets.size,
                            )
                            Box(
                                modifier = Modifier
                                    .size(48.dp) // UX-P2-3: Material's 48dp minimum touch target (was 44dp)
                                    .clip(CircleShape)
                                    .background(Color(argb))
                                    .border(
                                        width = if (selected) 3.dp else 1.dp,
                                        color = if (selected) {
                                            MaterialTheme.colorScheme.primary
                                        } else {
                                            MaterialTheme.colorScheme.outlineVariant
                                        },
                                        shape = CircleShape,
                                    )
                                    .selectable(
                                        selected = selected,
                                        role = Role.RadioButton,
                                        onClick = { onPick(argb) },
                                    )
                                    .semantics { contentDescription = label },
                            )
                        }
                    }
                }
                TextButton(onClick = { onPick(null) }) {
                    Text(stringResource(R.string.tier_color_default))
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) }
        },
    )
}

/** Returns a copy with the items at [i] and [j] swapped; the original if either index is invalid. */
private fun List<String>.swap(i: Int, j: Int): List<String> {
    if (i !in indices || j !in indices) return this
    return toMutableList().also {
        it[i] = this[j]
        it[j] = this[i]
    }
}

/** First [n] Unicode code points (not UTF-16 units), so an emoji/surrogate pair is never split. */
private fun String.takeCodePoints(n: Int): String {
    if (n <= 0) return ""
    if (isEmpty() || codePointCount(0, length) <= n) return this
    return substring(0, offsetByCodePoints(0, n))
}
