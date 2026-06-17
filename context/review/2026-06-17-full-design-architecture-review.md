# TeaTiers full design and architecture review - 2026-06-17

Scope reviewed:
- `task.md`, `architecture.md`, `context/plan.md`, `context/decisions.md`
- existing review files under `context/review/`
- focused plans under `context/shared-teas/`, `context/photos/`, `context/polish/`
- research workflow and current research runs under `research/`
- Android app, backend, and infra implementation shape
- external notes under `/Users/macsia/context/git/TeaTiers`

This is a current-state review after decisions #70-#86. It supersedes the earlier
architecture-review notes where they mention now-resolved items.

## Executive summary

The product/architecture direction is still coherent:

- Keep the local-first Android app with no accounts. It is the right privacy, cost, and
  RuStore/no-GMS boundary.
- Keep user-global teas plus per-board placements. That is the correct local model for a
  personal tea collection.
- Keep maps/geopoints out of MVP. Free-text + marketplace URL already covers the common
  tea-log flow without location permission risk.
- Keep the backend as a catalog/enrichment service only. User notes, photos, boards,
  ratings, and purchase places should remain on-device.
- Keep "no open-ended web crawling" and "no arbitrary web images". The legal boundary is
  worth more than marginal catalog coverage.
- Keep Postgres `pg_trgm` as the first search solution. It is now built in V4; only deploy
  verification remains before treating the product requirement as live.

The remaining risks are release gates and a few M4/M5 product gaps, not a need to redesign
the whole system.

## Current status of previous blockers

- Privacy copy: fixed in decision #85. The app now says catalog search/name clarification
  sends the typed tea name to the TeaTiers catalog service.
- Typo-tolerant search: built in decision #84 via `pg_trgm`, `name_norm`, `word_similarity`,
  and `TeaSearchFuzzyIT`; not yet deployed to the live VM at the time of the decision.
- Backup v5 fields: fixed in #69.
- Failed/pending resolve hidden as local `DONE`: fixed in #78.
- `catalogTeaId` dedup: repository path fixed in #72 and DB unique index added in #78.
- GHCR migration: complete in #83; YCR retired.
- Off-box DB backup: deployed and restore-rehearsed in #82.
- Privacy wording no longer claims "nothing is sent".

## Findings

### P0 - Room destructive migration is still the public-release blocker

Evidence:
- `TeaDatabase` is version 6 with `exportSchema = false`:
  `app/src/main/kotlin/com/macsia/teatiers/data/db/TeaDatabase.kt:36`.
- The Room builder still calls `fallbackToDestructiveMigration(dropAllTables = true)`:
  `app/src/main/kotlin/com/macsia/teatiers/di/AppModule.kt:45`.
- The code comments correctly mark this as pre-launch only.

Impact: once a public APK exists, a missed migration can permanently delete local boards,
teas, photos, notes, purchases, placement order, backup metadata, and enrichment state.
Android's Room docs explicitly warn that destructive fallback permanently deletes user
data when no migration path exists.

Recommendation:
- Keep the current choice only for internal M4/M5 builds.
- Before first public/RuStore/direct APK, declare the public schema version, enable
  `room.schemaLocation`, commit the baseline JSON, remove broad destructive fallback from
  release builds, and add migration tests for every later bump.
- A debug/internal variant may keep destructive fallback if it speeds iteration.

Ready solution:
- Plain Room migrations and Room's schema export/migration test workflow. No new product
  dependency is needed.

### P1 - Queued enrichment is app-open retry, not durable background work

Evidence:
- `TeaEnrichmentManager.enrich()` launches on an app-scope coroutine:
  `app/src/main/kotlin/com/macsia/teatiers/data/repository/TeaEnrichmentManager.kt:51`.
- `resumePending()` re-dispatches only when app code runs:
  `TeaEnrichmentManager.kt:63`.
- `BoardsViewModel` and `BoardViewModel` call resume on app/home/board open, which is useful
  but not process/reboot durable.
- There is no WorkManager dependency in the app version catalog.

Impact: a tea marked `QUEUED` while offline will retry when the app is opened with network,
not because Android woke a durable job after connectivity returned. That is acceptable only
if release copy says so.

Recommendation:
- Preferred: add AndroidX WorkManager with a `CoroutineWorker`, network constraint, unique
  work per tea id, and exponential backoff. Keep `TeaEnrichmentManager` for immediate in-app
  optimistic updates.
- Minimal MVP: keep app-open retry and change copy from generic "queued" to "will retry when
  you open the app with network".

Ready solution:
- AndroidX WorkManager. Android docs position it for work that should continue after the app
  exits; scheduled work persists in WorkManager's SQLite DB and is rescheduled after reboot.

### P1 - The best long-tail grounding path exists in backend code but not in the add flow

Evidence:
- Server `/resolve` accepts `sourceText`:
  `server/src/main/kotlin/com/macsia/teatiers/controller/TeaController.kt:55`.
- `ResolveService` forwards it into the LLM enrichment dispatch:
  `server/src/main/kotlin/com/macsia/teatiers/service/ResolveService.kt:95`.
- `LlmEnrichmentService.profile()` switches to grounded prompt mode when `sourceText` is
  non-blank: `server/src/main/kotlin/com/macsia/teatiers/service/LlmEnrichmentService.kt:66`.
- App DTO/repository can send it, but `AddTeaForm` has no `sourceText` field and
  `AddTeaViewModel.submit()` calls `enrichmentManager.enrich(added.teaId, tea.nameRu)` with
  no pasted text: `app/src/main/kotlin/com/macsia/teatiers/viewmodel/AddTeaViewModel.kt:326`.

Impact: obscure teas fall back to name-only enrichment even though the user often has vendor
text, package text, or marketplace copy in hand. That is exactly where hallucination risk is
highest.

Recommendation:
- Add an optional "paste description" field in add mode, close to the manual-add/search-miss
  path, with a 4000-character counter.
- Send it through `AddTeaForm -> submit -> TeaEnrichmentManager.enrich(..., sourceText)`.
- Do not store raw vendor prose in the shared catalog. Treat it as untrusted copyrighted
  evidence, exactly as decisions #25/#44/#65 require.
- Update privacy copy when this UI ships: pasted text is sent for enrichment and not stored
  raw in the shared catalog.

### P1 - Catalog reference flavor and the user's own rating are still not shown together

Evidence:
- Catalog detail maps and shows reference flavors in `CatalogDetailSheet`:
  `app/src/main/kotlin/com/macsia/teatiers/ui/board/CatalogDetailSheet.kt:203`.
- User tea detail shows only `tea.flavor`:
  `app/src/main/kotlin/com/macsia/teatiers/ui/board/TeaDetailScreen.kt:212`.
- `TeaEnrichmentManager.applyPatch()` patches names/origin/blurb/catalog id but not flavor
  rows: `TeaEnrichmentManager.kt:130`.

Impact: decision #23 promised "catalog reference vs my rating". Today the app can show the
reference while browsing catalog detail and the user's rating on a local tea, but not both
on a catalog-linked user tea. The user cannot compare "catalog says smoky 4; I rated smoky 2".

Recommendation:
- Keep local `tea_flavors` as user ratings. Do not overwrite them with catalog reference data.
- Add either:
  - a small Room cache keyed by `catalogTeaId` for reference flavors, or
  - an on-demand catalog detail fetch on linked tea detail.
- In `TeaDetailScreen`, show "My rating" and "Catalog reference"; if the user has no rating,
  show reference as a suggestion with a one-tap copy into local ratings.

Ready solution:
- Reuse the existing `FlavorRadar` and `FlavorStrip`; no chart library is needed.

### P1 - Backend supports catalog image lists, app still consumes only the first image

Evidence:
- Server `TeaDetailDto` exposes both `image` and `images`:
  `server/src/main/kotlin/com/macsia/teatiers/dto/CatalogDtos.kt:65`.
- App DTO decodes only `image`, not `images`:
  `app/src/main/kotlin/com/macsia/teatiers/data/remote/dto/CatalogDtos.kt:78`.
- App domain has a single `CatalogTeaDetail.image`:
  `app/src/main/kotlin/com/macsia/teatiers/domain/model/CatalogTeaDetail.kt:18`.
- `CatalogDetailSheet` renders only `detail.image`:
  `app/src/main/kotlin/com/macsia/teatiers/ui/board/CatalogDetailSheet.kt:145`.

Impact: the backend schema is already shaped for a catalog photo list, but the app drops the
extra images. Not a release blocker, but it leaves product value unused and makes the photo
model inconsistent across user and catalog detail.

Recommendation:
- Add `images: List<CatalogImage>` to app DTO/domain mapping.
- Keep `image` as a first-image fallback for old server/client compatibility.
- Reuse `PhotoGallery` or a small read-only variant in `CatalogDetailSheet`.
- Continue banning arbitrary web image fetches; this should only show curated CC/Wikimedia
  images with attribution.

### P1 - LLM production enablement is a deployment/billing gate, not just code

Evidence:
- Server reads `YC_FOLDER_ID` and `TEATIERS_LLM_API_KEY` from env:
  `server/src/main/resources/application.yml:56`.
- Decision #82 recorded that the live VM had no `TEATIERS_LLM_API_KEY`, so the tier was off.
- Decision #86 records that moving Foundation Models billing to `cloud-summertime9875` is
  blocked by missing IAM rights. The macsia AI key remains dormant.

Impact: code for async enrichment exists, but production behavior may still be Wikidata-only
plus existing catalog rows. That is fine, but QA/release copy must not promise AI enrichment
until key injection, billing cloud, logging opt-out, daily cap, and failure paths are verified.

Recommendation:
- Make the release checklist explicit:
  - target billing folder selected;
  - service account has `ai.languageModels.user`;
  - `YC_FOLDER_ID` and `TEATIERS_LLM_API_KEY` injected from Lockbox;
  - `/resolve` miss returns `ENRICHING`;
  - row flips to `DONE` or `FAILED`;
  - daily cap fails closed;
  - Yandex request logging is verified off.
- If the key remains off for MVP, UI wording should say "catalog lookup/name clarification",
  not "AI enrichment".

### P2 - Seed catalog is still too small for first impression

Evidence:
- `server/src/main/resources/seed/catalog-seed.json` contains 13 teas.
- `context/plan.md` and decision #10 still target a curated roughly 300-tea seed.

Impact: `pg_trgm` and Wikidata resolve help, but first-run product feel depends on common
searches returning rich, verified suggestions immediately. Thirteen teas makes the catalog
look empty unless the user types one of the seeded teas or a Wikidata-resolvable label.

Recommendation:
- After deploying fuzzy search, expand curated seed in stages: 50, 100, then 300.
- Reuse that set as:
  - fuzzy-search gold queries;
  - flavor-profile evaluation;
  - transliteration regression cases;
  - UI screenshots/content QA.
- Keep provenance mandatory and continue avoiding commercial/community databases as imports.

### P2 - Language picker is wired before en/zh translations exist

Evidence:
- `locales_config.xml` offers `ru`, `en`, and `zh`:
  `app/src/main/res/xml/locales_config.xml:5`.
- Only `values/` and `values-night/` exist; there is no `values-en/` or `values-zh/`.
- Current copy states translations are coming:
  `app/src/main/res/values/strings.xml:55`.

Impact: this is honest enough for internal builds, but public release needs a clear choice:
ship translated UI resources or keep Russian-only explicitly.

Recommendation:
- For MVP release, either add `values-en` and `values-zh`, or keep the picker but label those
  languages as not ready / Russian-only UI.
- Tea names remain multilingual regardless of UI language.

### P2 - Research 10/11/12 are useful but not judged yet

Evidence:
- `research/10-photo-ocr-grounding/` has prompts and model answers, but `RATING.md` is still
  the template.
- `research/11-flavor-backfill/` has prompts and model answers, but `RATING.md` is still
  the template.
- `research/12-batch-enrichment/` has prompt + answers and no `RATING.md` yet.

Impact: the research artifacts are queued evidence, not decisions. They should not be treated
as accepted architecture until the ratings are filled and any version/API claims are verified
against official sources.

Recommendation:
- Judge runs 10 and 11 before implementing OCR or flavor backfill.
- Add and fill `research/12-batch-enrichment/RATING.md` before changing the Yandex async/batch
  architecture.
- Likely direction, pending rating:
  - OCR: prefer existing OCR engines, not a custom model. For no-GMS, evaluate bundled ML Kit
    carefully against Play Services assumptions, and compare Tesseract/PaddleOCR for RU/zh
    packaging text.
  - Flavor backfill: do not train a Python model first. Start with the existing LLM prompt as
    a batch/offline job plus a small human gold set; consider Python only after labeled data exists.
  - Batch enrichment: use async operations only after official Yandex API shape, structured
    output support, TTL, pricing, and logging behavior are confirmed.

## Keep these decisions

- Do not add accounts or server-side user storage for MVP.
- Do not bring maps/geopoints back into MVP.
- Do not add Meilisearch/Typesense now. `pg_trgm` is built and has tests; keep the dedicated
  engines as fallback only if production/gold-set search quality fails.
- Do not fetch arbitrary web images or crawl vendor pages.
- Do not write a custom OCR or flavor model before judging the research and building a gold set.
- Keep custom Compose navigation and drag code until a real failure appears; they are tested and
  fit the current app size.
- Keep self-hosted Postgres on the VM now that off-box backups are live; Managed PostgreSQL is an
  upgrade path, not an MVP need.

## Suggested next sequence

1. Deploy the server image that runs Flyway V4 and verify typo search on `tea.macsia.fun`.
2. Decide/implement the Room public-schema cutover before any public APK.
3. Choose WorkManager vs honest app-open retry copy.
4. Add the `sourceText` add-flow UI and privacy wording.
5. Add catalog-reference-vs-my-rating display.
6. Decode/render catalog `images`.
7. Decide LLM production billing/key deployment, or make the tier explicitly off for MVP.
8. Expand curated seed to at least 50, then 100.
9. Judge research 10, 11, and 12 before OCR/backfill/batch-enrichment implementation.

## Ready open-source / platform reuse notes

- **Search:** PostgreSQL `pg_trgm` is the right first solution. It provides `similarity`,
  `word_similarity`, `<%`, and GIN/GiST operator classes; current TeaTiers V4 matches that.
  Meilisearch and Typesense are valid fallback engines with built-in typo tolerance, but each
  adds a service and sync path.
- **Durable Android work:** AndroidX WorkManager is the standard Jetpack answer for
  persistent constrained work and retry/backoff.
- **Room migrations:** use Room's schema export and migration testing; destructive fallback is
  only acceptable when losing data is acceptable.
- **OCR candidates:** Tesseract and PaddleOCR are Apache-licensed open-source options; ML Kit
  has a bundled mode that avoids Play Services model download, but it is not open source and
  increases app size per script.
- **Flavor/backfill:** reuse the existing Yandex LLM prompt/evaluation harness before training
  any Python model. If Python becomes justified later, start with simple supervised regression
  on a curated labeled set, not fine-tuning.

## External source notes

- PostgreSQL `pg_trgm` docs: https://www.postgresql.org/docs/current/pgtrgm.html
- Android WorkManager persistent work: https://developer.android.com/develop/background-work/background-tasks/persistent
- Android Room migrations/destructive fallback: https://developer.android.com/training/data-storage/room/migrating-db-versions
- Meilisearch typo tolerance: https://www.meilisearch.com/docs/capabilities/full_text_search/relevancy/typo_tolerance_settings
- Typesense typo tolerance parameters: https://typesense.org/docs/30.2/api/search.html#typo-tolerance-parameters
- Tesseract OCR: https://github.com/tesseract-ocr/tesseract
- PaddleOCR: https://github.com/PaddlePaddle/PaddleOCR
- ML Kit text recognition: https://developers.google.com/ml-kit/vision/text-recognition/v2/android
