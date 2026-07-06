package com.macsia.teatiers.ui.board

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.foundation.clickable
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import coil3.compose.SubcomposeAsyncImage
import coil3.request.ImageRequest
import coil3.request.crossfade
import com.macsia.teatiers.R
import com.macsia.teatiers.domain.model.CatalogLocale
import com.macsia.teatiers.domain.model.CatalogTeaDetail
import com.macsia.teatiers.ui.components.FlavorRadar
import com.macsia.teatiers.ui.components.FlavorStrip
import com.macsia.teatiers.ui.components.TypeChip
import com.macsia.teatiers.ui.theme.TeaTheme
import com.macsia.teatiers.viewmodel.CatalogDetailUiState

/**
 * Catalog detail surfaced as a bottom sheet over the add form (M3). A sheet (not a back-stack
 * destination) keeps [AddTeaScreen] mounted, so opening detail never re-binds the form or drops the
 * in-progress catalog search, and "use this tea" can prefill via the same view model.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CatalogDetailSheet(
    state: CatalogDetailUiState,
    onDismiss: () -> Unit,
    onUse: (CatalogTeaDetail) -> Unit,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
) {
    if (state is CatalogDetailUiState.Hidden) return
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState, modifier = modifier) {
        when (state) {
            CatalogDetailUiState.Hidden -> Unit
            CatalogDetailUiState.Loading -> DetailStatus {
                CircularProgressIndicator()
            }
            CatalogDetailUiState.Offline -> DetailMessage(
                text = stringResource(R.string.catalog_detail_offline),
                onRetry = onRetry,
            )
            CatalogDetailUiState.RateLimited -> DetailMessage(
                text = stringResource(R.string.catalog_detail_rate_limited),
                onRetry = onRetry,
            )
            CatalogDetailUiState.Error -> DetailMessage(
                text = stringResource(R.string.catalog_detail_error),
                onRetry = onRetry,
            )
            CatalogDetailUiState.Withdrawn -> DetailMessage(
                text = stringResource(R.string.catalog_detail_withdrawn),
            )
            is CatalogDetailUiState.Loaded -> CatalogDetailContent(
                detail = state.detail,
                onUse = { onUse(state.detail) },
            )
        }
    }
}

private val SheetInset = 24.dp

@Composable
private fun DetailStatus(content: @Composable () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 200.dp)
            .padding(SheetInset),
        contentAlignment = Alignment.Center,
    ) {
        content()
    }
}

@Composable
private fun DetailMessage(text: String, onRetry: (() -> Unit)? = null) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 180.dp)
            .padding(SheetInset)
            .navigationBarsPadding(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        // A withdrawn entry is not retryable, so the retry affordance is omitted there.
        onRetry?.let {
            Spacer(Modifier.height(12.dp))
            TextButton(onClick = it) {
                Text(stringResource(R.string.action_retry))
            }
        }
    }
}

@Composable
private fun CatalogDetailContent(detail: CatalogTeaDetail, onUse: () -> Unit) {
    val liquor = TeaTheme.colors.liquorByType[detail.type] ?: MaterialTheme.colorScheme.secondary
    // Catalog descriptions are keyed by locale (ru/en/...); prefer the device language, then fall back.
    val deviceLanguage = LocalConfiguration.current.locales[0].language
    val description = detail.descriptionFor(CatalogLocale.forDeviceLanguage(deviceLanguage))

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = SheetInset)
            .padding(bottom = SheetInset)
            .navigationBarsPadding(),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        detail.images.forEach { image ->
            SubcomposeAsyncImage(
                model = ImageRequest.Builder(LocalContext.current)
                    .data(image.url)
                    .crossfade(true)
                    .build(),
                contentDescription = stringResource(R.string.a11y_catalog_image),
                contentScale = ContentScale.Crop,
                // Catalog images are remote (Wikidata/server) — a dead URL or offline shows a
                // broken-image glyph instead of a blank tinted banner (audit #6).
                error = { PhotoLoadError(Modifier.fillMaxSize()) },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(200.dp)
                    .clip(MaterialTheme.shapes.large)
                    .background(liquor.copy(alpha = 0.12f)),
            )
        }

        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(text = detail.displayName, style = MaterialTheme.typography.headlineSmall)
            if (detail.secondaryName.isNotBlank()) {
                Text(
                    text = detail.secondaryName,
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            detail.nameEn?.takeIf { it != detail.displayName }?.let {
                Text(
                    text = it,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        Row(verticalAlignment = Alignment.CenterVertically) {
            TypeChip(type = detail.type)
            detail.origin?.let {
                Spacer(Modifier.width(12.dp))
                Text(
                    text = it,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        if (detail.isUnverified) {
            Text(
                text = stringResource(R.string.catalog_result_unverified),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        if (description != null) {
            DescriptionBlock(description = description)
        }

        if (detail.flavors.isNotEmpty()) {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    text = stringResource(R.string.detail_flavor_title),
                    style = MaterialTheme.typography.titleMedium,
                )
                if (detail.flavors.size >= 3) {
                    FlavorRadar(
                        flavors = detail.flavors,
                        accent = liquor,
                        modifier = Modifier.fillMaxWidth().height(240.dp),
                    )
                }
                FlavorStrip(
                    flavors = detail.flavors,
                    accent = liquor,
                    max = detail.flavors.size,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }

        ProvenanceBlock(detail = detail)

        Button(onClick = onUse, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.catalog_detail_use))
        }
    }
}

@Composable
private fun DescriptionBlock(
    description: com.macsia.teatiers.domain.model.CatalogDescription,
) {
    var expanded by remember(description) { mutableStateOf(false) }
    val full = description.full
    val hasFull = !full.isNullOrBlank() && full != description.short
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        val body = if (expanded && hasFull) full.orEmpty() else description.short.orEmpty()
        if (body.isNotBlank()) {
            Text(text = body, style = MaterialTheme.typography.bodyLarge)
        }
        if (hasFull) {
            TextButton(
                onClick = { expanded = !expanded },
                contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp),
            ) {
                Text(
                    stringResource(
                        if (expanded) R.string.catalog_detail_read_less
                        else R.string.catalog_detail_read_full,
                    ),
                )
            }
        }
        Attribution(source = description.source, sourceUrl = null, license = description.license)
    }
}

@Composable
private fun ProvenanceBlock(detail: CatalogTeaDetail) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Attribution(
            source = detail.provenance.source,
            sourceUrl = detail.provenance.sourceUrl,
            license = detail.provenance.license,
        )
        detail.images.forEach { image ->
            val credit = image.sourceUrl ?: image.license
            if (credit != null) {
                LinkedText(
                    text = stringResource(R.string.catalog_detail_image_credit, image.license ?: credit),
                    url = image.sourceUrl,
                )
            }
        }
    }
}

/** Source + license footer; the source becomes a tappable link when [sourceUrl] is present. */
@Composable
private fun Attribution(source: String?, sourceUrl: String?, license: String?) {
    if (!source.isNullOrBlank()) {
        LinkedText(text = stringResource(R.string.catalog_detail_source, source), url = sourceUrl)
    }
    if (!license.isNullOrBlank()) {
        Text(
            text = stringResource(R.string.catalog_detail_license, license),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun LinkedText(text: String, url: String?) {
    val context = LocalContext.current
    if (url != null) {
        // Underline + ↗ so it reads as a link that leaves the app, not just tinted text (audit).
        Text(
            text = "$text  ↗",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.primary,
            textDecoration = TextDecoration.Underline,
            modifier = Modifier.clickable(role = Role.Button) { context.openExternalUrl(url) },
        )
    } else {
        Text(
            text = text,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
