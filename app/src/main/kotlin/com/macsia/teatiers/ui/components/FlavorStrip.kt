package com.macsia.teatiers.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.macsia.teatiers.R
import com.macsia.teatiers.domain.model.FlavorScore

private const val MAX_INTENSITY = 5

/**
 * Compact flavor meter: the strongest [max] dimensions as labeled bars, sorted by intensity.
 * The fill uses [accent] (the tea's liquor) so a card reads as a single coherent object.
 */
@Composable
fun FlavorStrip(
    flavors: List<FlavorScore>,
    accent: Color,
    modifier: Modifier = Modifier,
    max: Int = 3,
    labelWidth: Dp = 84.dp,
) {
    val track = MaterialTheme.colorScheme.surfaceVariant
    Column(modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        flavors.sortedByDescending { it.intensity }.take(max).forEach { score ->
            val label = stringResource(score.dimension.labelRes)
            val description = stringResource(R.string.a11y_flavor_dim, label, score.intensity)
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .clearAndSetSemantics { contentDescription = description },
            ) {
                Text(
                    text = label,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.width(labelWidth),
                )
                Box(
                    Modifier
                        .weight(1f)
                        .height(6.dp)
                        .clip(CircleShape)
                        .background(track),
                ) {
                    Box(
                        Modifier
                            .fillMaxWidth(score.intensity.toFloat() / MAX_INTENSITY)
                            .fillMaxHeight()
                            .clip(CircleShape)
                            .background(accent),
                    )
                }
            }
        }
    }
}
