package com.macsia.teatiers.ui.components

import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.PlainTooltip
import androidx.compose.material3.Text
import androidx.compose.material3.TooltipBox
import androidx.compose.material3.TooltipDefaults
import androidx.compose.material3.rememberTooltipState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector

/**
 * An icon-only action that reveals its [label] on long-press/hover (R4-JRN-1). Icon-only top-bar
 * buttons are otherwise unlabeled for sighted users — and the app reuses the same glyph for different
 * actions across sibling screens (a list glyph means "catalog" on one screen and "sort" on another,
 * since material-icons-core lacks a Sort glyph). The tooltip surfaces the meaning without pulling in
 * the -extended icon set. [label] doubles as the TalkBack contentDescription.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TooltipIconButton(
    label: String,
    icon: ImageVector,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    TooltipBox(
        positionProvider = TooltipDefaults.rememberPlainTooltipPositionProvider(),
        tooltip = { PlainTooltip { Text(label) } },
        state = rememberTooltipState(),
        modifier = modifier,
    ) {
        IconButton(onClick = onClick) {
            Icon(imageVector = icon, contentDescription = label)
        }
    }
}
