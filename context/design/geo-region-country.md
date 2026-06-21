# Design: dataset-grounded country + region (toward a tea-region map)

Status: design + lockable decision only. **No implementation in this doc.** Locks decision #138. Targets
the shared catalog service (PostgreSQL) and, later, the Android app. Implementation is a separate PR.

Implementation baseline: server `21fda80` + the #137 scrape-foundation hardening (PRs #119/#120). This
design must land before the scraper hardens `region` as a free-text scalar (#137-C5/C6 currently does).

## 1. Problem and goal

`tea.origin_country` and `tea.region` are free `TEXT` invented by the seed author:

- **Country** — 19 distinct values, all already valid **ISO 3166-1 alpha-2** (`CN, IN, JP, LK, TW, GE,
  KE, …`), but unvalidated, so a typo or a full name ("China") would be stored silently.
- **Region** — 40 hand-shaped, inconsistent strings: `Anji, Zhejiang` / `Mount Emei, Sichuan` /
  `Junshan Island, Hunan` / `Darjeeling, West Bengal` / `Nuwara Eliya`. No identity, no coordinates, no
  localization, no way to put a tea on a map.

**Goals**
- **G1** — country is a validated ISO 3166-1 alpha-2 code with localized (ru/en/zh) display names.
- **G2** — region has a *stable canonical identity* from a standard dataset (we never invent regions),
  with localized names and **coordinates**, so a future Android map widget can place a tea's region.
- **G3** — the scraper maps a vendor's region text to a canonical region *through review*, never by
  inventing or guessing; unknown region text queues for a human, exactly like cross-script tea identity
  (#136) and the unknown-`type` mapping queue (SCR-P1-2).

**Non-goals (deferred):** the Android map widget itself; region polygons/geoshapes; a country reference
table (ISO + a static name resource is enough — countries don't need a QID); historical region boundaries.

## 2. Dataset choice (decision #138)

| Axis | Choice | Why |
|---|---|---|
| Country | **ISO 3166-1 alpha-2** | Already the de-facto value; a fixed closed list; trivial centroid/flag. |
| Region | **Wikidata QID** | Reuses the existing Wikidata client/SPARQL/upsert stack; CC0 (cache + ship freely); each region has ru/en/zh labels + coordinates (P625) + country (P17) + parent (P131). |

Rejected for region: **GeoNames** (CC BY → attribution + self-hosted dump + a new pipeline) and
**ISO 3166-2 subdivisions** (province/state only — too coarse for Wuyi / Darjeeling / West Lake / a
specific mountain or island). A QID can later resolve to an OSM relation / geoshape if polygons are wanted.

## 3. Target model

### 3.1 Country
- Keep `tea.origin_country TEXT` but constrain it to ISO 3166-1 alpha-2 (CHECK against a committed code
  list, or validate in the importer/FactsOnlyGuard + a small lookup).
- A committed resource `iso-3166-1.json`: `{ code, name_en, name_ru, name_zh, flag?, lat?, lon? }` for
  display + a fallback map centroid. ~249 rows, static.

### 3.2 Region (new)
```text
region                          (cached canonical region, sourced from Wikidata; operator/curated)
  qid            TEXT PK        -- 'Q204661'
  name_en/ru/zh  TEXT           -- cached Wikidata labels (the app's locales)
  latitude       NUMERIC        -- P625
  longitude      NUMERIC        -- P625
  country_iso    TEXT           -- P17 -> ISO 3166-1 alpha-2 (cross-checked vs the tea's origin_country)
  parent_qid     TEXT NULL      -- P131 located-in (province/prefecture), for rollup/breadcrumbs
  source         TEXT           -- 'wikidata'
  license        TEXT           -- 'CC0'
  fetched_at     TIMESTAMPTZ

tea
  region_qid     TEXT NULL  REFERENCES region(qid)   -- canonical region (new)
  origin_country TEXT (ISO 3166-1 alpha-2, validated) -- unchanged column, now constrained
  region         TEXT  -- KEPT as a display fallback until a qid is resolved (open question Q1)
```
- `region` rows are populated/cached by the existing **Wikidata SPARQL client** (operator-run, off the
  request path), one SPARQL query per QID: labels (ru/en/zh), `wdt:P625` coords, `wdt:P17` country,
  `wdt:P131` parent. CC0 → cache + ship.
- `tea.region_qid` is the canonical region; the display label is `region.name_<locale>` with a fallback
  chain to the legacy `tea.region` text for not-yet-resolved rows.

## 4. Scrape integration (mirrors #137-C5/C6)

- Vendor region text remains an **observation fact** on the source revision (`ScrapedFacts.region`) — never
  promoted to a canonical region automatically.
- Resolving observation text → a region QID is a **review step**, parallel to cross-script tea identity:
  - a curated **`region_alias (alias_text_norm → qid)`** table (e.g. `Дарджилинг`/`Darjeeling` → Q204661),
  - normalized with the same `lower(f_unaccent(...))` invariant used elsewhere,
  - an exact/curated hit proposes the region; an unknown text enters a **region mapping queue** for the
    operator (who confirms an existing region QID or fetches a new one from Wikidata).
- The #137-C6 region field-claim then carries the resolved `region_qid` (with the vendor text retained as
  the claim's evidence), instead of a free-text scalar. Country validates against ISO at the same gate;
  an unknown country code queues rather than minting a bad value (extends SCR-P1-2's "no silent OTHER").

## 5. Map widget (future, Android — out of scope)

- Reads `region.latitude/longitude` (a pin) and optionally a geoshape derived from the QID (an area).
- **Privacy constraint (mirrors AND-P2-2):** fetching map tiles from a third party (OSM/Mapbox/Google)
  leaks the device IP and the user's interest to that host, contradicting the local-first/no-account
  stance. Prefer a **bundled/offline** basemap or a **first-party** tile/vector source, or disclose the
  egress in the data-flow table. Decide when the widget is built.

## 6. Open questions
- **Q1 (legacy `tea.region`):** keep the free-text column as a display fallback until every row has a
  `region_qid`, or drop it once backfilled? (Lean: keep as fallback through the migration window.)
- **Q2 (granularity):** store the most specific place (e.g. *Anji*) and roll up to province via `parent_qid`,
  or store the province? (Lean: most specific + rollup — the map wants the precise pin.)
- **Q3 (country QID):** countries stay ISO-only (no QID). Confirm we never need a country QID (ISO →
  name/centroid covers display + a country-level map).
- **Q4 (Wikidata freshness):** how often to re-fetch region labels/coords; cache is fine for a slow-moving
  gazetteer, refresh on demand.
- **Q5 (offline basemap):** which bundled/first-party map source satisfies Q5's privacy constraint at an
  acceptable APK size.

## 7. Implementation sketch (later PR, not now)
1. Flyway `region` table + `tea.region_qid` FK + ISO 3166-1 country CHECK/validation; `region_alias` table.
2. Commit `iso-3166-1.json` (ru/en/zh + centroid) and a Wikidata region-fetch query in the existing client.
3. Backfill: resolve the ~40 seed regions to QIDs (operator pass), populate `region`, set seed `region_qid`.
4. Importer: country ISO validation + region text → QID via `region_alias` + the mapping queue.
5. (Future) Android: `CatalogRef` carries `regionQid` + cached label/coords; the map widget.
