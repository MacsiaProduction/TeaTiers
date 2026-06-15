package com.macsia.teatiers.data.location

import com.macsia.teatiers.domain.model.GeoProvider

/**
 * One interface over the map providers (rule 20-android: abstract Google / Yandex / OSM behind
 * a single interface). For now a provider only turns a stored geopoint into a view URI; an
 * interactive picker and runtime location capture arrive with the maps integration
 * (research/02-maps-geo-android). We store and hand off only the point the user chose.
 */
interface MapLinkProvider {
    val provider: GeoProvider

    /** A web URI that opens [latitude]/[longitude] in this provider's map. */
    fun viewUri(latitude: Double, longitude: Double): String
}

internal object GoogleMapLinkProvider : MapLinkProvider {
    override val provider = GeoProvider.GOOGLE
    override fun viewUri(latitude: Double, longitude: Double): String =
        "https://www.google.com/maps/search/?api=1&query=$latitude,$longitude"
}

internal object YandexMapLinkProvider : MapLinkProvider {
    override val provider = GeoProvider.YANDEX

    // Yandex expects ll/pt as longitude,latitude (x,y), the opposite order of Google.
    override fun viewUri(latitude: Double, longitude: Double): String =
        "https://yandex.ru/maps/?ll=$longitude,$latitude&z=16&pt=$longitude,$latitude"
}

internal object OsmMapLinkProvider : MapLinkProvider {
    override val provider = GeoProvider.OSM
    override fun viewUri(latitude: Double, longitude: Double): String =
        "https://www.openstreetmap.org/?mlat=$latitude&mlon=$longitude#map=16/$latitude/$longitude"
}

/** Registry resolving a [GeoProvider] to its [MapLinkProvider]. */
object MapLinks {
    private val byProvider: Map<GeoProvider, MapLinkProvider> =
        listOf(GoogleMapLinkProvider, YandexMapLinkProvider, OsmMapLinkProvider)
            .associateBy { it.provider }

    fun of(provider: GeoProvider): MapLinkProvider = byProvider.getValue(provider)
}
