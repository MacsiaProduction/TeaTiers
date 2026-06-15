package com.macsia.teatiers.ui.theme

/**
 * Curated tea-toned tier-color presets for the color picker (0xAARRGGBB), echoing the
 * "Настой" palette. Lifted out of `TierEditorScreen.kt` so a pure-JVM unit test can assert
 * each preset, paired with the screen-side on-color rule (`luminance() > 0.55 → ink else
 * white`), still clears WCAG AA contrast (>= 4.5:1) for tier-label text.
 *
 * If you add or change a preset here, also re-run `./gradlew :app:testDebugUnitTest --tests
 * TierColorContrastTest`; the test owns the contract.
 */
internal val TierColorPresetsArgb: List<Long> = listOf(
    // Row 1: roasted reds + amber. The 3rd swatch (deep copper) replaces a brighter
    // 0xFFB5742B that landed in the WCAG "dead zone" (4.49:1 against both ink and white).
    0xFF7A3B2EL, 0xFFB05A2CL, 0xFF8C5722L, 0xFFC9A53EL,
    // Row 2: greens. The 1st swatch (mountain green) replaces a sage 0xFF5E8C5A that
    // sat at 4.39:1 — close enough to be wrong on AMOLED phones.
    0xFF3F6F3DL, 0xFF356A4BL, 0xFF55715CL, 0xFF9DAE93L,
    0xFF6E3520L, 0xFFC46E86L, 0xFF4C7A99L, 0xFF8C8A82L,
)

/** Ink color used on light tier swatches (0xAARRGGBB). Mirrors the screen-side constant. */
internal const val InkOnLightArgb: Long = 0xFF1E1B16L

/** Ink color used on dark tier swatches (0xAARRGGBB) — plain white. */
internal const val InkOnDarkArgb: Long = 0xFFFFFFFFL

/**
 * Picks whichever of [InkOnLightArgb] / [InkOnDarkArgb] gives higher WCAG contrast against
 * a tier color of the given relative luminance. Replaces the earlier hardcoded
 * `luminance > 0.55 → ink else white` rule, which scored mid-tone presets badly (the
 * crossover for pure-black/pure-white is around 0.18, not 0.55). Used by both
 * `BoardScreen` and `TierEditorScreen`, and verified by `TierColorContrastTest`.
 */
internal fun pickOnColorArgb(rampLuminance: Float): Long {
    val rampLum = rampLuminance.toDouble()
    val inkLum = WcagLuminance.INK_ON_LIGHT
    val whiteLum = WcagLuminance.WHITE
    val ratioIfInk = wcagContrast(rampLum, inkLum)
    val ratioIfWhite = wcagContrast(rampLum, whiteLum)
    return if (ratioIfInk >= ratioIfWhite) InkOnLightArgb else InkOnDarkArgb
}

/** Pre-computed WCAG relative luminances for the two on-colors, so the picker is cheap. */
private object WcagLuminance {
    // Computed once from 0xFF1E1B16 via the standard sRGB->linear gamma curve.
    const val INK_ON_LIGHT: Double = 0.00828
    const val WHITE: Double = 1.0
}

private fun wcagContrast(a: Double, b: Double): Double {
    val lighter = maxOf(a, b)
    val darker = minOf(a, b)
    return (lighter + 0.05) / (darker + 0.05)
}
