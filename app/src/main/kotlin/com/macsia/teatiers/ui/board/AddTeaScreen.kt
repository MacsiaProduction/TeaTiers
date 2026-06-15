package com.macsia.teatiers.ui.board

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
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
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.macsia.teatiers.R
import com.macsia.teatiers.domain.model.TeaType
import com.macsia.teatiers.viewmodel.AddTeaViewModel
import com.macsia.teatiers.viewmodel.CollectUiEvents
import com.macsia.teatiers.viewmodel.ExtendedRateDimensions
import com.macsia.teatiers.viewmodel.PurchaseDraft
import com.macsia.teatiers.viewmodel.PurchaseKind
import com.macsia.teatiers.viewmodel.QuickRateDimensions
import com.macsia.teatiers.viewmodel.visibleExtendedDimensions
import kotlin.math.roundToInt

private val ScreenInset = 16.dp

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddTeaScreen(
    boardId: String?,
    onBack: () -> Unit,
    onSaved: () -> Unit,
    modifier: Modifier = Modifier,
    teaId: String? = null,
    viewModel: AddTeaViewModel = hiltViewModel(),
) {
    LaunchedEffect(boardId, teaId) { viewModel.bind(boardId = boardId, teaId = teaId) }
    val form by viewModel.form.collectAsStateWithLifecycle()
    val tiers by viewModel.tiers.collectAsStateWithLifecycle()
    val placementCount by viewModel.placementCount.collectAsStateWithLifecycle()
    val photos by viewModel.photos.collectAsStateWithLifecycle()
    val isEdit = teaId != null
    var menuExpanded by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    var flavorsExpanded by remember { mutableStateOf(false) }
    val snackbarHostState = remember { SnackbarHostState() }
    CollectUiEvents(viewModel.events, snackbarHostState)
    // Focus-on-error: the Save button invokes `submit`, which synchronously arms the
    // pendingNameFocus flag when the form is invalid. We then pop the flag and route focus
    // to the nameRu field so the user knows where to look. Snackbar covers the "why".
    val nameRuFocusRequester = remember { FocusRequester() }

    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = {
                    Text(stringResource(if (isEdit) R.string.edit_tea_title else R.string.add_tea_title))
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.a11y_back),
                        )
                    }
                },
                actions = {
                    TextButton(
                        // Save stays tappable even when the form is invalid so the user gets
                        // the snackbar + focus pump instead of silently disabled UI.
                        onClick = {
                            viewModel.submit(onSaved)
                            if (viewModel.consumeNameRequiredFocus()) {
                                nameRuFocusRequester.requestFocus()
                            }
                        },
                    ) {
                        Text(stringResource(R.string.action_save))
                    }
                    if (isEdit) {
                        IconButton(onClick = { menuExpanded = true }) {
                            Icon(
                                imageVector = Icons.Filled.MoreVert,
                                contentDescription = stringResource(R.string.a11y_card_menu),
                            )
                        }
                        DropdownMenu(
                            expanded = menuExpanded,
                            onDismissRequest = { menuExpanded = false },
                        ) {
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.action_delete_tea_forever)) },
                                onClick = {
                                    menuExpanded = false
                                    confirmDelete = true
                                },
                            )
                        }
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(hostState = snackbarHostState) },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
                .imePadding()
                .padding(horizontal = ScreenInset, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            // Ripple notice (decisions.md #42): only when the tea sits on >1 board, so editing
            // a single-placement tea does not nag with an irrelevant warning.
            if (isEdit && placementCount > 1) {
                Text(
                    text = stringResource(R.string.edit_ripple_caption),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            OutlinedTextField(
                value = form.nameRu,
                onValueChange = { v -> viewModel.update { it.copy(nameRu = v) } },
                label = { Text(stringResource(R.string.field_name_ru)) },
                singleLine = true,
                isError = form.nameRu.isBlank(),
                supportingText = { Text(stringResource(R.string.field_name_ru_hint)) },
                modifier = Modifier
                    .fillMaxWidth()
                    .focusRequester(nameRuFocusRequester),
            )
            OutlinedTextField(
                value = form.nameEn,
                onValueChange = { v -> viewModel.update { it.copy(nameEn = v) } },
                label = { Text(stringResource(R.string.field_name_en)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value = form.pinyin,
                    onValueChange = { v -> viewModel.update { it.copy(pinyin = v) } },
                    label = { Text(stringResource(R.string.field_pinyin)) },
                    singleLine = true,
                    modifier = Modifier.weight(1f),
                )
                OutlinedTextField(
                    value = form.nameZh,
                    onValueChange = { v -> viewModel.update { it.copy(nameZh = v) } },
                    label = { Text(stringResource(R.string.field_name_zh)) },
                    singleLine = true,
                    modifier = Modifier.weight(1f),
                )
            }

            FieldLabel(stringResource(R.string.field_type))
            ChipRow {
                TeaType.entries.forEach { type ->
                    FilterChip(
                        selected = type == form.type,
                        onClick = { viewModel.update { it.copy(type = type) } },
                        label = { Text(stringResource(type.labelRes)) },
                    )
                }
            }

            OutlinedTextField(
                value = form.origin,
                onValueChange = { v -> viewModel.update { it.copy(origin = v) } },
                label = { Text(stringResource(R.string.field_origin)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )

            PhotoStripField(
                photos = photos,
                onPick = viewModel::onAddPhoto,
                onRemove = viewModel::onRemovePhoto,
                onReorder = viewModel::onReorderPhotos,
            )

            // Quick-rate (decisions.md #28) is the default short list; "show all" reveals the rest
            // of the locked 11-dim vocabulary (#23/#44) so every axis the radar draws is enterable.
            // A rated extended dimension stays visible even when collapsed (see visibleExtendedDimensions).
            Column(
                modifier = Modifier.fillMaxWidth().animateContentSize(),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                FieldLabel(stringResource(R.string.field_flavor))
                Text(
                    text = stringResource(R.string.flavor_scale_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 4.dp),
                )
                QuickRateDimensions.forEach { dimension ->
                    FlavorSlider(
                        label = stringResource(dimension.labelRes),
                        value = form.flavors[dimension] ?: 0,
                        onValue = { viewModel.setFlavor(dimension, it) },
                    )
                }
                visibleExtendedDimensions(form.flavors, flavorsExpanded).forEach { dimension ->
                    FlavorSlider(
                        label = stringResource(dimension.labelRes),
                        value = form.flavors[dimension] ?: 0,
                        onValue = { viewModel.setFlavor(dimension, it) },
                    )
                }
                val hiddenExtended = ExtendedRateDimensions.count { (form.flavors[it] ?: 0) == 0 }
                if (flavorsExpanded || hiddenExtended > 0) {
                    TextButton(onClick = { flavorsExpanded = !flavorsExpanded }) {
                        Text(
                            stringResource(
                                if (flavorsExpanded) R.string.flavor_show_less else R.string.flavor_show_all,
                            ),
                        )
                    }
                }
            }

            // Tier picker is hidden in edit mode: drag-to-rank owns placement on the board, and
            // the form's tierId is ignored on save when editing an existing tea.
            if (!isEdit) {
                FieldLabel(stringResource(R.string.field_tier))
                ChipRow {
                    FilterChip(
                        selected = form.tierId == null,
                        onClick = { viewModel.update { it.copy(tierId = null) } },
                        label = { Text(stringResource(R.string.board_unranked)) },
                    )
                    tiers.forEach { tier ->
                        FilterChip(
                            selected = form.tierId == tier.id,
                            onClick = { viewModel.update { it.copy(tierId = tier.id) } },
                            label = { Text(tier.label) },
                        )
                    }
                }
            }

            OutlinedTextField(
                value = form.notes,
                onValueChange = { v -> viewModel.update { it.copy(notes = v) } },
                label = { Text(stringResource(R.string.field_notes)) },
                modifier = Modifier.fillMaxWidth().height(112.dp),
            )

            PurchaseEditor(
                purchases = form.purchases,
                onAdd = viewModel::addPurchase,
                onRemove = viewModel::removePurchase,
                onUpdateAt = viewModel::updatePurchase,
            )
            Spacer(Modifier.height(8.dp))
        }
    }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text(stringResource(R.string.delete_tea_confirm_title)) },
            text = {
                Text(stringResource(R.string.delete_tea_confirm_message, form.nameRu))
            },
            confirmButton = {
                TextButton(onClick = {
                    confirmDelete = false
                    viewModel.deleteTea(onDeleted = onBack)
                }) {
                    Text(stringResource(R.string.action_delete))
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmDelete = false }) {
                    Text(stringResource(R.string.action_cancel))
                }
            },
        )
    }
}

@Composable
private fun PurchaseEditor(
    purchases: List<PurchaseDraft>,
    onAdd: () -> Unit,
    onRemove: (Int) -> Unit,
    onUpdateAt: (Int, (PurchaseDraft) -> PurchaseDraft) -> Unit,
) {
    Text(
        text = stringResource(R.string.field_purchase),
        style = MaterialTheme.typography.titleMedium,
        modifier = Modifier.fillMaxWidth(),
    )
    purchases.forEachIndexed { index, draft ->
        PurchaseDraftCard(
            draft = draft,
            onRemove = { onRemove(index) },
            onUpdate = { transform -> onUpdateAt(index) { transform(it) } },
        )
    }
    OutlinedButton(onClick = onAdd, modifier = Modifier.fillMaxWidth()) {
        Icon(Icons.Filled.Add, contentDescription = null)
        Spacer(Modifier.width(8.dp))
        Text(stringResource(R.string.purchase_add))
    }
}

@Composable
private fun PurchaseDraftCard(
    draft: PurchaseDraft,
    onRemove: () -> Unit,
    onUpdate: ((PurchaseDraft) -> PurchaseDraft) -> Unit,
) {
    Surface(
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 1.dp,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.weight(1f).horizontalScroll(rememberScrollState()),
                ) {
                    PurchaseKind.entries.forEach { kind ->
                        FilterChip(
                            selected = kind == draft.kind,
                            onClick = { onUpdate { it.copy(kind = kind) } },
                            label = { Text(stringResource(kind.labelRes())) },
                        )
                    }
                }
                IconButton(onClick = onRemove) {
                    Icon(
                        imageVector = Icons.Filled.Clear,
                        contentDescription = stringResource(R.string.a11y_purchase_remove),
                    )
                }
            }
            OutlinedTextField(
                value = draft.label,
                onValueChange = { v -> onUpdate { it.copy(label = v) } },
                label = { Text(stringResource(R.string.field_purchase_label)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            when (draft.kind) {
                PurchaseKind.TEXT -> OutlinedTextField(
                    value = draft.text,
                    onValueChange = { v -> onUpdate { it.copy(text = v) } },
                    label = { Text(stringResource(R.string.field_purchase_text)) },
                    modifier = Modifier.fillMaxWidth(),
                )
                PurchaseKind.MARKETPLACE -> OutlinedTextField(
                    value = draft.url,
                    onValueChange = { v -> onUpdate { it.copy(url = v) } },
                    label = { Text(stringResource(R.string.field_purchase_url)) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
}

@Composable
private fun FieldLabel(text: String) {
    Text(text = text, style = MaterialTheme.typography.titleMedium)
}

@Composable
private fun ChipRow(content: @Composable () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        content()
    }
}

@Composable
private fun FlavorSlider(label: String, value: Int, onValue: (Int) -> Unit) {
    // Label the adjustable slider for TalkBack (the value digit alone is shown only visually).
    val description = stringResource(R.string.a11y_flavor_dim, label, value)
    Column(Modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(text = label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
            Text(text = value.toString(), style = MaterialTheme.typography.labelLarge)
        }
        androidx.compose.material3.Slider(
            value = value.toFloat(),
            onValueChange = { onValue(it.roundToInt()) },
            valueRange = 0f..5f,
            steps = 4,
            modifier = Modifier.semantics { contentDescription = description },
        )
    }
}

private fun PurchaseKind.labelRes(): Int = when (this) {
    PurchaseKind.TEXT -> R.string.purchase_kind_text
    PurchaseKind.MARKETPLACE -> R.string.purchase_kind_marketplace
}
