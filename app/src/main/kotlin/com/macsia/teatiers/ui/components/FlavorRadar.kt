package com.macsia.teatiers.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import com.macsia.teatiers.R
import com.macsia.teatiers.domain.model.FlavorScore
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

private const val MAX_INTENSITY = 5f
private const val RINGS = 4

/**
 * Radial flavor profile — the brief's "flavor radar" motif. Each axis is a tasted dimension,
 * the filled polygon its intensities (0..5). Needs >= 3 axes to form a shape; fewer than that
 * should render a [FlavorStrip] instead. Color comes from [accent] (the tea's liquor).
 */
@Composable
fun FlavorRadar(
    flavors: List<FlavorScore>,
    accent: Color,
    modifier: Modifier = Modifier,
) {
    val labels = flavors.map { stringResource(it.dimension.labelRes) }
    val intensities = flavors.map { it.intensity }
    val gridColor = MaterialTheme.colorScheme.outlineVariant
    val labelColor = MaterialTheme.colorScheme.onSurfaceVariant
    val measurer = rememberTextMeasurer()
    val labelStyle = MaterialTheme.typography.labelSmall.copy(color = labelColor)

    // The chart is purely visual, so expose the profile to TalkBack (decisions.md #28).
    val axisDescriptions = flavors.indices.map { i ->
        stringResource(R.string.a11y_flavor_dim, labels[i], intensities[i])
    }
    val profileDescription = stringResource(R.string.a11y_flavor_profile, axisDescriptions.joinToString(", "))

    Canvas(modifier.semantics { contentDescription = profileDescription }) {
        val n = intensities.size
        if (n < 3) return@Canvas

        val center = Offset(size.width / 2f, size.height / 2f)
        val radius = min(size.width, size.height) / 2f * 0.64f
        val startAngle = (-Math.PI / 2).toFloat()
        val step = (2.0 * Math.PI / n).toFloat()

        fun vertex(index: Int, r: Float): Offset {
            val angle = startAngle + index * step
            return Offset(center.x + r * cos(angle), center.y + r * sin(angle))
        }

        for (ring in 1..RINGS) {
            val ringRadius = radius * ring / RINGS
            val gridPath = Path()
            for (i in 0 until n) {
                val p = vertex(i, ringRadius)
                if (i == 0) gridPath.moveTo(p.x, p.y) else gridPath.lineTo(p.x, p.y)
            }
            gridPath.close()
            drawPath(gridPath, gridColor, style = Stroke(width = 1.dp.toPx()))
        }

        for (i in 0 until n) {
            drawLine(gridColor, center, vertex(i, radius), strokeWidth = 1.dp.toPx())
        }

        val dataPath = Path()
        for (i in 0 until n) {
            val p = vertex(i, radius * (intensities[i] / MAX_INTENSITY))
            if (i == 0) dataPath.moveTo(p.x, p.y) else dataPath.lineTo(p.x, p.y)
        }
        dataPath.close()
        drawPath(dataPath, accent.copy(alpha = 0.22f))
        drawPath(dataPath, accent, style = Stroke(width = 2.dp.toPx()))

        for (i in 0 until n) {
            drawCircle(accent, radius = 3.dp.toPx(), center = vertex(i, radius * (intensities[i] / MAX_INTENSITY)))
        }

        for (i in 0 until n) {
            val anchor = vertex(i, radius + 16.dp.toPx())
            val layout = measurer.measure(labels[i], labelStyle)
            drawText(
                textLayoutResult = layout,
                topLeft = Offset(anchor.x - layout.size.width / 2f, anchor.y - layout.size.height / 2f),
            )
        }
    }
}
