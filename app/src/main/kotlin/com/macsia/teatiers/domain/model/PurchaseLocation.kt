package com.macsia.teatiers.domain.model

/**
 * Where a tea was bought. MVP scope (decisions.md #20) is a marketplace link or free text
 * only; the map geopoint (Google/Yandex/OSM pick-and-store) is deferred to M6 (#9/#20), so
 * this stays a two-case sealed type. [label] is an optional human name for the place or shop.
 */
sealed interface PurchaseLocation {
    val label: String?

    data class Marketplace(
        val url: String,
        override val label: String? = null,
    ) : PurchaseLocation

    data class FreeText(
        val text: String,
        override val label: String? = null,
    ) : PurchaseLocation
}
