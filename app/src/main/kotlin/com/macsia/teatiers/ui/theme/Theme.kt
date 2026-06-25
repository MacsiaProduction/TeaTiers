package com.macsia.teatiers.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import com.macsia.teatiers.domain.model.ThemeMode
import com.macsia.teatiers.viewmodel.isDarkTheme

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
 * App theme. The tea-leaf-green brand scheme is the default; dynamic color (Material You) is an
 * opt-in (#28) honoured only on Android 12+, where it never touches our [LocalTeaColors] liquor
 * palette (those stay brand-fixed regardless). [themeMode] overrides the system light/dark choice.
 */
@Composable
fun TeaTiersTheme(
    themeMode: ThemeMode = ThemeMode.SYSTEM,
    dynamicColor: Boolean = false,
    content: @Composable () -> Unit,
) {
    val darkTheme = isDarkTheme(themeMode, isSystemInDarkTheme())
    val context = LocalContext.current
    val colorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ->
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        darkTheme -> DarkColorScheme
        else -> LightColorScheme
    }
    CompositionLocalProvider(LocalTeaColors provides TeaColorsValue) {
        MaterialTheme(
            colorScheme = colorScheme,
            typography = TeaTypography,
            shapes = TeaShapes,
            content = content,
        )
    }
}
