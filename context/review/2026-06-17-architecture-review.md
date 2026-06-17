# TeaTiers architecture review — 2026-06-17

Scope reviewed:
- `task.md`, `architecture.md`, `context/plan.md`, `context/decisions.md`
- `context/shared-teas/plan.md`, `context/photos/plan.md`, `context/polish/plan.md`
- `research/*/RATING.md`, `research/README.md`, `research/LEADERBOARD.md`
- Android app, backend, and infra implementation shape
- extra external context from `~/context/git/TeaTiers`

This is a design review, not a rewrite of locked decisions. Items are ordered by
release risk / architecture leverage.

## Executive summary

The core architecture is coherent for the project size:

- Local-first Android app with no accounts is the right privacy/cost trade-off.
- User-global local teas + per-board placements fixed the largest early modeling issue.
- Deferring geopoints/maps from MVP is still correct; purchase place text + URL covers the
  common tea-log workflow without location-permission risk.
- The backend as a catalog-only service keeps user data off the server and keeps the API
  small.
- "No open-ended web crawling" remains a good legal/operational boundary.
- Yandex Cloud VM + self-hosted Postgres is acceptable for hobby/MVP scale, as long as
  backups and release gates are tightened before public distribution.

The main problems are now narrower and actionable:

1. Typo-tolerant search is still missing in the live backend despite `pg_trgm` being present.
2. Queued enrichment should use a durable Android scheduler instead of only an app-scope
   coroutine.
3. Backup/export is no longer a faithful schema round-trip after the v5 enrichment columns.
4. Destructive Room migrations must stop before the first real user build.
5. `/resolve` has an intentional but risky contract drift: `sourceText` is accepted but
   ignored, and the client already models async statuses that the server cannot emit yet.
6. Catalog image support is still "one backend image" while the app has moved to a photo list.
7. The release plan needs a tighter "MVP release gate" list.

## Findings and improvements

### P0 — Search does not satisfy the product requirement

`task.md` now requires a ready search index that tolerates "several wrong symbols". The
server schema creates `pg_trgm` and `tea_name_trgm_idx`, but the live query in
`server/src/main/kotlin/com/macsia/teatiers/repository/TeaSearchRepositoryImpl.kt` is still
literal `LIKE '%q%'`.

Impact: `Longjng`, `Да Хун Пао` with a missed character, or pinyin tone/spacing mistakes
will miss. That hurts the app's first real workflow: adding a tea.

Recommendation:
- Implement the first version inside Postgres, not a new service.
- Use `unaccent`, `pg_trgm` `%` / `<%` or explicit `similarity` / `word_similarity`, and
  rank exact > prefix > substring > fuzzy.
- Add a small search-gold test set with ru/en/pinyin/zh inputs and deliberate typos.
- Keep Meilisearch/Typesense as fallback only if Postgres cannot pass the gold set.

Why: Postgres `pg_trgm` officially supports similarity functions/operators and GIN/GiST
indexes for fast similar-string search. Meilisearch and Typesense have stronger built-in
typo handling, but they add another always-on container and sync path on the small VM.

Ready open-source choices:
- First choice: PostgreSQL `pg_trgm` (already installed).
- Fallback candidates: Meilisearch, Typesense.
- Avoid OpenSearch for MVP: too much RAM/ops weight for a small catalog unless we already
  need its broader search platform.

Sources: PostgreSQL `pg_trgm` docs; Meilisearch typo tolerance docs; Typesense typo
tolerance docs.

### P0 — Backup schema drift loses enrichment data

Room v5 added `TeaEntity.catalogTeaId` and `TeaEntity.enrichmentState`, but
`app/src/main/kotlin/com/macsia/teatiers/data/backup/BackupModels.kt` omits both fields
from `BackupTea`. Import reconstructs `TeaEntity` through defaults, so a backup/restore
drops catalog linkage and enrichment state.

Impact: after restore, teas lose their shared-catalog identity and retry/done state. This
contradicts decision #49's "faithful 1:1 of the DB tables" backup claim.

Recommendation:
- Add `catalogTeaId` and `enrichmentState` to `BackupTea`.
- Keep `formatVersion = 1` only if defaults preserve old backups safely; otherwise bump to
  2 and keep a v1 reader.
- Add a regression test that exports a catalog-linked `DONE` tea and restores it unchanged.

### P0 — Destructive Room migrations are a release blocker

`AppModule.provideDatabase()` still uses `fallbackToDestructiveMigration(dropAllTables = true)`,
and `TeaDatabase.exportSchema = false`. This has been a valid pre-launch choice, but the app
now has export/import, photos, enrichment state, and user-global data. Data loss on upgrade is
no longer acceptable once an APK leaves internal testing.

Recommendation:
- Before any public/RuStore/direct APK release, enable `room.schemaLocation`, commit the v5
  schema baseline, remove broad destructive fallback, and add real migrations from the first
  public schema onward.
- If old internal builds can be sacrificed, declare a "public schema starts at v5" line in
  `decisions.md`, then protect every later bump with real migrations.

Android's Room docs explicitly call out preserving user data when schema changes and note
that automatic migrations depend on exported schemas.

### P1 — Background enrichment should use WorkManager

`TeaEnrichmentManager` runs work in an app-scope coroutine and resumes `PENDING`/`QUEUED`
only when the app launches/binds. This is simple and tested, but it is not durable Android
background work.

Impact: queued enrichment does not reliably run after process death, reboot, or a long time
away from the app. The UI says "queued", but the queue is effectively "try next app open".

Recommendation:
- Keep the current manager for immediate optimistic patching while the app is open.
- Add WorkManager for durable retry of `QUEUED`/`PENDING` rows with network constraints and
  exponential backoff.
- Use unique work keyed by tea id to avoid duplicate resolves.

Android docs position WorkManager for work that must run even if the app exits or the device
restarts; it persists scheduled work in its own SQLite DB and supports constraints/retry.

Ready open-source choice: AndroidX WorkManager.

### P1 — `/resolve` contract needs one decisive next shape

The app already models `ResolveResult.Enriching` and polls detail. The backend
`ResolveStatus` currently has only `MATCHED`, `ENRICHED`, and `UNRESOLVED`; `sourceText` is
validated but ignored by the controller/service.

This is fine for the Wikidata-only slice, but it must not become permanent ambiguity.

Recommendation:
- Decide whether the LLM tier is synchronous or asynchronous.
- If synchronous: return only `MATCHED` / `ENRICHED` / `UNRESOLVED`; remove client polling
  until needed.
- If asynchronous: add `ENRICHING` to the server DTO now, persist a pending catalog row, and
  make `GET /teas/{id}` expose a state the client can actually poll.
- Wire `sourceText` in the same slice as the UI field, with the run-07/run-08 injection and
  n-gram copy guards.
- Add the missing global daily LLM ceiling; per-IP rate limit alone is not enough for cost
  protection behind NAT/shared mobile networks.

### P1 — Catalog image model is behind the app photo model

The local app now supports a photo list per user-tea and has a `PhotoSource.CATALOG` hook.
The backend catalog schema still stores a single `tea.image_url` triple.

Impact: the original product note says tea pages should show a photo list "if we have it
from db or search". The app can display a list, but the catalog cannot provide one.

Recommendation:
- If catalog photos remain a future requirement, add a backend `tea_image` table with
  `position`, `source`, `license`, `source_url`, and stored object URL.
- Keep arbitrary web image fetching banned; only curated CC/Wikimedia images or user photos.
- Map the first catalog image to the card thumbnail, but let detail show the list.

### P1 — Local duplicate prevention needs a catalog-linked path

The user-global tea pool is correct. The current auto-link uses the first non-blank local
name field (`nameRu` then `nameZh` then `pinyin`). Once catalog integration is available,
`catalogTeaId` should become the strongest local identity key.

Recommendation:
- When a user picks a catalog tea, link by `catalogTeaId` first.
- Prevent adding a second local tea with the same non-null `catalogTeaId`.
- Keep name-based matching only as fallback for manual/offline additions.

### P1 — Release gate is not explicit enough

The plan has many milestones, but the actual "first public APK can ship when..." gate is
spread across decisions and open items.

Recommended release gate:
- Room destructive migrations removed for future public schema changes.
- Backup round-trip includes v5 enrichment fields and photos.
- Typo-tolerant catalog search implemented and tested.
- At least one durable queued-enrichment path using WorkManager or explicit "runs only when
  the app is open" copy.
- `/resolve` contract aligned with client expectations.
- Dependency/security check wired or explicitly deferred with a date.
- Production container registry choice settled (`ghcr.io` or YCR) and image pulls verified
  from the VM.
- Off-box Postgres backup enabled, or local-only backup accepted in writing.
- `values-en` / `values-zh` either shipped or language picker copy adjusted so Russian-only
  UI is not surprising.

### P2 — GHCR migration is a good infra simplification, with one caveat

`task.md` asks to move from Yandex image repository to open registries like GHCR. This is a
good simplification if the VM can pull from `ghcr.io` reliably.

Recommendation:
- Use GitHub Actions + `GITHUB_TOKEN` to publish `ghcr.io/<owner>/teatiers-server`.
- Prefer public image pulls to avoid storing a GH PAT on the VM. If private, store a
  read-only token in Lockbox.
- Pin deployment by digest, not `latest`.
- Verify `docker pull ghcr.io/...` from the Yandex VM before removing YCR.

GitHub's container registry supports Docker/OCI images, anonymous access to public images,
`GITHUB_TOKEN` publishing from workflows, and digest pulls.

### P2 — Backend backup is local-only

Decision #58 added local `pg_dump` backups under `/opt/teatiers/backups` with optional S3.
For a reproducible seed-only catalog this is tolerable, but `/resolve` now creates rows that
are not in the committed seed.

Recommendation:
- Enable off-box Object Storage backups before relying on user-driven catalog enrichment.
- Document restore rehearsal steps in `infra/README.md`.

### P2 — The catalog seed is too small for product feel

The live seed is 13 teas; plan #10 still wants a curated ~300-tea seed. Wikidata resolve helps,
but it only triggers after a user types a known exact label.

Recommendation:
- Prioritize either the 300-tea curated seed or fuzzy search before adding more AI surface.
- Use the curated seed to build the search-gold set.

### P2 — Keep some custom choices; do not over-replace with dependencies

Do not replace these yet:
- Custom Compose back stack: acceptable while the graph is small; revisit Navigation Compose
  only if deep links, nested graphs, or restore bugs appear.
- Hand-rolled board/photo drag: already tested and domain-specific; adding a reorder library
  now would not remove much complexity.
- Self-hosted Postgres: fine until backups/HA or memory pressure force Managed PostgreSQL.

## Suggested next sequence

1. Fix backup v5 fields.
2. Create the Room public-schema release gate: export schema + remove broad destructive
   fallback for future releases.
3. Run `research/09-typo-search/` and implement Postgres `pg_trgm` search unless the gold
   set proves it inadequate.
4. Decide `/resolve` sync vs async; wire sourceText + LLM tier and daily budget.
5. Move queued enrichment to WorkManager.
6. GHCR migration with VM pull verification.
7. Expand curated catalog seed and add off-box DB backups.

## Source notes

Local evidence:
- Current exact backend search: `server/src/main/kotlin/com/macsia/teatiers/repository/TeaSearchRepositoryImpl.kt`
- Existing `pg_trgm` index: `server/src/main/resources/db/migration/V1__catalog_schema.sql`
- App backup DTO omission: `app/src/main/kotlin/com/macsia/teatiers/data/backup/BackupModels.kt`
- Destructive Room builder: `app/src/main/kotlin/com/macsia/teatiers/di/AppModule.kt`
- Current enrichment manager: `app/src/main/kotlin/com/macsia/teatiers/data/repository/TeaEnrichmentManager.kt`
- `/resolve` DTO/service: `server/src/main/kotlin/com/macsia/teatiers/dto/ResolveDtos.kt`,
  `server/src/main/kotlin/com/macsia/teatiers/service/ResolveService.kt`

External references checked during this review:
- PostgreSQL `pg_trgm`: https://www.postgresql.org/docs/current/pgtrgm.html
- Meilisearch typo tolerance: https://www.meilisearch.com/docs/capabilities/full_text_search/relevancy/typo_tolerance_settings
- Typesense typo tolerance parameters: https://typesense.org/docs/29.0/api/search.html#typo-tolerance-parameters
- Android WorkManager persistent work: https://developer.android.com/develop/background-work/background-tasks/persistent
- Android Room migrations: https://developer.android.com/training/data-storage/room/migrating-db-versions
- GitHub Container Registry: https://docs.github.com/en/packages/working-with-a-github-packages-registry/working-with-the-container-registry
