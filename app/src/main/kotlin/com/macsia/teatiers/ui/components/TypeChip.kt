package com.macsia.teatiers.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.macsia.teatiers.domain.model.TeaType
import com.macsia.teatiers.ui.theme.TeaTheme

/** A quiet pill naming the tea [type], dotted with its liquor color so coding stays consistent. */
@Composable
fun TypeChip(type: TeaType, modifier: Modifier = Modifier) {
    val liquor = TeaTheme.colors.liquorByType[type] ?: MaterialTheme.colorScheme.secondary
    Surface(
        shape = MaterialTheme.shapes.small,
        color = liquor.copy(alpha = 0.16f),
        contentColor = MaterialTheme.colorScheme.onSurface,
        modifier = modifier,
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            modifier = Modifier.padding(horizontal = 9.dp, vertical = 4.dp),
        ) {
            Box(
                Modifier
                    .size(8.dp)
                    .clip(CircleShape)
                    .background(liquor),
            )
            Text(
                text = stringResource(type.labelRes),
                style = MaterialTheme.typography.labelMedium,
            )
        }
    }
}
