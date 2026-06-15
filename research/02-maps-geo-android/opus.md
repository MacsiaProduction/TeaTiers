# TeaTiers: Map + Reverse-Geocoding Stack Decision for an MVP Pin-Drop "Purchase Location" Feature

## TL;DR
- **Ship MapLibre Native + OpenFreeMap tiles + on-device `android.location.Geocoder` (with a Nominatim fallback) for the MVP** — it needs no account, no API key, no Google Play Services, and renders on RuStore/AOSP devices; put Yandex MapKit behind the same interface as the "later" default once you have a Yandex API key and have accepted the free-tier terms.
- **Yandex MapKit is viable but conditional**: current version is `com.yandex.android:maps.mobile:4.33.1` (released March 17, 2026; `-lite`/`-full`/`-navikit` variants). The map renders without GMS if you exclude the `com.google.android.gms` dependency, but the free tier is restricted to free, publicly-downloadable apps under 25,000 MAU with a 25,000 requests/day geocoding cap and is legally scoped to specific territories (RU/CIS/Turkey/France).
- **Google Maps Compose is not recommended** for a Russia-first/RuStore app: `maps-compose` hard-requires Google Play Services at runtime, so it silently fails on no-GMS devices — exactly your target population.

## Key Findings

### 1. The provider abstraction is the real decision, not the map vendor
Because GMS may be absent and Yandex's free terms are restrictive, the correct architecture is a single swappable Kotlin interface with the lowest-friction (keyless, GMS-free) implementation as the MVP default and Yandex MapKit as a drop-in upgrade. The pin-drop UX (tap/drag to choose a point, then reverse-geocode) is identical across all three vendors, so the interface cost is low.

### 2. Comparison table

| Provider | SDK version (coordinates) | Key/account needed? | Free-tier limits | Reverse-geocode included? | Works without Google Play Services? | Compose interop | Offline behavior |
|---|---|---|---|---|---|---|---|
| **Yandex MapKit** | `com.yandex.android:maps.mobile:4.33.1-full` (also `-lite`, `-navikit`); Maven Central + Google Maven | Yes — API key from Yandex Developer Dashboard (~15 min to activate) | Free for free, publicly-downloadable apps; up to 25,000 MAU; 25,000 requests/day to search+routing+panoramas combined; no offline maps on free tier | Yes — Geocoder/Search in the `-full` variant; counts toward the 25k/day request limit | Yes, with caveats — POM pulls `play-services-location:21.0.1` + `play:integrity:1.1.0` but these are excludable; map renders without GMS, only positioning degrades | Wrap `MapView` in `AndroidView` (no native composable) | Lite variant: offline maps are a paid-only feature; map and tiles otherwise require network |
| **MapLibre Native** | `org.maplibre.gl:android-sdk:11.11.0` (docs current) / `13.0.1` (latest confirmed on Maven Central); BSD-2 | No (keyless library); a tile source may need one | Library is free/open; tile source determines limits (OpenFreeMap = no key/no limits) | No — needs external geocoder (Nominatim/Photon/on-device) | Yes — pure OSS; ships GMS location by default but excludable via `exclude group: 'com.google.android.gms'` | Via `ramani-maps` (`maplibre-0.9.2`) or `io.github.rallista:maplibre-compose:0.0.16`, or raw `AndroidView` | Strong — can render bundled MBTiles/PMTiles fully offline; only live geocoding needs network |
| **Google Maps Compose** | `com.google.maps.android:maps-compose` (6.x) + `com.google.android.gms:play-services-maps:19.x/20.0.0` | Yes — Google Maps Platform API key + billing account | Maps SDK for Android (mobile dynamic maps) free with no call cap; Geocoding API = 10,000 free events/month then paid | Via separate Geocoding API (server-side) | **No** — runtime-depends on Google Play Services; fails on no-GMS devices | First-class `GoogleMap` composable | Weak — no official offline map; geocoding is online-only |

### 3. Recommended picks
- **MVP pick: MapLibre Native + OpenFreeMap tiles + on-device `Geocoder` (Nominatim fallback).** One-line reason: zero accounts, zero keys, GMS-free, and it renders on the exact RuStore/AOSP devices you target — lowest possible friction to a working pin-drop.
- **Later pick: Yandex MapKit (`-full`).** One-line reason: best Russian map data and Russian-language place names for your Russia-first audience, with reverse geocoding built in — adopt it as the default once a Yandex API key is provisioned and the free-tier terms are accepted.

### 4. Provider Kotlin interface sketch

```kotlin
// A single point chosen by the user
data class GeoPoint(val lat: Double, val lng: Double)

// Result of reverse geocoding, localized to Russian
data class PlaceName(
    val displayName: String,   // human-readable, ru
    val raw: String? = null    // optional structured payload
)

interface LocationPickerProvider {
    /**
     * Renders a map (as a Composable) centered on [initial], lets the user
     * tap/drag a pin, and reports the chosen point via [onPointChosen].
     */
    @Composable
    fun MapPicker(
        initial: GeoPoint,
        onPointChosen: (GeoPoint) -> Unit,
        modifier: Modifier = Modifier
    )

    /** Reverse-geocode a point to a Russian place name; null if unavailable. */
    suspend fun reverseGeocode(point: GeoPoint, lang: String = "ru"): PlaceName?

    /** True if this provider can run on the current device (e.g. GMS present). */
    fun isAvailable(context: Context): Boolean
}
```

Implementations that map to it:
- `MapLibreLocationPicker` — wraps MapLibre `MapView` (via ramani-maps or `AndroidView`); `reverseGeocode` delegates to `android.location.Geocoder` first, then a Nominatim/Photon HTTP fallback. **MVP default.**
- `YandexLocationPicker` — wraps `com.yandex.mapkit.mapview.MapView` via `AndroidView`; `reverseGeocode` uses MapKit's `SearchManager`/geocoding (`-full`). **Later default.** `isAvailable` always true (GMS-free).
- `GoogleLocationPicker` — optional; `GoogleMap` composable + Geocoding API. `isAvailable` returns false when GMS is missing. Likely **never shipped**.
- `ManualLocationPicker` — no map; the paste-marketplace-URL / free-text path that always works.

### 5. Yandex MapKit details
- **Coordinates / versions:** `com.yandex.android:maps.mobile:4.33.1` (latest stable, released March 17, 2026), available from Maven Central and Google Maven. Use the `-full` suffix to get search/suggest/geocoding; `-lite` has only map + traffic + location layers; `-navikit` adds turn-by-turn. Per Yandex's official "Getting started" docs, verbatim: *"The lite library only contains the map, traffic layer, LocationManager, and UserLocationLayer and lets you download offline maps (in the paid version only)... The full library supplements lite version features with car routing, bike routing, pedestrian routing, and public transport routing, search, suggest, geocoding, and panorama display."* The `-lite` AAR is ~39 MB.
- **API key:** Yandex Developer Dashboard → "Connect APIs" → "MapKit Mobile SDK"; key activates in ~15 minutes; requires a Yandex ID (corporate email not accepted). Set the key in `Application.onCreate`, confirmed verbatim by Yandex docs: `override fun onCreate() { super.onCreate(); MapKitFactory.setApiKey(BuildConfig.MAPKIT_API_KEY) }`, and `MapKitFactory.initialize(Context)` "loads the MapKit's required native libraries."
- **Free-tier terms (the binding constraints):** free only in projects with open access (no private/closed apps); not for commercial/paid apps; data must be shown on the Yandex map; you may not store or modify returned data; you may not obscure Yandex copyright/logos; no monitoring/dispatching; daily request limit must not be exceeded; the app must include a link to the Yandex Maps Terms of Use. The free MAU cap is 25,000; the combined search+routing+panorama cap is 25,000 requests/day (a panorama request counts as 5).
- **Reverse geocoding:** included in `-full` (the Geocoder converts coordinates → address and vice versa); counts toward the daily request limit. Offline maps are paid-only.
- **Territory restriction:** the licensed map databases are defined for Russia, CIS countries (Ukraine, Belarus, Kazakhstan, Georgia, Abkhazia, South Ossetia, Armenia, Azerbaijan, Moldova, Estonia, Latvia, Turkmenistan, Tajikistan, Uzbekistan, Kyrgyzstan), Turkey and France — relevant because "usage outside Russia" is governed by these named territories, not arbitrary global use.
- **No-GMS / RuStore:** the Maven Central POM for `4.33.1-navikit` lists `com.google.android.gms:play-services-location:21.0.1` and `com.google.android.play:integrity:1.1.0`, but per Yandex's own maintainer on GitHub issue #256 ("MapKit should work" with the GMS dependency excluded), the map renders without GMS; only location/positioning falls back to cell-tower/Wi-Fi.
- **Compose interop:** no native composable; wrap `com.yandex.mapkit.mapview.MapView` in `AndroidView`, forwarding lifecycle (`onStart`/`onStop`) and calling `MapKitFactory.initialize(context)`.

### 6. MapLibre details
- **Coordinates / versions:** `org.maplibre.gl:android-sdk:11.11.0` is the version in current MapLibre docs; Maven Central's latest confirmed release is `13.0.1`. BSD-2 licensed, keyless.
- **Compose interop:** two community wrappers — `ramani-maps` (latest `maplibre-0.9.2`, July 2025, built on MapLibre 11.11.0, MPL-2.0) and `io.github.rallista:maplibre-compose:0.0.16` — or raw `AndroidView` around `org.maplibre.android.maps.MapView`.
- **GMS:** MapLibre ships proprietary Google Play location services by default but documents excluding them: `implementation('org.maplibre.gl:android-sdk:<v>') { exclude group: 'com.google.android.gms' }`.
- **Tile sources (production-usable, free):**
  - **OpenFreeMap** — completely free, no usage limits, no key/registration; recommended MVP default (the ramani-maps docs endorse it).
  - **OSM raster tiles (`tile.openstreetmap.org`)** — donated infra; **bulk downloading/offline prohibited**, must set a contactable User-Agent, honour ≥7-day cache TTL, show attribution; access can be withdrawn without notice. Not suitable as a primary production source.
  - **MapTiler** — free plan up to 100,000 map loads/month, no credit card; service pauses (no surprise bills) when exhausted.
  - **Protomaps** — public API free to a soft cap of 1,000,000 requests/month; uniquely, you can download a PMTiles extract and self-host/bundle for offline use.
  - **Stadia Maps** — perpetual free developer plan (no card) + 14-day Pro trial.
- **Reverse geocoding options:**
  - **Nominatim public instance** — per the OSM Foundation Usage Policy, verbatim: *"No heavy uses (an absolute maximum of 1 request per second). Provide a valid HTTP Referer or User-Agent identifying the application (stock User-Agents as set by http libraries will not do)."* Results must be cached client-side, attribution + ODbL share-alike apply; end-user-triggered lookups are fine at moderate volume. The policy now explicitly warns, verbatim: *"The public Nominatim API must not be built into, offered through, suggested by, or automatically generated by no-code, low-code, or vibe-coding platforms as a generic geocoding, address lookup, place search, or map search service."*
  - **Photon (komoot)** — public demo at `photon.komoot.io`, free "as long as the number of requests stay in a reasonable limit," extensive usage throttled/banned; supports reverse geocoding and multilingual output. Self-hosting needs ~95 GB disk and ≥64 GB RAM recommended.
  - Both are best treated as fallbacks to the on-device geocoder, with self-hosting deferred.

### 7. Google Maps Compose feasibility
`maps-compose` (current major version 6.x) depends on `com.google.android.gms:play-services-maps`, whose runtime is part of Google Play services. Google's own docs state the Maps SDK for Android runtime "is included as part of Google Play services," and apps must run on a device "based on Android 6.0 or higher and includes the Google APIs." On a no-GMS device the map will not initialize. Given RuStore-first distribution, this disqualifies it as a primary or even reliable secondary provider. Pricing-wise it would otherwise be cheap — mobile dynamic maps are free with no call cap, and the Geocoding API (an Essentials SKU) gives 10,000 free events/month then $2–$7 per 1,000 under the March 1, 2025 per-SKU model that replaced the old $200 credit (Google's billing FAQ example: 20,000 monthly Geocoding requests now yields 10,000 free + 10,000 billed = ~$50/month). But cost is moot when the SDK can't load. Recommendation: **do not ship it**; keep only a stub behind the interface if you later add a Google Play build flavor.

### 8. Permissions & privacy
- **Tap/drag-only pin selection needs NO location permission.** Choosing a point on a map is just UI; neither `ACCESS_FINE_LOCATION` nor `ACCESS_COARSE_LOCATION` is required to render a map or read a tapped coordinate.
- **Request location permission only when the user taps a "use my current location" button.** At that moment request `ACCESS_COARSE_LOCATION` (sufficient for a "near me" map recenter; less intrusive and faster to grant). Request `ACCESS_FINE_LOCATION` only if you need precise positioning.
- **Recommended flow:** ship the MVP with no location permission in the manifest at all; add the optional "my location" button later with a runtime `ACCESS_COARSE_LOCATION` request and graceful denial handling.
- **Privacy:** store only the single chosen `GeoPoint` (and the resolved place name) on the tea record; do not log coordinates to analytics/crash tooling; do not retain a location history. This aligns with the app's local-first ethos.

### 9. Offline behavior
- **MapLibre Native** is the only stack that can render a real map fully offline, by bundling MBTiles/PMTiles (e.g. a Protomaps extract) and pointing the style at a local file URI. (Note: an open upstream issue means you currently must copy the MBTiles out of APK assets into internal storage and reference `mbtiles:///absolute/path` rather than reading directly from assets.)
- **`android.location.Geocoder`** can reverse-geocode without your app having connectivity *only if* the device has a backing geocode service — but that service is itself usually network-backed and, on no-GMS devices, may be entirely absent. Always call `Geocoder.isPresent()` first; on Android 13+ (API 33) use the async `getFromLocation(..., GeocodeListener)` overload (the blocking overload is deprecated). If `isPresent()` is false or it throws "Service not Available", fall back to Nominatim/Photon (which require connectivity).
- **Yandex MapKit** offline maps are a paid-only feature; on the free tier both map rendering and geocoding require network.
- **Google Maps** has no offline map for this use case and geocoding is online-only.
- **Net:** when fully offline, MapLibre with bundled tiles still shows a map and lets the user drop a pin (capturing lat/lng), but the human-readable place name may be unavailable until connectivity returns — store the coordinate immediately and resolve the name lazily.

## Recommendations
1. **Build the `LocationPickerProvider` interface first**, then implement `MapLibreLocationPicker` + `ManualLocationPicker` for the MVP. This gets you a shippable, account-free, GMS-free pin-drop today.
2. **Use OpenFreeMap as the MVP tile source** (no key, no limits); keep the style URL configurable so you can switch to MapTiler/Protomaps/Stadia or a bundled PMTiles file without an app update.
3. **Reverse-geocode via `android.location.Geocoder` first** (guarded by `isPresent()` and the API-33 async API), falling back to Nominatim (1 req/s, custom non-stock User-Agent, cache results, attribution) — or a self-hosted Photon if volume grows.
4. **Add `YandexLocationPicker` as the eventual default** once you (a) register a Yandex API key, (b) confirm the app stays within free-tier terms (free app, open access, <25k MAU, <25k geo requests/day, attribution link present), and (c) exclude the `com.google.android.gms` dependency and test on a no-GMS device.
5. **Do not ship Google Maps Compose**; if you ever publish a Google Play flavor, gate `GoogleLocationPicker` behind `isAvailable()`.
6. **Permissions:** ship with no location permission; add `ACCESS_COARSE_LOCATION` only with the optional "use my location" button. Store only the chosen point; never log it.

**Thresholds that change these recommendations:**
- If Russian-language place-name quality or Russian map detail proves inadequate with OSM/MapLibre → promote Yandex MapKit to default sooner.
- If geocoding volume approaches ~1 request/second sustained → stop using the Nominatim public instance and self-host Photon/Nominatim.
- If you exceed 25,000 MAU, want offline Yandex maps, or go paid → you must buy a Yandex commercial license.
- If you add a Google Play build targeting GMS devices → Google Maps becomes viable as a per-flavor option.

## Reference links (publication / last-checked)
- Yandex MapKit "Getting started — Android" — yandex.com/maps-api/docs/mapkit/android (lite vs full variants, API key flow; checked 2026-06-14)
- Yandex MapKit terms of free use — yandex.com/dev/commercial/doc/en/ and yandex.com/maps-api/faq (25k MAU, 25k/day; checked 2026-06-14)
- Yandex MapKit Terms of Use / licensed territories — yandex.com/dev/tariffs/doc/en/mapkit/terms/ (RU/CIS/Turkey/France; checked 2026-06-14)
- GitHub yandex/mapkit-android-demo issue #256 — github.com/yandex/mapkit-android-demo/issues/256 (no-GMS operation; checked 2026-06-14)
- MapLibre Android quickstart — maplibre.org/maplibre-native/android/examples/getting-started/ and github.com/maplibre/maplibre-native (11.11.0, ramani-maps; checked 2026-06-14)
- OSM Nominatim Usage Policy — operations.osmfoundation.org/policies/nominatim/ (1 req/s, vibe-coding clause; checked 2026-06-14)
- OSM Tile Usage Policy — operations.osmfoundation.org/policies/tiles/ (bulk/offline prohibited; checked 2026-06-14)
- Google Maps Platform March 2025 pricing / billing FAQ — developers.google.com/maps/billing-and-pricing/march-2025 and /faq (10k free Geocoding events/SKU; checked 2026-06-14)
- Google Maps SDK setup (GMS runtime requirement) — developers.google.com/maps/documentation/android-sdk/config and /software-support (checked 2026-06-14)
- Android `Geocoder` reference — developer.android.com/reference/android/location/Geocoder (`isPresent()`, async API-33 listener; checked 2026-06-14)
- Protomaps free-tier blog — protomaps.com/blog/free-tier-maps/ (1M req/mo soft cap, PMTiles offline; checked 2026-06-14); MapTiler pricing — maptiler.com/cloud/pricing/ (100k loads; checked 2026-06-14); Photon — github.com/komoot/photon (checked 2026-06-14)

## Caveats / Uncertain — could not fully verify
- **Yandex free-tier exact numbers:** the 25,000 MAU cap and 25,000 requests/day figure come from Yandex's own pages, but Yandex states "the published terms and conditions are not an offer" and the licensing pages distinguish Free/Basic/Advanced tiers with shifting definitions. Treat the precise caps as indicative and re-confirm in the Developer Dashboard at integration time.
- **Yandex no-GMS operation** is confirmed by a Yandex maintainer's "MapKit should work" comment on GitHub issue #256 (regarding `4.0.0-lite`) plus the excludable POM dependency — but I found no official Yandex doc that formally certifies GMS-free operation on every device, and the dependency set has changed across versions (current versions add `play:integrity:1.1.0`). Test on a real RuStore/Huawei/AOSP device before committing.
- **Latest MapLibre version:** MapLibre docs reference `11.11.0` while Maven Central's latest confirmed release is `13.0.1` (an earlier search snippet showed `13.1.0`, which I could not confirm — verify the exact newest patch before citing). ramani-maps `0.9.2` is pinned to MapLibre 11.11.0; verify compatibility before bumping the underlying SDK.
- **`maps-compose` exact current version:** sources cite 4.3.3 and 6.1.3; I could not pin the single newest 6.x patch as of June 2026 — verify on Maven Central if you ever need it (moot under the recommendation not to ship it).
- **OpenFreeMap longevity/SLA:** it is free with no limits but offers no SLA; suitable for an MVP but keep the tile URL swappable.
- **Whether `android.location.Geocoder` has a working backend on specific RuStore-target devices** varies by OEM and cannot be assumed; the only safe approach is the runtime `isPresent()` check plus a network fallback.