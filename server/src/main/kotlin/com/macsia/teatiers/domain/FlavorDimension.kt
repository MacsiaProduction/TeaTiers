package com.macsia.teatiers.domain

/**
 * The locked 11-dimension flavor vocabulary (decision #46). Stored as text in the DB so the
 * set stays extensible; client localizes the labels (горечь/bitterness/苦味) in strings.xml.
 */
enum class FlavorDimension {
    BITTERNESS,
    SWEETNESS,
    ASTRINGENCY,
    FRUITINESS,
    FLORAL,
    GRASSY,
    SPICY,
    SMOKY,
    EARTHY_NUTTY,
    UMAMI,
    ROASTED,
}
