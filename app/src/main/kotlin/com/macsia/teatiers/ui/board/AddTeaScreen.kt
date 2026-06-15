package com.macsia.teatiers.ui.board

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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.macsia.teatiers.R
import com.macsia.teatiers.domain.model.TeaType
import com.macsia.teatiers.viewmodel.AddTeaForm
import com.macsia.teatiers.viewmodel.AddTeaViewModel
import com.macsia.teatiers.viewmodel.PurchaseKind
import com.macsia.teatiers.viewmodel.QuickRateDimensions
import com.macsia.teatiers.domain.model.GeoProvider
import kotlin.math.roundToInt

private val ScreenInset = 16.dp

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddTeaScreen(
    boardId: String,
    onBack: () -> Unit,
    onSaved: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: AddTeaViewModel = hiltViewModel(),
) {
    val form by viewModel.form.collectAsStateWithLifecycle()
    val tiers = remember(boardId) { viewModel.tiersOf(boardId) }

    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.add_tea_title)) },
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
                        onClick = { if (viewModel.submit(boardId)) onSaved() },
                        enabled = form.isValid,
                    ) {
                        Text(stringResource(R.string.action_save))
                    }
                },
            )
        },
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
            OutlinedTextField(
                value = form.nameRu,
                onValueChange = { v -> viewModel.update { it.copy(nameRu = v) } },
                label = { Text(stringResource(R.string.field_name_ru)) },
                singleLine = true,
                isError = form.nameRu.isBlank(),
                supportingText = { Text(stringResource(R.string.field_name_ru_hint)) },
                modifier = Modifier.fillMaxWidth(),
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

            FieldLabel(stringResource(R.string.field_flavor))
            QuickRateDimensions.forEach { dimension ->
                FlavorSlider(
                    label = stringResource(dimension.labelRes),
                    value = form.flavors[dimension] ?: 0,
                    onValue = { viewModel.setFlavor(dimension, it) },
                )
            }

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

            OutlinedTextField(
                value = form.notes,
                onValueChange = { v -> viewModel.update { it.copy(notes = v) } },
                label = { Text(stringResource(R.string.field_notes)) },
                modifier = Modifier.fillMaxWidth().height(112.dp),
            )

            PurchaseEditor(form = form, onUpdate = viewModel::update)
            Spacer(Modifier.height(8.dp))
        }
    }
}

@Composable
private fun PurchaseEditor(form: AddTeaForm, onUpdate: ((AddTeaForm) -> AddTeaForm) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        Text(
            text = stringResource(R.string.field_purchase),
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.weight(1f),
        )
        Switch(
            checked = form.includePurchase,
            onCheckedChange = { on -> onUpdate { it.copy(includePurchase = on) } },
        )
    }
    if (!form.includePurchase) return

    val draft = form.purchase
    ChipRow {
        PurchaseKind.entries.forEach { kind ->
            FilterChip(
                selected = kind == draft.kind,
                onClick = { onUpdate { it.copy(purchase = it.purchase.copy(kind = kind)) } },
                label = { Text(stringResource(kind.labelRes())) },
            )
        }
    }
    OutlinedTextField(
        value = draft.label,
        onValueChange = { v -> onUpdate { it.copy(purchase = it.purchase.copy(label = v)) } },
        label = { Text(stringResource(R.string.field_purchase_label)) },
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
    )
    when (draft.kind) {
        PurchaseKind.TEXT -> OutlinedTextField(
            value = draft.text,
            onValueChange = { v -> onUpdate { it.copy(purchase = it.purchase.copy(text = v)) } },
            label = { Text(stringResource(R.string.field_purchase_text)) },
            modifier = Modifier.fillMaxWidth(),
        )
        PurchaseKind.MARKETPLACE -> OutlinedTextField(
            value = draft.url,
            onValueChange = { v -> onUpdate { it.copy(purchase = it.purchase.copy(url = v)) } },
            label = { Text(stringResource(R.string.field_purchase_url)) },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
            modifier = Modifier.fillMaxWidth(),
        )
        PurchaseKind.GEO -> {
            ChipRow {
                GeoProvider.entries.forEach { provider ->
                    FilterChip(
                        selected = provider == draft.provider,
                        onClick = { onUpdate { it.copy(purchase = it.purchase.copy(provider = provider)) } },
                        label = { Text(stringResource(provider.labelRes)) },
                    )
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value = draft.latitude,
                    onValueChange = { v -> onUpdate { it.copy(purchase = it.purchase.copy(latitude = v)) } },
                    label = { Text(stringResource(R.string.field_latitude)) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    modifier = Modifier.weight(1f),
                )
                OutlinedTextField(
                    value = draft.longitude,
                    onValueChange = { v -> onUpdate { it.copy(purchase = it.purchase.copy(longitude = v)) } },
                    label = { Text(stringResource(R.string.field_longitude)) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    modifier = Modifier.weight(1f),
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
        )
    }
}

private fun PurchaseKind.labelRes(): Int = when (this) {
    PurchaseKind.TEXT -> R.string.purchase_kind_text
    PurchaseKind.MARKETPLACE -> R.string.purchase_kind_marketplace
    PurchaseKind.GEO -> R.string.purchase_kind_geo
}
