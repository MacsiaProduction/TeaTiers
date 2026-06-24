# TeaTiers current design and architecture review - 2026-06-18

Scope reviewed:
- `task.md`, `architecture.md`, `context/plan.md`, `context/decisions.md`
- prior review files under `context/review/`
- focused plans under `context/shared-teas/`, `context/photos/`, `context/polish/`
- research runs under `research/`
- Android app, backend, infra, CI, and backup implementation
- external task notes under `/Users/macsia/context/git/TeaTiers`

`task.md` and `architecture.md` are legacy sketches. Current authority is
`context/plan.md` plus the append-only decision log in `context/decisions.md`.

## Current-state corrections

Several findings from the earlier `2026-06-17` full review are now resolved and
should not be carried forward as active blockers:

- Typo-tolerant catalog search is built and live: Postgres `pg_trgm`,
  `f_unaccent`, `name_norm`, `word_similarity`, and the live VM deployment are
  recorded in decisions #84 and #91.
- The add flow now has optional `sourceText` grounding and forwards it through
  `AddTeaViewModel.submit()`; see decision #87.
- Catalog detail now renders the server `images` list, not only the first image;
  see decision #89.
- User-tea detail now shows "my rating" and catalog reference flavor side by
  side; see decision #90.
- Queued enrichment copy is intentionally honest app-open retry copy, not a
  background WorkManager promise; see decision #92.
- Curated seed is now 100 teas, not the older 13-row seed; see decisions #93
  and #95.

The architecture direction remains coherent: local-first Android app, no
accounts, backend as a catalog/enrichment service, Postgres as the catalog
database, no open-ended web crawling, no arbitrary web images, and no GMS-only
MVP dependency.

## Findings

### P0 - Global privacy copy is wrong after `sourceText` shipped

Evidence:
- `settings_about_privacy` says only the typed tea name is sent and "nothing
  else": `app/src/main/res/values/strings.xml:74`.
- Add mode exposes a pasted description field and says that text is sent to the
  catalog service: `app/src/main/kotlin/com/macsia/teatiers/ui/board/AddTeaScreen.kt:325`.
- Submit forwards `form.sourceText.trim().ifBlank { null }` into enrichment:
  `app/src/main/kotlin/com/macsia/teatiers/viewmodel/AddTeaViewModel.kt:326`.
- `context/plan.md` already states the intended truth: typed name and any
  pasted `sourceText` may be sent, while notes/photos/ratings/locations stay
  local: `context/plan.md:469`.

Impact:
The field-specific hint is honest, but the global About/privacy text is now too
absolute. This is a trust and compliance bug because a user can read Settings,
believe only a name is ever sent, then paste vendor/package prose that is sent
to the backend when enrichment runs.

Recommendation:
- Rewrite the global privacy copy to say: boards, ratings, notes, photos, and
  purchase places stay on-device; catalog search/clarification sends the typed
  tea name, and if the user fills the optional description field, that text is
  sent for clarification and is not stored raw in the shared catalog.
- Keep it AI-neutral while production LLM remains off, but do not say "only the
  name" anymore.
- Add a small string test or screenshot-review checklist item so future
  networking fields cannot drift from Settings copy.

Ready solution:
No dependency needed. This is a copy + review-gate fix.

### P0 - Room destructive migration remains the first-public-release blocker

Evidence:
- Room DB is version 6 with `exportSchema = false`:
  `app/src/main/kotlin/com/macsia/teatiers/data/db/TeaDatabase.kt:36`.
- The app still uses `fallbackToDestructiveMigration(dropAllTables = true)`:
  `app/src/main/kotlin/com/macsia/teatiers/di/AppModule.kt:45`.
- The MVP release gate still marks Room migrations as open:
  `context/plan.md:436`.

Impact:
This is acceptable for internal pre-launch builds, but not for the first public
APK. A missed migration after public release can delete the user's boards,
teas, notes, photos, purchases, flavor ratings, catalog links, and enrichment
state.

Recommendation:
- Declare the first public schema version.
- Enable Room schema export with a committed baseline JSON.
- Remove broad destructive fallback from release builds before public shipping.
- Add migration tests for each future version bump.
- If useful, keep destructive fallback only in debug/internal builds.

Ready solution:
Use standard Room `Migration` objects plus Room schema export and migration
test helpers. No new product architecture is needed.

### P1 - Dependency/security scanning is still an explicit release-gate gap

Evidence:
- The release checklist still has dependency/security check open:
  `context/plan.md:451`.
- CI runs `./gradlew --no-daemon check` for server and app, plus app
  `assembleDebug`: `.github/workflows/ci.yml:29` and `.github/workflows/ci.yml:46`.
- Neither `app/build.gradle.kts` nor `server/build.gradle.kts` wires an advisory
  scanner or dependency audit task.

Impact:
The repo has pinned versions and CI tests, but no automated dependency advisory
gate. That leaves supply-chain/security regressions dependent on manual review.

Recommendation:
- Pick one release-gate scanner and wire it into CI before public APK/server
  release, or write an explicit time-boxed deferral.
- Keep Gradle dependency updates separate from advisory failure. Advisory gates
  should fail for known vulnerable artifacts; update bots can remain advisory.

Ready open-source options:
- OWASP Dependency-Check Gradle plugin.
- OSV-Scanner in CI against Gradle lock/dependency output.
- Dependabot/Renovate for update PRs, paired with one of the scanners above.

### P1 - Backup import/export loads full photo payloads into memory

Evidence:
- Backup import reads `backup.json` and every photo entry via `zip.readBytes()`:
  `app/src/main/kotlin/com/macsia/teatiers/data/backup/BackupArchive.kt:47`.
- `BackupManager.importFrom()` then stores all parsed photo bytes in memory
  before writing them to `PhotoStore`: `app/src/main/kotlin/com/macsia/teatiers/data/backup/BackupManager.kt:87`.
- Export also builds a full `photoBytes` map with `file.readBytes()` before
  writing the zip: `app/src/main/kotlin/com/macsia/teatiers/data/backup/BackupManager.kt:119`.
- `PhotoStore.importInto()` writes to an app-private UUID filename, so classic
  zip-slip is not the main problem: `app/src/main/kotlin/com/macsia/teatiers/data/photos/AndroidPhotoStore.kt:66`.

Impact:
A user-picked huge or malicious backup can cause high memory pressure or OOM.
Normal TeaTiers backups are probably small, but photos are the one data type
that can grow unexpectedly.

Recommendation:
- Add maximums: JSON entry bytes, total archive bytes, per-photo bytes,
  photo count, and accepted extensions/MIME classes.
- Stream export/import photo bytes instead of collecting all photos in a
  `Map<String, ByteArray>`.
- Keep destructive replace-all import, but validate the archive limits before
  any DB replacement.

Ready solution:
No heavy dependency needed. Use bounded copy helpers around `ZipInputStream`
and stream each accepted photo directly into `PhotoStore`.

### P1 - Public deploy hardening still needs a small boundary pass

Evidence:
- SSH ingress is open to the world in the Yandex security group:
  `infra/security_group.tf:7`.
- `/resolve` rate limiting keys off `X-Forwarded-For` first:
  `server/src/main/kotlin/com/macsia/teatiers/controller/TeaController.kt:60`.
- The code comment assumes the reverse proxy owns that header; direct backend
  ingress is currently meant to stay closed.

Impact:
The current shape is acceptable for a small key-only VM if 8080 is truly closed
and Caddy controls the forwarded headers. For a public release, this should be
made explicit so later infra edits do not accidentally make rate limiting
spoofable or SSH broader than intended.

Recommendation:
- Restrict SSH to known admin CIDRs or require an explicit waiver.
- Ensure Caddy strips/sets `X-Forwarded-For` and the backend is not reachable
  except through Caddy.
- Add a deploy checklist item that verifies 8080 is closed externally and
  health/API traffic only enters over 443.

Ready solution:
No app dependency needed. This is Terraform/Caddy hardening plus a release
check.

### P2 - Offline catalog cache search is substring-only

Evidence:
- Network search uses the live fuzzy backend path.
- On network failure, `DefaultCatalogRepository.cachedOr()` calls the local
  cache with `query.lowercase()`: `app/src/main/kotlin/com/macsia/teatiers/data/repository/CatalogRepository.kt:136`.
- `CatalogDao.search()` is a simple `LIKE '%query%'` over `searchText`:
  `app/src/main/kotlin/com/macsia/teatiers/data/db/CatalogCacheEntity.kt:39`.

Impact:
The original "several wrong symbols" requirement is satisfied by the online
catalog, but offline fallback suggestions do not share the same typo tolerance.
That is fine for MVP if documented, but it can surprise users after they have
seen fuzzy search work online.

Recommendation:
- Keep as-is for MVP unless offline search parity becomes a product promise.
- If parity becomes important, prefer a small on-device search layer over
  adding a service: normalized tokens, SQLite FTS where available, or a compact
  trigram index over cached names.

Ready solution:
Start with normalized cached search text and targeted app tests. Do not add
Meilisearch or another always-on component for on-device fallback.

### P2 - Curated seed is much better, but still below the target catalog feel

Evidence:
- The original target remains a curated roughly 300-tea seed:
  `context/plan.md:499`.
- The seed is now 100 teas, with the next stage explicitly called out:
  `context/decisions.md:1694`.
- `catalog-seed.json` currently reports `version: 3` and `jq '.teas | length'`
  returns `100`.

Impact:
100 teas is enough to stop the catalog from feeling empty, but the first-run
experience will still depend heavily on the user's tea habits. Long-tail
Wikidata and enrichment are useful, but curated verified data is what gives
search, flavor, attribution, and screenshots reliable quality.

Recommendation:
- Continue the staged seed expansion: 100 -> 150 -> 300.
- Reuse the same set as search gold queries, flavor backfill evaluation,
  transliteration regression cases, and UI screenshot content.
- Keep the current provenance rule: own-worded descriptions, verified rows,
  no commercial/community DB imports.

### P2 - Source-of-truth docs have some stale lower sections

Evidence:
- `context/plan.md` release gate correctly says pg_trgm is deployed and live:
  `context/plan.md:440`.
- The research table lower down still says run 09 implementation is queued:
  `context/plan.md:507`.
- The deploy section still talks about a planned YCR -> GHCR move, while
  decisions #83 and #91 record the GHCR cutover and live deployment:
  `context/plan.md:490`, `context/decisions.md:1523`, `context/decisions.md:1649`.

Impact:
Future agents or contributors can re-open already closed work or mis-rank
risks if they read only the stale sections.

Recommendation:
- Keep `context/decisions.md` append-only.
- Refresh the stale summary rows in `context/plan.md` so they point to the
  latest decision numbers.
- Add a short "superseded by decisions #..." note rather than rewriting old
  historical reasoning.

## Open-source reuse / avoid

Keep using these choices:
- Postgres `pg_trgm` for server typo search. Research run 09 picked it over
  Meilisearch for MVP, and the implementation is now live. Meilisearch CE stays
  only as a future fallback if a gold set fails.
- Coil 3 + OkHttp for catalog/user image loading. It fits the existing app and
  does not need another image stack.
- Room + DataStore + Retrofit/Hilt. The app stack is coherent; the issue is
  migration hardening, not architecture.
- Testcontainers Postgres for backend integration tests. It already catches the
  `pg_trgm`/locale contract that matters in production.

Reuse only if the product promise changes:
- AndroidX WorkManager. It is the right open-source solution for durable
  process/reboot/connectivity retry, but decision #92 intentionally chose
  honest app-open retry for MVP.
- SQLite FTS or a small cached trigram index for offline catalog search. This
  is only needed if offline typo tolerance becomes a promise.

Use for future OCR grounding:
- Research run 10 points to on-device RapidOCR / PaddleOCR behind an `OcrEngine`
  interface for ru/en packaging text. Avoid ML Kit for the RU-first path, avoid
  Surya licensing, and avoid server-side photo upload for MVP privacy/ops.

Use for future flavor backfill:
- Research run 11 says not to build a Python model for MVP. Reuse the existing
  Yandex LLM enrichment tier as a batch job plus a deterministic heuristic
  prior table, with per-dimension provenance and evaluation gates.

Avoid:
- Open-ended web crawling/search grounding for catalog rows. Research run 08
  and the later decisions keep the compliant path as Wikidata/Wikipedia,
  curated data, and user-provided `sourceText`.
- GMS-only dependencies for MVP.
- New always-on services unless they remove a measured bottleneck.

## Research follow-ups

No new broad research run is needed for the items above. Existing runs already
answer the major open-source reuse questions:
- run 09: typo search
- run 10: photo OCR grounding
- run 11: flavor backfill
- run 12: batch enrichment

Useful future research, only when implementation is about to start:
- OCR engine implementation verification: exact RapidOCR/PaddleOCR Android
  artifacts, model licenses, model sizes, ru/en accuracy, and packaging impact.
- Dependency scanning choice: OWASP Dependency-Check vs OSV-Scanner for this
  split Gradle repo and GitHub Actions setup.

## Suggested next slices

1. Fix `settings_about_privacy` to include optional `sourceText`.
2. Convert Room from destructive pre-launch mode to a public-schema migration
   baseline.
3. Add a dependency/security advisory gate or document a dated waiver.
4. Add backup archive size limits and streaming photo import/export.
5. Tighten deploy boundary checks around SSH, Caddy headers, and closed 8080.
6. Refresh stale `context/plan.md` summary rows for run 09 and GHCR/YCR.
