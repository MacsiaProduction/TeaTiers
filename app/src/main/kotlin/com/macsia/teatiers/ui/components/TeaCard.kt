package com.macsia.teatiers.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Icon
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
import coil3.compose.SubcomposeAsyncImage
import coil3.request.ImageRequest
import coil3.request.crossfade
import com.macsia.teatiers.R
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
        SubcomposeAsyncImage(
            model = request,
            contentDescription = null,
            // UX2-P2-22: a missing/revoked photo file used to render a silent blank square here,
            // unlike PhotoStrip's identical PhotoImage, which already shows this same glyph.
            error = { PhotoLoadError(Modifier.fillMaxSize()) },
            modifier = Modifier.fillMaxSize(),
        )
    }
}

/**
 * Fallback for a photo that can't be loaded (file gone, gallery permission revoked, an offline
 * catalog image). The glyph scales with its container — capped — so it reads on a small badge and
 * a full-screen view alike. Shared by [PhotoBadge] here and `PhotoImage` in `ui/board/PhotoStrip.kt`.
 */
@Composable
fun PhotoLoadError(modifier: Modifier = Modifier) {
    BoxWithConstraints(modifier, contentAlignment = Alignment.Center) {
        val iconSize = (minOf(maxWidth, maxHeight) * 0.4f).coerceIn(14.dp, 40.dp)
        Icon(
            imageVector = Icons.Filled.Warning,
            contentDescription = stringResource(R.string.a11y_photo_failed),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(iconSize),
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
            EnrichmentStatus(state = tea.enrichmentState, modifier = Modifier.padding(top = 8.dp))
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

