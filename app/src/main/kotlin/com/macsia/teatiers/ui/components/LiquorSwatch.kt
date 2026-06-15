package com.macsia.teatiers.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.macsia.teatiers.domain.model.TeaType
import com.macsia.teatiers.ui.theme.TeaTheme

/**
 * The signature element: a filled disc tinted with the color of the brewed liquor (настой)
 * for [type]. Used standalone, stacked on board cards, and enlarged in the featured panel.
 *
 * [ringColor] separates overlapping swatches when stacked (pass the surface color there).
 */
@Composable
fun LiquorSwatch(
    type: TeaType,
    modifier: Modifier = Modifier,
    size: Dp = 16.dp,
    ringColor: Color = MaterialTheme.colorScheme.outlineVariant,
    ringWidth: Dp = 1.dp,
) {
    val liquor = TeaTheme.colors.liquorByType[type] ?: MaterialTheme.colorScheme.outline
    Box(
        modifier
            .size(size)
            .clip(CircleShape)
            .background(liquor)
            .border(ringWidth, ringColor, CircleShape),
    )
}
