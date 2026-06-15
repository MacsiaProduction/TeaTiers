package com.macsia.teatiers.domain.model

/**
 * A customizable tier (decisions.md #6). [colorArgb] is null when the tier uses the
 * default tea-toned ramp (resolved by [position] in the UI); a user override stores an
 * explicit 0xAARRGGBB value.
 */
data class Tier(
    val id: String,
    val label: String,
    val position: Int,
    val colorArgb: Long? = null,
)
