package com.macsia.teatiers.ui.theme

import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.TestFactory

/**
 * Pure-JVM check that every curated tier-color preset clears WCAG AA contrast
 * (>= 4.5:1) for normal body text once paired with the on-color rule used by
 * `BoardScreen.TierRow` / `TierEditorScreen.TierEditRow`:
 *
 *   `if (rampColor.luminance() > 0.55f) InkOnLight else White`
 *
 * The math is sRGB-only and matches W3C's WCAG 2.x relative-luminance formula
 * so we can verify accessibility without dragging in Compose UI.
 */
class TierColorContrastTest {

    @TestFactory
    fun `every tier color preset clears WCAG AA against the chosen on-color`(): List<DynamicTest> =
        TierColorPresetsArgb.map { argb ->
            DynamicTest.dynamicTest(String.format("%08X", argb)) {
                val rampLum = relativeLuminance(argb)
                // Production rule (`pickOnColorArgb`) reads Compose's Float luminance; we
                // pass the test-computed Double down-cast to Float so the test and runtime
                // agree byte-for-byte.
                val onColor = pickOnColorArgb(rampLum.toFloat())
                val ratio = contrastRatio(rampLum, relativeLuminance(onColor))
                assertTrue(
                    ratio >= 4.5,
                    "Preset 0x${"%08X".format(argb)} vs on-color 0x${"%08X".format(onColor)} " +
                        "has ratio ${"%.2f".format(ratio)} (<4.5). " +
                        "Either tweak the preset, the on-color rule, or switch to large-text scope.",
                )
            }
        }

    /** WCAG 2.x relative luminance for an opaque 0xAARRGGBB color. Alpha ignored. */
    private fun relativeLuminance(argb: Long): Double {
        val r = channel(((argb shr 16) and 0xFF).toInt())
        val g = channel(((argb shr 8) and 0xFF).toInt())
        val b = channel((argb and 0xFF).toInt())
        return 0.2126 * r + 0.7152 * g + 0.0722 * b
    }

    private fun channel(eight: Int): Double {
        val s = eight / 255.0
        return if (s <= 0.03928) s / 12.92 else Math.pow((s + 0.055) / 1.055, 2.4)
    }

    private fun contrastRatio(lA: Double, lB: Double): Double {
        val lighter = maxOf(lA, lB)
        val darker = minOf(lA, lB)
        return (lighter + 0.05) / (darker + 0.05)
    }
}
