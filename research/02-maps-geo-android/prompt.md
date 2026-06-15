# 02-maps-geo-android — map + reverse-geocode provider for a Russia-first Android (Compose) pin-drop feature

<!--
The SINGLE prompt for this run. Send this exact text to every model.
Do NOT tailor it per model. If a tool's input limit forces a change, note it
under "Adaptations" at the bottom.
Save each model's verbatim answer next to this file as <model>.md
(opus.md, gpt.md, gemini.md, kimi.md, …). Then fill RATING.md and bump
../LEADERBOARD.md. See ../README.md for the full spec.
-->

## Context

Project: **TeaTiers** — a personal, local-first tier-list Android app for teas
(Kotlin 2.x, Jetpack Compose, MVVM, minSdk to be pinned). One feature lets a user
attach a **purchase location** to a tea: drop/drag a pin on a map, then
**reverse-geocode** it to a human-readable place name (shown in Russian), OR instead
paste a marketplace URL, OR type free text. We want the map/geocoding provider behind
a **single Kotlin interface** so it can be swapped.

Locked decisions relevant here:
- **Russia-first** distribution: **RuStore + direct APK**, NOT primarily Google Play.
  So Google Play Services may be absent on target devices — solutions must not hard-
  depend on GMS.
- Yandex is the intended default provider, but the **maps SDK choice is exactly what
  this research decides** — Yandex MapKit vs keyless MapLibre/OSM vs Google Maps.
- The app is local-first; only the shared tea catalog needs the network. We'd like
  the location feature to degrade gracefully with poor/no connectivity.

## Objective

Decide which map + reverse-geocoding stack to ship for the MVP pin-drop feature on a
Russia-first Android/Compose app distributed via RuStore/APK, and which (if any) to
add later — accounting for API keys, free-tier limits, licensing/terms, no-GMS
viability, Compose interop, and offline behavior.

## Questions

1. **Yandex MapKit Mobile SDK (Android).** Current version and Gradle/Maven
   coordinates; how to obtain an **API key** and the exact **free-tier limits/quotas**
   and **terms** (esp. for an app distributed outside Google Play / via RuStore, and
   any restriction on usage outside Russia). Does it include **reverse geocoding**
   (Search/Geocoder API) and what are its limits? How is the SDK embedded in Compose
   (AndroidView interop)? Pin exact versions.
2. **MapLibre Native Android (keyless alternative).** Current version and Gradle
   coordinates; Compose interop; a **production-usable free tile source** and its
   terms (OSM raster tile usage policy vs free tiers of MapTiler / Protomaps /
   Stadia), and **reverse geocoding via Nominatim or Photon** — public-instance usage
   policy and rate limits vs self-hosting effort. Pin exact versions.
3. **Google Maps Compose (`maps-compose`).** Feasibility given Russia-first + RuStore
   and possibly-absent Play Services: does it require GMS at runtime? Key/billing
   model and free-tier. Is it worth keeping as a third provider at all?
4. **Provider abstraction.** Propose a minimal Kotlin interface that covers: (a) show
   a map with a draggable/tappable pin, (b) return the chosen `lat/lng`, (c)
   reverse-geocode that point to a place name in Russian. Recommend which
   implementation(s) to ship for **MVP** vs **later**, and which is the lowest-
   friction starting point (no external account needed).
5. **Permissions & privacy.** Minimal Android location-permission flow: if the user
   only *taps a map* to choose a point, do we need `ACCESS_FINE_LOCATION` /
   `ACCESS_COARSE_LOCATION` at all, or only when offering a "use my current location"
   button? Recommend storing only the chosen point and not logging it.
6. **Offline behavior.** Which of these can render a map and/or reverse-geocode with
   no connectivity (cached tiles, on-device `android.location.Geocoder`, bundled
   data), and exactly what degrades when offline.

## Evidence standards

- Prefer official SDK docs / developer consoles / license pages over blog posts.
- Pin exact SDK versions and Gradle coordinates; explicitly flag anything you are not
  certain exists, and any free-tier number you could not confirm on an official page.
- Cite every claim with a link and its publication/last-checked date; prefer recent
  sources. Be explicit about Yandex free-tier terms and any RuStore/no-GMS caveats.

## Return

1. A **comparison table**: `Provider | SDK version | Key/account needed? | Free-tier
   limits | Reverse-geocode included? | Works without Google Play Services? | Compose
   interop | Offline behavior`.
2. A **recommended MVP pick + a later pick**, with the one-line reason for each.
3. A **sketch of the provider Kotlin interface** and which implementations map to it.
4. A recommended **permission flow** (which permissions, when requested).
5. 5–8 high-quality reference links with dates, and an explicit **"uncertain / could
   not verify"** list (especially Yandex free-tier terms and SDK availability).

---

Models run: <opus, gpt, gemini, kimi>   ·   Date: 2026-06-14

## Adaptations (if any)

- <model>: <what you changed for this tool, and why>
