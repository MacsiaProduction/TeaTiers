package com.macsia.teatiers.domain.model

import androidx.annotation.StringRes
import com.macsia.teatiers.R

/** Map providers a geopoint can be opened in (task: Google / Yandex / OpenStreetMap). */
enum class GeoProvider(@StringRes val labelRes: Int) {
    GOOGLE(R.string.geo_provider_google),
    YANDEX(R.string.geo_provider_yandex),
    OSM(R.string.geo_provider_osm),
}

/**
 * Where a tea was bought (task). A sealed hierarchy so each kind carries exactly its own data
 * instead of a bag of nullables: a map geopoint, a marketplace link, or free text. [label] is
 * an optional human name for the place or shop. Geopoints are sensitive (rule 50-secure): we
 * store only the point the user enters and never log it.
 */
sealed interface PurchaseLocation {
    val label: String?

    data class Geo(
        val provider: GeoProvider,
        val latitude: Double,
        val longitude: Double,
        override val label: String? = null,
    ) : PurchaseLocation {
        init {
            require(latitude in -90.0..90.0) { "latitude out of range" }
            require(longitude in -180.0..180.0) { "longitude out of range" }
        }
    }

    data class Marketplace(
        val url: String,
        override val label: String? = null,
    ) : PurchaseLocation

    data class FreeText(
        val text: String,
        override val label: String? = null,
    ) : PurchaseLocation
}
