package com.macsia.teatiers.ui.components

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.macsia.teatiers.R
import com.macsia.teatiers.domain.model.EnrichmentState
import com.macsia.teatiers.domain.model.isRetryable

/**
 * Shared optimistic-enrichment hint (#21/#28), used wherever a tea is shown — the board card, the
 * detail screen, and the My Teas list (UX3-P1-4) — so an in-flight/failed/queued resolve is never
 * invisible on any surface. A tiny spinner while resolving, a muted "queued" line offline, an
 * error-toned "failed" line. Renders nothing once DONE or for a plain custom tea (NONE), so the
 * caller's [modifier] (e.g. leading padding) only takes effect when there is actually a hint to show.
 *
 * When [onRetry] is non-null and the state [isRetryable], an inline retry action is shown (used on the
 * detail screen, where there is no overflow menu). The board card leaves [onRetry] null and keeps its
 * retry in the card's own overflow; My Teas leaves it null too (retry via opening the tea).
 */
@Composable
fun EnrichmentStatus(
    state: EnrichmentState,
    modifier: Modifier = Modifier,
    onRetry: (() -> Unit)? = null,
    announceChanges: Boolean = false,
) {
    val label = when (state) {
        EnrichmentState.PENDING -> stringResource(R.string.enrichment_status_pending)
        EnrichmentState.QUEUED -> stringResource(R.string.enrichment_status_queued)
        EnrichmentState.RATE_LIMITED -> stringResource(R.string.enrichment_status_rate_limited)
        EnrichmentState.FAILED -> stringResource(R.string.enrichment_status_failed)
        EnrichmentState.NONE, EnrichmentState.DONE -> return
    }
    val color = if (state == EnrichmentState.FAILED) {
        MaterialTheme.colorScheme.error
    } else {
        MaterialTheme.colorScheme.onSurfaceVariant
    }
    Row(
        // UX3-P2-24: an OPT-IN polite live region so a state change (e.g. resolving -> failed) is
        // announced even when TalkBack focus isn't on this element. Only the detail screen sets it: the
        // board card and My Teas row are each one `mergeDescendants` node, so a live region here would
        // be absorbed by the parent and re-announce the WHOLE card on every change (noisy). The detail
        // screen renders this standalone, so the announcement stays scoped to the status line.
        modifier = if (announceChanges) {
            modifier.semantics { liveRegion = LiveRegionMode.Polite }
        } else {
            modifier
        },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (state == EnrichmentState.PENDING) {
            CircularProgressIndicator(
                strokeWidth = 1.5.dp,
                color = color,
                modifier = Modifier.size(12.dp),
            )
            Spacer(Modifier.width(6.dp))
        }
        Text(
            // weight(fill = false): the label yields space to the trailing retry button instead of
            // claiming the full row width, so a long "queued/rate-limited" string ellipsizes rather
            // than pushing the retry action off-screen on a narrow display or at a large font scale.
            modifier = Modifier.weight(1f, fill = false),
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = color,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        if (onRetry != null && state.isRetryable) {
            Spacer(Modifier.width(8.dp))
            TextButton(
                onClick = onRetry,
                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp),
            ) {
                Text(
                    text = stringResource(R.string.action_retry_enrichment),
                    style = MaterialTheme.typography.labelSmall,
                )
            }
        }
    }
}
