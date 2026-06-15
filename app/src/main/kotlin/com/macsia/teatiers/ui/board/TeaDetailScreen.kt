package com.macsia.teatiers.ui.board

import android.content.ActivityNotFoundException
import android.content.Intent
import androidx.core.net.toUri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.macsia.teatiers.R
import com.macsia.teatiers.domain.model.PurchaseLocation
import com.macsia.teatiers.domain.model.Tea
import com.macsia.teatiers.ui.components.FlavorRadar
import com.macsia.teatiers.ui.components.FlavorStrip
import com.macsia.teatiers.ui.components.LiquorSwatch
import com.macsia.teatiers.ui.components.TypeChip
import com.macsia.teatiers.ui.theme.TeaTheme
import com.macsia.teatiers.viewmodel.TeaDetailViewModel

private val ScreenInset = 16.dp

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TeaDetailScreen(
    teaId: String,
    onBack: () -> Unit,
    onEdit: (String) -> Unit,
    modifier: Modifier = Modifier,
    viewModel: TeaDetailViewModel = hiltViewModel(),
) {
    LaunchedEffect(teaId) { viewModel.bind(teaId) }
    val tea by viewModel.tea.collectAsStateWithLifecycle()
    var menuExpanded by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }

    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { Text(tea?.nameRu.orEmpty(), maxLines = 1, overflow = TextOverflow.Ellipsis) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.a11y_back),
                        )
                    }
                },
                actions = {
                    if (tea != null) {
                        IconButton(onClick = { onEdit(teaId) }) {
                            Icon(
                                imageVector = Icons.Filled.Edit,
                                contentDescription = stringResource(R.string.a11y_edit_tea),
                            )
                        }
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
    ) { innerPadding ->
        val current = tea
        if (current == null) {
            Box(Modifier.fillMaxSize().padding(innerPadding))
        } else {
            TeaDetailBody(
                tea = current,
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = ScreenInset, vertical = 8.dp),
            )
        }
    }

    if (confirmDelete) {
        val name = tea?.nameRu.orEmpty()
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text(stringResource(R.string.delete_tea_confirm_title)) },
            text = { Text(stringResource(R.string.delete_tea_confirm_message, name)) },
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
private fun TeaDetailBody(tea: Tea, modifier: Modifier = Modifier) {
    val liquor = TeaTheme.colors.liquorByType[tea.type] ?: MaterialTheme.colorScheme.secondary

    Column(modifier, verticalArrangement = Arrangement.spacedBy(20.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            LiquorSwatch(type = tea.type, size = 64.dp)
            Spacer(Modifier.width(16.dp))
            Column(Modifier.weight(1f)) {
                Text(text = tea.nameRu, style = MaterialTheme.typography.headlineMedium)
                if (tea.secondaryName.isNotEmpty()) {
                    Spacer(Modifier.height(2.dp))
                    Text(
                        text = tea.secondaryName,
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                tea.nameEn?.let {
                    Text(
                        text = it,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }

        Row(verticalAlignment = Alignment.CenterVertically) {
            TypeChip(type = tea.type)
            tea.origin?.let {
                Spacer(Modifier.width(12.dp))
                Text(
                    text = it,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        tea.shortBlurb?.let {
            Text(text = it, style = MaterialTheme.typography.bodyLarge)
        }

        if (tea.photos.isNotEmpty()) {
            Section(title = stringResource(R.string.detail_photos_title)) {
                PhotoGallery(photos = tea.photos, modifier = Modifier.fillMaxWidth())
            }
        }

        if (tea.flavor.isNotEmpty()) {
            Section(title = stringResource(R.string.detail_flavor_title)) {
                if (tea.flavor.size >= 3) {
                    FlavorRadar(
                        flavors = tea.flavor,
                        accent = liquor,
                        modifier = Modifier.fillMaxWidth().height(260.dp),
                    )
                    Spacer(Modifier.height(12.dp))
                }
                FlavorStrip(
                    flavors = tea.flavor,
                    accent = liquor,
                    max = tea.flavor.size,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }

        tea.notes?.let {
            Section(title = stringResource(R.string.detail_notes_title)) {
                Text(text = it, style = MaterialTheme.typography.bodyMedium)
            }
        }

        Section(title = stringResource(R.string.detail_purchase_title)) {
            if (tea.purchaseLocations.isEmpty()) {
                Text(
                    text = stringResource(R.string.detail_purchase_empty),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    tea.purchaseLocations.forEach { PurchaseRow(it) }
                }
            }
        }
    }
}

@Composable
private fun Section(title: String, content: @Composable () -> Unit) {
    Column(Modifier.fillMaxWidth()) {
        Text(text = title, style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(10.dp))
        content()
    }
}

@Composable
private fun PurchaseRow(location: PurchaseLocation) {
    val context = LocalContext.current
    val (primary, secondary, uri) = when (location) {
        is PurchaseLocation.Marketplace -> Triple(
            location.label ?: stringResource(R.string.purchase_kind_marketplace),
            location.url,
            location.url,
        )
        is PurchaseLocation.FreeText -> Triple(location.label ?: location.text, location.text.takeIf { location.label != null }, null)
    }

    Surface(
        onClick = {
            if (uri != null) {
                runCatching {
                    context.startActivity(Intent(Intent.ACTION_VIEW, uri.toUri()))
                }.onFailure { if (it !is ActivityNotFoundException) throw it }
            }
        },
        enabled = uri != null,
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 1.dp,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(14.dp)) {
            Text(text = primary, style = MaterialTheme.typography.titleSmall)
            secondary?.let {
                Spacer(Modifier.height(2.dp))
                Text(
                    text = it,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}
