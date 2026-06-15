package com.macsia.teatiers.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.macsia.teatiers.domain.model.Tea
import com.macsia.teatiers.ui.theme.TeaTheme

private val CardWidth = 172.dp

/** A tea as placed on a board: liquor swatch, multilingual names, type, and a compact flavor strip. */
@Composable
fun TeaCard(tea: Tea, modifier: Modifier = Modifier) {
    val liquor = TeaTheme.colors.liquorByType[tea.type] ?: MaterialTheme.colorScheme.secondary
    val secondaryName = listOfNotNull(tea.pinyin, tea.nameZh).joinToString("  ·  ")

    Surface(
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 1.dp,
        modifier = modifier.width(CardWidth),
    ) {
        Column(Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                LiquorSwatch(type = tea.type, size = 18.dp)
                Spacer(Modifier.width(10.dp))
                Text(
                    text = tea.nameRu,
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
            Spacer(Modifier.height(10.dp))
            TypeChip(type = tea.type)
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
