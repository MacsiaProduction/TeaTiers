# Rating — 02-maps-geo-android

Prompt: ./prompt.md   ·   Date judged: 2026-06-14

Scale 1–5 per dimension; **Halluc.↓** is inverted (1 = none, 5 = many → lower is
better). When torn between two scores, pick the lower. The **rank + winner** is the
real output — the numeric Score is only a tiebreaker. See ../README.md → *Rating*.

| Model  | Accuracy | Depth | Actionability | Halluc.↓ | Clarity | Score | Rank |
|--------|:--------:|:-----:|:-------------:|:--------:|:-------:|:-----:|:----:|
| opus   |    5     |   5   |       5       |    2     |    4    | 4.20  |  1   |
| gemini |    4     |   4   |       4       |    2     |    5    | 3.50  |  2   |
| gpt    |    4     |   3   |       3       |    2     |    3    | 2.85  |  3   |
| kimi   |    -     |   -   |       -       |    -     |    -    |   -   |  –   |

<!-- Score = 0.35·Accuracy + 0.20·Depth + 0.25·Actionability + 0.10·Clarity − 0.10·Halluc. · kimi not run -->

**Winner:** opus — all three reach the same recommendation (MVP = MapLibre + keyless
tiles + Photon/Nominatim; Yandex later; never ship Google Maps on a no-GMS RuStore
device), but opus is the most rigorous engineer: real Android specifics
(`Geocoder.isPresent()`, the API-33 async `getFromLocation` overload), the GitHub
issue #256 no-GMS evidence, the Nominatim "no vibe-coding platforms" + 1 req/s policy,
a swappable OpenFreeMap style URL, an interface with `isAvailable()` + a
`ManualLocationPicker`, and an honest "could-not-verify" list that explicitly flags the
conflicting version pins. It also independently surfaces the Yandex "may not store
returned data" term that gemini makes its headline.

**Decision impact (this flips a planning assumption):** I had Yandex as the intended
default with MapLibre as fallback. The research inverts that. **gemini's sharpest
finding — corroborated by opus — is that Yandex's *free* terms prohibit storing
geocoder results in your own DB**, which is exactly what a local-first app does (it
saves the resolved place name to Room). So Yandex free tier is incompatible with the
core use case; using it for storage needs a paid commercial license (gemini cites
~195,000 RUB/yr, unverified). **MapLibre + keyless tiles becomes both the MVP and the
likely long-term default; Yandex is demoted to an optional, terms-gated upgrade.**

**Reuse:**
- **MVP stack** → `context/decisions.md` #9 + `context/plan.md` §maps: **MapLibre
  Native + OpenFreeMap tiles (style URL kept configurable) + reverse geocode via
  on-device `android.location.Geocoder` (guarded by `isPresent()` + API-33 async),
  falling back to Photon (`lang=ru`) or Nominatim (1 req/s, custom non-stock
  User-Agent, cache results, ODbL attribution).** No GMS, no key, no account.
- **Provider interface** → opus's `LocationPickerProvider` (`@Composable MapPicker`,
  `suspend reverseGeocode(...,"ru")`, `isAvailable(context)`) + a `ManualLocationPicker`
  for the URL / free-text path that always works offline.
- **Permissions** → pin-drop needs **no location permission**; request
  `ACCESS_COARSE_LOCATION` only behind an optional "use my location" button. Store
  only the chosen point + resolved name; never log coordinates.
- **Offline** → bundle/cached MapLibre tiles render a map; if geocoding is unavailable,
  store the coordinate now and resolve the name later, or let the user type it (gemini's
  "Offline Mode: map search unavailable" banner).
- **Do NOT ship Google Maps** (hard GMS runtime dep; also GCP billing impractical with
  RU cards per gemini).

**Discard (verify before pinning):**
- **All version pins — they mutually conflict, so trust none:** Yandex
  `maps.mobile` 4.33.1 (opus) vs 4.36.0 (gemini) vs 4.38.0 (gpt); MapLibre
  `android-sdk` 11.11.0/13.0.1 (opus) vs 11.13.5 (gpt); `maps-compose` 6.1.0/6.12.0/6.x.
  Pin against Maven Central at integration time.
- **Whether `org.maplibre.compose:maplibre-compose:0.13.0` is a mature "first-party"
  Compose composable** (gemini/gpt) vs community wrappers + raw `AndroidView` (opus).
  gemini's code sample APIs (`MaplibreMap`, `rememberCameraState`,
  `org.maplibre.spatialk.geojson.Point`) are illustrative — verify before copying.
- **Yandex free-tier numbers** (25k MAU / 25k-per-day / 1000 DAU) — unconfirmed; Yandex
  says the terms "are not an offer." The ~195,000 RUB/yr commercial figure is unverified.
- **No-GMS operation is community-confirmed (issue #256), not officially certified** —
  test on a real RuStore/Huawei/AOSP device before committing to Yandex.
- **OpenFreeMap has no SLA** — fine for MVP, keep the tile URL swappable.
