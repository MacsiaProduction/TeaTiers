package com.macsia.teatiers.ui.board

import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.clickable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.macsia.teatiers.R
import com.macsia.teatiers.domain.model.CatalogTea
import com.macsia.teatiers.domain.model.TeaType
import com.macsia.teatiers.ui.components.TypeChip
import com.macsia.teatiers.viewmodel.AddTeaViewModel
import com.macsia.teatiers.viewmodel.CatalogSearchUiState
import com.macsia.teatiers.viewmodel.CollectUiEvents
import com.macsia.teatiers.viewmodel.ExtendedRateDimensions
import com.macsia.teatiers.viewmodel.PurchaseDraft
import com.macsia.teatiers.viewmodel.PurchaseKind
import com.macsia.teatiers.viewmodel.QuickRateDimensions
import com.macsia.teatiers.viewmodel.ScanUiState
import com.macsia.teatiers.viewmodel.SourceTextMaxLength
import com.macsia.teatiers.viewmodel.visibleExtendedDimensions
import java.io.File
import kotlin.math.roundToInt
import kotlinx.coroutines.launch

private val ScreenInset = 16.dp

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddTeaScreen(
    boardId: String?,
    onBack: () -> Unit,
    onSaved: () -> Unit,
    modifier: Modifier = Modifier,
    teaId: String? = null,
    catalogTeaId: Long? = null,
    forceNew: Boolean = false,
    viewModel: AddTeaViewModel = hiltViewModel(),
) {
    // Stable per-entry token: survives rotation/process-death via rememberSaveable, so a re-fired
    // bind() on recreation preserves the in-progress form instead of wiping it (review N5).
    val entryToken = rememberSaveable { java.util.UUID.randomUUID().toString() }
    LaunchedEffect(boardId, teaId, catalogTeaId, forceNew, entryToken) {
        viewModel.bind(boardId = boardId, teaId = teaId, catalogTeaId = catalogTeaId, forceNew = forceNew, entryToken = entryToken)
    }
    val form by viewModel.form.collectAsStateWithLifecycle()
    val tiers by viewModel.tiers.collectAsStateWithLifecycle()
    val placementCount by viewModel.placementCount.collectAsStateWithLifecycle()
    val photos by viewModel.photos.collectAsStateWithLifecycle()
    val catalogQuery by viewModel.catalogQuery.collectAsStateWithLifecycle()
    val catalogSearch by viewModel.catalogSearch.collectAsStateWithLifecycle()
    val catalogDetail by viewModel.catalogDetail.collectAsStateWithLifecycle()
    val isEdit = teaId != null
    var menuExpanded by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    var confirmDiscard by remember { mutableStateOf(false) }
    // Guard the back paths (system back + nav arrow): warn before dropping an in-progress form.
    val attemptBack = { if (viewModel.isDirty()) confirmDiscard = true else onBack() }
    BackHandler(enabled = true) { attemptBack() }
    var flavorsExpanded by remember { mutableStateOf(false) }
    var sampleExpanded by rememberSaveable { mutableStateOf(false) }
    val snackbarHostState = remember { SnackbarHostState() }
    val snackbarScope = rememberCoroutineScope()
    // Resolved here (composable scope) so it can be shown from the non-composable submit callback.
    val photoCopyFailedMessage = stringResource(R.string.error_photo_copy_failed)
    CollectUiEvents(viewModel.events, snackbarHostState)
    // Focus-on-error: the Save button invokes `submit`, which synchronously arms the
    // pendingNameFocus flag when the form is invalid. We then pop the flag and route focus
    // to the nameRu field so the user knows where to look. Snackbar covers the "why".
    val nameRuFocusRequester = remember { FocusRequester() }

    // Opt-in packaging scan (slice 3). Gallery uses the permission-free PickVisualMedia; camera uses
    // TakePicture into a FileProvider URI (also permission-free — the camera app owns the capture).
    val context = LocalContext.current
    val scanState by viewModel.scan.collectAsStateWithLifecycle()
    var showScanChooser by remember { mutableStateOf(false) }
    var pendingCameraUri by remember { mutableStateOf<Uri?>(null) }
    val galleryLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.PickVisualMedia(),
    ) { uri -> uri?.let(viewModel::scanLabel) }
    val cameraLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.TakePicture(),
    ) { success -> if (success) pendingCameraUri?.let(viewModel::scanLabel) }

    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = {
                    Text(stringResource(if (isEdit) R.string.edit_tea_title else R.string.add_tea_title))
                },
                navigationIcon = {
                    IconButton(onClick = attemptBack) {
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
                            viewModel.submit { failedPhotoCount ->
                                if (failedPhotoCount > 0) {
                                    // Show the photo-copy failure on this still-mounted screen's host,
                                    // then navigate — popping first would tear out the collector before
                                    // the message ever rendered, losing it silently.
                                    snackbarScope.launch {
                                        snackbarHostState.showSnackbar(photoCopyFailedMessage)
                                        onSaved()
                                    }
                                } else {
                                    onSaved()
                                }
                            }
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
            // Catalog search (M3): add mode only. Editing an existing tea is local-only, so the
            // catalog box would just be noise there. Picking a result prefills the fields below.
            if (!isEdit) {
                CatalogSearchField(
                    query = catalogQuery,
                    state = catalogSearch,
                    onQuery = viewModel::onCatalogQuery,
                    onClear = viewModel::clearCatalogSearch,
                    onPick = viewModel::pickCatalogTea,
                    onInfo = { tea -> viewModel.openCatalogDetail(tea.id) },
                    onAddManually = {
                        viewModel.addManuallyFromQuery()
                        if (viewModel.consumeNameRequiredFocus()) {
                            nameRuFocusRequester.requestFocus()
                        }
                    },
                )
            }
            // The requirement is "≥1 name in ANY locale" (#132), so the label + hint live at the name
            // GROUP level, not pinned to the ru field (which made an empty form look like ru alone was
            // required — audit #7). No per-field error outline; the group hint below carries it.
            FieldLabel(stringResource(R.string.field_names_section))
            OutlinedTextField(
                value = form.nameRu,
                onValueChange = { v -> viewModel.update { it.copy(nameRu = v) } },
                label = { Text(stringResource(R.string.field_name_ru)) },
                singleLine = true,
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
            // One group-level requirement line for all four name fields; red until satisfied (audit #7).
            Text(
                text = stringResource(R.string.field_name_required_hint),
                style = MaterialTheme.typography.bodySmall,
                color = if (form.isValid) {
                    MaterialTheme.colorScheme.onSurfaceVariant
                } else {
                    MaterialTheme.colorScheme.error
                },
            )

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

            // P1-1 sample identity (#132): distinguishes two samples of the same catalog tea. Power-user
            // detail — collapsed by default to keep the casual add path short; auto-expanded when it
            // already carries data (e.g. editing, audit #17).
            val hasSampleData = listOf(form.vendor, form.product, form.harvestYear, form.batch, form.grade)
                .any { it.isNotBlank() }
            // Auto-expand once when the section gains data (e.g. an edit prefill), but then let the
            // chevron collapse it. Previously `|| hasSampleData` pinned it open, so the collapse
            // chevron silently did nothing whenever a field was filled (audit P2).
            LaunchedEffect(hasSampleData) { if (hasSampleData) sampleExpanded = true }
            val sampleVisible = sampleExpanded
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { sampleExpanded = !sampleExpanded },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                FieldLabel(stringResource(R.string.field_sample_section))
                Spacer(Modifier.weight(1f))
                Icon(
                    imageVector = if (sampleVisible) Icons.Filled.KeyboardArrowUp else Icons.Filled.KeyboardArrowDown,
                    contentDescription = null,
                )
            }
            if (sampleVisible) {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    OutlinedTextField(
                        value = form.vendor,
                        onValueChange = { v -> viewModel.update { it.copy(vendor = v) } },
                        label = { Text(stringResource(R.string.field_vendor)) },
                        singleLine = true,
                        modifier = Modifier.weight(1f),
                    )
                    OutlinedTextField(
                        value = form.product,
                        onValueChange = { v -> viewModel.update { it.copy(product = v) } },
                        label = { Text(stringResource(R.string.field_product)) },
                        singleLine = true,
                        modifier = Modifier.weight(1f),
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    OutlinedTextField(
                        value = form.harvestYear,
                        onValueChange = { v -> viewModel.update { it.copy(harvestYear = v.filter(Char::isDigit).take(4)) } },
                        label = { Text(stringResource(R.string.field_harvest_year)) },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.weight(1f),
                    )
                    OutlinedTextField(
                        value = form.batch,
                        onValueChange = { v -> viewModel.update { it.copy(batch = v) } },
                        label = { Text(stringResource(R.string.field_batch)) },
                        singleLine = true,
                        modifier = Modifier.weight(1f),
                    )
                    OutlinedTextField(
                        value = form.grade,
                        onValueChange = { v -> viewModel.update { it.copy(grade = v) } },
                        label = { Text(stringResource(R.string.field_grade)) },
                        singleLine = true,
                        modifier = Modifier.weight(1f),
                    )
                }
            }

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

            // Optional grounding blurb (#25), add mode only: a pasted vendor/packaging description
            // sent once with background enrichment to sharpen the flavor profile. Hard-capped to the
            // server limit; the hint states it is sent for enrichment and not stored in the catalog.
            if (!isEdit) {
                val recognizing = scanState is ScanUiState.Recognizing
                OutlinedButton(
                    onClick = { showScanChooser = true },
                    enabled = !recognizing,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    if (recognizing) {
                        CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.width(8.dp))
                        Text(stringResource(R.string.ocr_recognizing))
                    } else {
                        Text(stringResource(R.string.ocr_scan_label))
                    }
                }
                OutlinedTextField(
                    value = form.sourceText,
                    onValueChange = { v ->
                        if (v.length <= SourceTextMaxLength) viewModel.update { it.copy(sourceText = v) }
                    },
                    label = { Text(stringResource(R.string.field_source_text)) },
                    supportingText = {
                        Row(Modifier.fillMaxWidth()) {
                            Text(
                                text = stringResource(R.string.field_source_text_hint),
                                modifier = Modifier.weight(1f).padding(end = 8.dp),
                            )
                            Text(text = "${form.sourceText.length}/$SourceTextMaxLength")
                        }
                    },
                    minLines = 3,
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            PurchaseEditor(
                purchases = form.purchases,
                onAdd = viewModel::addPurchase,
                onRemove = viewModel::removePurchase,
                onUpdateAt = viewModel::updatePurchase,
            )
            Spacer(Modifier.height(8.dp))
        }
    }

    if (confirmDiscard) {
        AlertDialog(
            onDismissRequest = { confirmDiscard = false },
            title = { Text(stringResource(R.string.discard_changes_title)) },
            text = { Text(stringResource(R.string.discard_changes_message)) },
            confirmButton = {
                TextButton(onClick = {
                    confirmDiscard = false
                    onBack()
                }) { Text(stringResource(R.string.discard_changes_confirm)) }
            },
            dismissButton = {
                TextButton(onClick = { confirmDiscard = false }) {
                    Text(stringResource(R.string.discard_changes_keep))
                }
            },
        )
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

    if (showScanChooser) {
        AlertDialog(
            onDismissRequest = { showScanChooser = false },
            title = { Text(stringResource(R.string.ocr_scan_source_title)) },
            confirmButton = {
                TextButton(onClick = {
                    showScanChooser = false
                    val uri = scanCaptureUri(context)
                    pendingCameraUri = uri
                    cameraLauncher.launch(uri)
                }) { Text(stringResource(R.string.ocr_scan_source_camera)) }
            },
            dismissButton = {
                TextButton(onClick = {
                    showScanChooser = false
                    galleryLauncher.launch(
                        PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly),
                    )
                }) { Text(stringResource(R.string.ocr_scan_source_gallery)) }
            },
        )
    }

    (scanState as? ScanUiState.Review)?.let { review ->
        var edited by remember(review.text) { mutableStateOf(review.text) }
        AlertDialog(
            onDismissRequest = viewModel::cancelScan,
            title = { Text(stringResource(R.string.ocr_review_title)) },
            text = {
                Column {
                    Text(
                        text = stringResource(R.string.ocr_review_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = edited,
                        onValueChange = { edited = it.take(SourceTextMaxLength) },
                        minLines = 4,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = { viewModel.applyScannedText(edited) }) {
                    Text(stringResource(R.string.ocr_use))
                }
            },
            dismissButton = {
                TextButton(onClick = viewModel::cancelScan) {
                    Text(stringResource(R.string.action_cancel))
                }
            },
        )
    }

    CatalogDetailSheet(
        state = catalogDetail,
        onDismiss = viewModel::closeCatalogDetail,
        onUse = viewModel::useCatalogDetail,
        onRetry = viewModel::retryCatalogDetail,
    )
}

/**
 * A FileProvider URI for a transient camera-capture target under `cacheDir/scans/` (exposed in
 * `file_paths.xml`). Old scans are cleared first so full-res captures don't accumulate in the cache.
 */
private fun scanCaptureUri(context: android.content.Context): Uri {
    val dir = File(context.cacheDir, "scans").apply {
        mkdirs()
        listFiles()?.forEach { it.delete() }
    }
    val file = File(dir, "scan-${System.currentTimeMillis()}.jpg")
    return FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
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
private fun CatalogSearchField(
    query: String,
    state: CatalogSearchUiState,
    onQuery: (String) -> Unit,
    onClear: () -> Unit,
    onPick: (CatalogTea) -> Unit,
    onInfo: (CatalogTea) -> Unit,
    onAddManually: () -> Unit,
) {
    val focusManager = LocalFocusManager.current
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedTextField(
            value = query,
            onValueChange = onQuery,
            label = { Text(stringResource(R.string.catalog_search_label)) },
            singleLine = true,
            // Search is live; the IME action just collapses the keyboard (audit).
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = { focusManager.clearFocus() }),
            leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
            trailingIcon = {
                if (query.isNotEmpty()) {
                    IconButton(onClick = onClear) {
                        Icon(
                            imageVector = Icons.Filled.Clear,
                            contentDescription = stringResource(R.string.a11y_catalog_clear),
                        )
                    }
                }
            },
            supportingText = { Text(stringResource(R.string.catalog_search_hint)) },
            modifier = Modifier.fillMaxWidth(),
        )
        when (state) {
            CatalogSearchUiState.Idle -> Unit
            CatalogSearchUiState.Loading ->
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(vertical = 4.dp),
                ) {
                    CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                }
            is CatalogSearchUiState.Results -> {
                if (state.fromCache) {
                    CatalogHint(stringResource(R.string.catalog_search_from_cache))
                }
                state.teas.forEach { tea ->
                    CatalogResultRow(
                        tea = tea,
                        onPick = { onPick(tea) },
                        onInfo = { onInfo(tea) },
                    )
                }
            }
            CatalogSearchUiState.Empty ->
                CatalogMiss(
                    message = stringResource(R.string.catalog_search_empty),
                    query = query,
                    onAddManually = onAddManually,
                )
            CatalogSearchUiState.Offline ->
                CatalogMiss(
                    message = stringResource(R.string.catalog_search_offline),
                    query = query,
                    onAddManually = onAddManually,
                )
            CatalogSearchUiState.Error ->
                CatalogMiss(
                    message = stringResource(R.string.catalog_search_error),
                    query = query,
                    onAddManually = onAddManually,
                )
        }
    }
}

@Composable
private fun CatalogHint(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

/**
 * Terminal non-result state (no match / offline / error): show the reason, then a CTA that
 * carries the typed query into the name field so the user can add the tea by hand without
 * retyping. The "paste a description" path is M4 enrichment and lives elsewhere.
 */
@Composable
private fun CatalogMiss(message: String, query: String, onAddManually: () -> Unit) {
    val trimmed = query.trim()
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        CatalogHint(message)
        OutlinedButton(onClick = onAddManually, modifier = Modifier.fillMaxWidth()) {
            Icon(
                imageVector = Icons.Filled.Add,
                contentDescription = null,
                modifier = Modifier.size(18.dp),
            )
            Spacer(Modifier.width(8.dp))
            Text(
                text = if (trimmed.isEmpty()) {
                    stringResource(R.string.catalog_add_manually_generic)
                } else {
                    stringResource(R.string.catalog_add_manually, trimmed)
                },
            )
        }
    }
}

@Composable
private fun CatalogResultRow(tea: CatalogTea, onPick: () -> Unit, onInfo: () -> Unit) {
    val pickDescription = stringResource(R.string.a11y_catalog_pick, tea.displayName)
    val infoDescription = stringResource(R.string.a11y_catalog_info, tea.displayName)
    Surface(
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 1.dp,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.padding(start = 12.dp, top = 4.dp, bottom = 4.dp, end = 4.dp),
        ) {
            // Tapping the body prefills the form; the info button (a separate a11y node) opens detail.
            Column(
                modifier = Modifier
                    .weight(1f)
                    .clickable(onClick = onPick)
                    .semantics(mergeDescendants = true) { contentDescription = pickDescription }
                    .padding(vertical = 8.dp),
            ) {
                Text(text = tea.displayName, style = MaterialTheme.typography.titleSmall)
                if (tea.secondaryName.isNotBlank()) {
                    Text(
                        text = tea.secondaryName,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
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
                Icon(
                    imageVector = Icons.Filled.Info,
                    contentDescription = infoDescription,
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
