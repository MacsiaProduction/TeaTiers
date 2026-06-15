Based on a deep dive into map SDKs, licensing terms, and technical compatibility for a Russia-first Android MVP, here is the architectural and product-level analysis for **TeaTiers**.

---

### 1. Comparison of Map & Geocoding Providers

| Provider | SDK Version & Coordinate | Key/Account Needed? | Free-Tier Limits | Reverse-Geocode Included? | Works without GMS? | Compose Interop | Offline Behavior |
| :--- | :--- | :--- | :--- | :--- | :--- | :--- | :--- |
| **Yandex MapKit** | `com.yandex.android:maps.mobile:4.36.0-full` | **Yes**. Developer Console account required. | **25,000 MAU** (or 1,000 DAU depending on contract). | **Yes** (via `-full` version). Free tier **prohibits storing results**. | **Yes**. Fully independent of Google Play Services. | **`AndroidView` Interop** (needs wrapper for `MapView`). | **Degraded**. Automatic caching of viewed tiles. Offline downloads restricted to paid versions. |
| **MapLibre Native** (Recommended) | `org.maplibre.compose:maplibre-compose:0.13.0` | **No** (using keyless tiles like **OpenFreeMap**). | **Unlimited** for self-hosted/open tiles. | **No**. Must pair with Photon/Nominatim HTTP lookup. | **Yes**. 100% open-source native C++/Kotlin rendering. | **Native Compose** (First-party composable). | **Robust**. Highly customizable vector caching and local MBTiles loading. |
| **Google Maps** | `com.google.maps.android:maps-compose:6.1.0` | **Yes**. GCP Billing setup required. | $200 monthly credit (approx. 28k map loads). | **Yes** (separately billed via Places/Geocoding API). | **No**. Hard runtime dependency on GMS. | **Native Compose** (`maps-compose` wrapper). | **Poor**. Limited GMS-level tile caching; no programmatic offline control. |

---

### 2. Provider Deep Dives

#### Yandex MapKit Mobile SDK
* **Gradle Coordinates:**
  ```kotlin
  // Lite includes map + location markers; Full adds routing, search, and reverse-geocoding.
  implementation("com.yandex.android:maps.mobile:4.36.0-full")
  ```
* **API Key & Limits:** Keys are requested via the [Yandex Developer Dashboard](https://admin.api.maps.yandex.ru/). The free tier is limited to **25,000 MAU** (Monthly Active Users). 
* **Critical Terms Violation:** Yandex's Free Terms of Use (Section 2) states: *"Storing or changing data obtained via the API is prohibited... You are not allowed to obtain data from the Geocoder and then save it to your database"*. Because TeaTiers is a local-first app attaching human-readable locations directly to tea records in a Room database, **the Yandex free tier strictly prohibits this use case**. A commercial license (allowing storage) starts at **195,000 RUB/year**, making Yandex highly impractical for an indie MVP.
* **Compose Integration:** Requires wrapping Yandex's `MapView` in an `AndroidView`.

#### MapLibre Native Android (The Keyless Powerhouse)
* **Gradle Coordinates:**
  ```kotlin
  implementation("org.maplibre.compose:maplibre-compose:0.13.0")
  ```
* **Compose Interop:** Unlike Yandex, MapLibre features a first-party, active **Compose Multiplatform** native wrapper (`org.maplibre.compose:maplibre-compose`). No raw `AndroidView` wrapper is needed.
* **Free Tile Sources:** Since MapLibre is keyless, it requires a style/tile source.
  * **OpenFreeMap** is a non-profit, zero-registration project offering open OSM vector tiles with their clean "Liberty" style. It requires no account, enforces no trackers, and provides high-speed global delivery.
  * **OSM Public Raster Tiles:** Highly restricted by the OSM Tile Usage Policy (disallows high-volume mobile app loads), and raster maps degrade on high-DPI screens.
* **Reverse Geocoding (Photon vs. Nominatim):**
  * **Nominatim (Public instance):** Limits queries to **1 request/second** absolute max. Requires a highly specific `User-Agent` containing the app name/email.
  * **Photon (Powered by Komoot):** `https://photon.komoot.io` is optimized for search-as-you-type and reverse lookups. It is multilingual, highly tolerant of typos, supports Russian (`lang=ru`), and allows database caching. It has loose rate limit guidelines ("be fair") and can be easily self-hosted on a simple VPS if app traffic scales.

#### Google Maps Compose
* **Viability:** Strongly discouraged. Because target devices running APKs from RuStore may lack Google Play Services (GMS), Google Maps is fundamentally unstable for this deployment. Furthermore, setting up GCP billing is virtually impossible for developers using Russian credit cards due to global banking sanctions.

---

### 3. Recommended MVP Strategy

1. **MVP Pick: MapLibre Compose + OpenFreeMap tiles + Photon HTTP Geocoding.**
   * *Reason:* **Zero financial friction and 100% compliant.** This stack does not require setting up credit cards, does not collect analytics trackers, works flawlessly without GMS, and allows local storage of the reverse-geocoded location name in your local database.
2. **Later Pick: Yandex MapKit (Commercial)**
   * *Reason:* If the app achieves monetization, transitioning to a paid Yandex license offers unparalleled map fidelity and business search in the Russian region, though it couples you to their proprietary ecosystem.

---

### 4. Provider Abstraction Sketch

To decouple your UI and database from the mapping provider, wrap the logic in a clean domain interface. Define a domain-specific `TeaLatLng` representation to prevent leaking library-specific coordinates:

```kotlin
// Domain-specific point representation
data class TeaLatLng(val latitude: Double, val longitude: Double)

interface MapProvider {
    val name: String

    @Composable
    fun RenderMap(
        modifier: Modifier,
        initialPosition: TeaLatLng?,
        onPinPositionChanged: (TeaLatLng) -> Unit
    )

    suspend fun reverseGeocode(latLng: TeaLatLng): Result<String>
}
```

#### MapLibre Implementation (MVP)
Using `org.maplibre.compose`, the map rendering is fully declarative:

```kotlin
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import org.maplibre.compose.MaplibreMap
import org.maplibre.compose.rememberCameraState
import org.maplibre.compose.symbols.Symbol
import org.maplibre.compose.symbols.rememberSymbolState
// For HTTP lookup (e.g., Ktor client)
import io.ktor.client.*
import io.ktor.client.request.*
import io.ktor.client.statement.*

class MapLibreProvider(private val httpClient: HttpClient) : MapProvider {
    override val name: String = "MapLibre"

    @Composable
    override fun RenderMap(
        modifier: Modifier,
        initialPosition: TeaLatLng?,
        onPinPositionChanged: (TeaLatLng) -> Unit
    ) {
        val cameraState = rememberCameraState()
        val symbolState = rememberSymbolState()

        // OpenFreeMap Liberty style (Keyless vector tiles)
        val styleUrl = "https://tiles.openfreemap.org/styles/liberty"

        MaplibreMap(
            modifier = modifier,
            styleUri = styleUrl,
            cameraState = cameraState,
            onMapClick = { point ->
                val newLatLng = TeaLatLng(point.latitude, point.longitude)
                onPinPositionChanged(newLatLng)
            }
        ) {
            initialPosition?.let {
                Symbol(
                    state = symbolState,
                    position = org.maplibre.spatialk.geojson.Point(it.longitude, it.latitude)
                )
            }
        }
    }

    override suspend fun reverseGeocode(latLng: TeaLatLng): Result<String> = runCatching {
        // Querying public Komoot Photon instance with Russian language parameter
        val url = "https://photon.komoot.io/reverse?lat=${latLng.latitude}&lon=${latLng.longitude}&lang=ru"
        val response: HttpResponse = httpClient.get(url) {
            headers {
                append("User-Agent", "TeaTiersAndroidApp/1.0 (contact@teatiers.local)")
            }
        }
        parseAddressFromPhoton(response.bodyAsText())
    }

    private fun parseAddressFromPhoton(jsonString: String): String {
        // Parse geojson features to extract human-readable address properties (city, street, etc.)
        return "Parsed Place Name"
    }
}
```

---

### 5. Permissions & Privacy Flow

A highly respectful, low-friction permission design:

1. **Map Browsing & Pin Dropping:** **Require 0 permissions.** If a user manually pans, zooms, or drags a pin to specify a purchase location, the app does not access the device's GPS. Center the map on a sensible fallback (e.g., Moscow, `55.7558, 37.6173`) and let the user interact immediately.
2. **"Use My Current Location" Button:** Only request permissions when this button is explicitly tapped.
3. **Coarse vs. Fine Location:** Request only `ACCESS_COARSE_LOCATION`. Pinpointing a tea retailer or marketplace does not require sub-meter precision; city/neighborhood-level accuracy is sufficient, speeds up location resolution, and builds user trust.
4. **Local-First Privacy:** Keep coordinates stored locally in Room. Do not log coordinate trails or establish remote telemetry metrics on coordinates.

---

### 6. Offline Behavior & Graceful Degradation

Because TeaTiers is local-first, the location feature must handle network loss gracefully:

* **Map Rendering Offline:**
  * *MapLibre:* Vector tiles cached during online browsing are saved locally. Developers can also package offline databases (`MBTiles` format) for default offline regions, which will render map frameworks entirely offline.
  * *Yandex MapKit:* In the free version, offline vector packages are blocked. Only recently fetched tiles stored in the system cache will render.
* **Geocoding Offline:**
  * No public HTTP service (Photon, Nominatim) works offline.
  * Android's system `android.location.Geocoder` is highly unreliable offline, as it generally delegates its engine back to GMS servers (which may also be absent).
  * **Product Fallback (Spec Alignment):** If the network is absent, immediately display an in-app banner: *"Offline Mode: Map search unavailable."* Let the user bypass the map entirely to manually type the place name or paste their marketplace URL into the provided text field.

---

### 7. References

1. [Yandex MapKit Quickstart for Android](https://yandex.ru/dev/mapkit/doc/en/android/quickstart) (Updated 2026)
2. [Yandex Maps API Terms of Free Use](https://yandex.ru/legal/maps_api/en/) (Terms detailing open access, branding, and explicit storage bans)
3. [MapLibre Compose GitHub Repository](https://github.com/maplibre/maplibre-compose) (Official Compose integration project)
4. [MapLibre Compose Releases](https://github.com/maplibre/maplibre-compose/releases) (Detailing `0.13.0` release on Maven Central)
5. [Komoot Photon Official Documentation](https://github.com/komoot/photon) (Open-source geocoding API)
6. [OpenStreetMap Nominatim Usage & Geocoding Policy](https://operations.osmfoundation.org/policies/nominatim/) (API requirements and limit policies)
7. [OpenFreeMap Documentation](https://openfreemap.org/) (Production-ready keyless map style serving)

---

### 8. Uncertainties / Unverified Claims

* **Yandex Local Pricing Fluctuations:** While the baseline commercial price for geocoding is historically fixed in rubles (~195,000 RUB/year), exact billing structures may vary if routed through non-Russian contracting entities (such as Iron Hive Belgrade).
* **Aurora OS/State Android Geocoder Implementations:** On specialized, GMS-free Russian system forks (such as Aurora OS or custom AOSP ROMs running on regional hardware), the offline behavior of native `android.location.Geocoder` is unverified and highly device-dependent.
* **Photon Public Server SLA:** Although `photon.komoot.io` allows fair-use geocoding, they do not guarantee service availability or uptime SLAs for production apps. If TeaTiers scales globally, self-hosting a Photon container is highly recommended.