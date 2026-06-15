package com.macsia.teatiers.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.Color

private val LightColorScheme = lightColorScheme(
    primary = LeafGreen,
    onPrimary = Color.White,
    primaryContainer = LeafGreenContainer,
    onPrimaryContainer = OnLeafContainer,
    secondary = SageSecondary,
    onSecondary = Color.White,
    secondaryContainer = SageContainer,
    onSecondaryContainer = OnSageContainer,
    tertiary = Amber,
    onTertiary = Color.White,
    tertiaryContainer = AmberContainer,
    onTertiaryContainer = OnAmberContainer,
    background = PorcelainBackground,
    onBackground = InkText,
    surface = PorcelainSurface,
    onSurface = InkText,
    surfaceVariant = PorcelainSurfaceVariant,
    onSurfaceVariant = InkMuted,
    outline = OutlineLight,
    outlineVariant = OutlineVariantLight,
    error = ErrorRed,
    onError = Color.White,
    surfaceTint = LeafGreen,
)

private val DarkColorScheme = darkColorScheme(
    primary = LeafGreenDark,
    onPrimary = OnLeafContainer,
    primaryContainer = LeafGreenContainerDark,
    onPrimaryContainer = OnLeafContainerDark,
    secondary = SageSecondaryDark,
    onSecondary = OnSageContainer,
    secondaryContainer = SageContainerDark,
    onSecondaryContainer = OnSageContainerDark,
    tertiary = AmberDark,
    onTertiary = OnAmberContainer,
    tertiaryContainer = AmberContainerDark,
    onTertiaryContainer = OnAmberContainerDark,
    background = SteepBackground,
    onBackground = SteepText,
    surface = SteepSurface,
    onSurface = SteepText,
    surfaceVariant = SteepSurfaceVariant,
    onSurfaceVariant = SteepMuted,
    outline = OutlineDark,
    outlineVariant = OutlineVariantDark,
    error = ErrorRedDark,
    onError = SteepBackground,
    surfaceTint = LeafGreenDark,
)

/**
 * App theme. Dynamic color (Material You) is intentionally off so the tea-leaf-green
 * brand identity stays consistent across devices; it can be offered as an opt-in later.
 */
@Composable
fun TeaTiersTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    val colorScheme = if (darkTheme) DarkColorScheme else LightColorScheme
    val teaColors = if (darkTheme) DarkTeaColors else LightTeaColors
    CompositionLocalProvider(LocalTeaColors provides teaColors) {
        MaterialTheme(
            colorScheme = colorScheme,
            typography = TeaTypography,
            shapes = TeaShapes,
            content = content,
        )
    }
}
