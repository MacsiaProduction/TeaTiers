package com.macsia.teatiers.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import coil3.request.crossfade
import com.macsia.teatiers.R
import com.macsia.teatiers.domain.model.EnrichmentState
import com.macsia.teatiers.domain.model.Tea
import com.macsia.teatiers.ui.theme.TeaTheme

private val CardWidth = 172.dp
private val PhotoBadgeSize = 28.dp

@Composable
private fun PhotoBadge(uri: String) {
    val context = LocalContext.current
    val request = remember(uri) {
        ImageRequest.Builder(context).data(uri).crossfade(true).build()
    }
    Box(
        modifier = Modifier
            .size(PhotoBadgeSize)
            .clip(MaterialTheme.shapes.small)
            .background(MaterialTheme.colorScheme.surfaceVariant),
    ) {
        AsyncImage(
            model = request,
            contentDescription = null,
            modifier = Modifier.fillMaxSize(),
        )
    }
}

/** A tea as placed on a board: liquor swatch, multilingual names, type, and a compact flavor strip. */
@Composable
fun TeaCard(tea: Tea, modifier: Modifier = Modifier, onClick: (() -> Unit)? = null) {
    val liquor = TeaTheme.colors.liquorByType[tea.type] ?: MaterialTheme.colorScheme.secondary
    val secondaryName = tea.secondaryName

    Surface(
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 1.dp,
        modifier = modifier
            .width(CardWidth)
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier),
    ) {
        Column(Modifier.padding(14.dp)) {
            val firstPhotoUri = tea.photos.firstOrNull()?.uri
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (firstPhotoUri != null) {
                    PhotoBadge(uri = firstPhotoUri)
                } else {
                    LiquorSwatch(type = tea.type, size = 18.dp)
                }
                Spacer(Modifier.width(10.dp))
                Text(
                    text = tea.displayName,
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
            }
            if (secondaryName.isNotEmpty()) {
                Spacer(Modifier.height(2.dp))
                Text(
                    text = secondaryName,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            // P1-1 disambiguator: two samples of one catalog tea otherwise render identically (#132).
            if (tea.sampleIdentity.isNotEmpty()) {
                Spacer(Modifier.height(2.dp))
                Text(
                    text = tea.sampleIdentity,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Spacer(Modifier.height(10.dp))
            TypeChip(type = tea.type)
            EnrichmentStatus(state = tea.enrichmentState)
            if (tea.flavor.isNotEmpty()) {
                Spacer(Modifier.height(12.dp))
                FlavorStrip(
                    flavors = tea.flavor,
                    accent = liquor,
                    max = 3,
                    labelWidth = 80.dp,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
}

/**
 * Optimistic-enrichment hint under the type chip (#21/#28): a tiny spinner while we resolve the
 * catalog, a muted "queued" line offline, and an error-toned "failed" line (the board card's
 * overflow offers the retry). Renders nothing once DONE or for a plain custom tea (NONE).
 */
@Composable
private fun EnrichmentStatus(state: EnrichmentState) {
    val label = when (state) {
        EnrichmentState.PENDING -> stringResource(R.string.enrichment_status_pending)
        EnrichmentState.QUEUED -> stringResource(R.string.enrichment_status_queued)
        EnrichmentState.FAILED -> stringResource(R.string.enrichment_status_failed)
        EnrichmentState.NONE, EnrichmentState.DONE -> return
    }
    val color = if (state == EnrichmentState.FAILED) {
        MaterialTheme.colorScheme.error
    } else {
        MaterialTheme.colorScheme.onSurfaceVariant
    }
    Spacer(Modifier.height(8.dp))
    Row(verticalAlignment = Alignment.CenterVertically) {
        if (state == EnrichmentState.PENDING) {
            CircularProgressIndicator(
                strokeWidth = 1.5.dp,
                color = color,
                modifier = Modifier.size(12.dp),
            )
            Spacer(Modifier.width(6.dp))
        }
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = color,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}
