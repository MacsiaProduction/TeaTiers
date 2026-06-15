package com.macsia.teatiers.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import com.macsia.teatiers.domain.model.TeaType

/**
 * Tea-specific colors that extend the Material 3 ColorScheme: the brewed-liquor coding
 * per tea type and the default tier ramp. Accessed in composables via [TeaTheme.colors].
 */
@Immutable
data class TeaColors(
    val liquorByType: Map<TeaType, Color>,
    val tierRamp: List<Color>,
)

private val LiquorPalette: Map<TeaType, Color> = mapOf(
    TeaType.GREEN to LiquorGreen,
    TeaType.WHITE to LiquorWhite,
    TeaType.YELLOW to LiquorYellow,
    TeaType.OOLONG to LiquorOolong,
    TeaType.BLACK to LiquorBlack,
    TeaType.DARK to LiquorDark,
    TeaType.PUER to LiquorPuer,
    TeaType.HERBAL to LiquorHerbal,
    TeaType.BLENDED to LiquorBlended,
    TeaType.OTHER to LiquorOther,
)

private val DefaultTierRamp: List<Color> = listOf(TierS, TierA, TierB, TierC, TierD)

internal val LightTeaColors = TeaColors(liquorByType = LiquorPalette, tierRamp = DefaultTierRamp)
internal val DarkTeaColors = TeaColors(liquorByType = LiquorPalette, tierRamp = DefaultTierRamp)

val LocalTeaColors = staticCompositionLocalOf { LightTeaColors }

/** Entry point for the extended tea palette, mirroring `MaterialTheme`. */
object TeaTheme {
    val colors: TeaColors
        @Composable
        @ReadOnlyComposable
        get() = LocalTeaColors.current
}
