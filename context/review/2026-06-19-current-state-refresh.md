# TeaTiers current-state architecture refresh - 2026-06-19

Scope reviewed:
- Source-of-truth docs: `task.md`, `architecture.md`, `context/plan.md`, `context/decisions.md` through #117.
- Focused plans: `context/shared-teas/plan.md`, `context/photos/plan.md`, `context/polish/plan.md`, `context/flavor-system/prompts.md`.
- Prior review: `context/review/2026-06-19-full-architecture-design-review.md`.
- Current implementation slices in `app/`, `server/`, `ocr-sidecar/`, `infra/`, and `.github/workflows/`.
- Research ratings 15 and 16, because they directly affect the remaining release architecture.

This is a **post-review-program refresh**. The large `2026-06-19-full-architecture-design-review.md`
is still the broadest design critique, but decision #117 says its main actionable fixes were implemented
and deployed. This file records the current state after that fix wave, so old findings do not keep getting
carried forward as if still open.

## Summary

The architecture is still the right one: local-first Android app, small catalog backend, PostgreSQL,
single Yandex VM, OCR sidecar as an opt-in assist, AI tier off for MVP, and catalog growth driven by
curated seed plus no-PII demand signals. The review program materially improved the system: Wikidata
resolve is live again, typo search is live, OCR is bounded and isolated, Caddy is hardened, backups are
atomic/off-box, app photo/enrichment races were fixed, and the catalog miss log is live.

The remaining blockers are **release discipline**, not a redesign:

1. Room still destroys local data on schema mismatch and exports no schema. This is the only hard
   public-release blocker.
2. The planned ACRA/client-diagnostics + Room wipe sentinel is not built yet, so silent local data loss
   would still be invisible.
3. English is offered in Android locale config before `values-en` exists.
4. The new miss-log growth engine needs an operator workflow, not just a DB table.
5. Supply-chain provenance is good for dependencies but still weak for the published images.
6. The dormant AI tier still has enable-day gaps: retry/budget accounting, graceful drain, and stale
   `PENDING` recovery.

## Resolved Since The Full Review

Do **not** carry these forward as open findings:

- **Free Wikidata resolve is working in prod.** Decision #115 fixed the URI-encoding bug that turned every
  cache-miss lookup into a 500; live non-seeded teas now resolve through Wikidata.
- **Catalog breadth now has an implementation path.** Research run 16 was judged and decision #116 locked
  the correct strategy: famous-tea seed + custom-add/OCR as first-class flow + no-PII seed-from-misses.
  Decision #117 implemented the `catalog_miss` table and live miss logging.
- **The main app data-integrity findings were fixed.** Current code has a transactional enrichment merge
  (`TeaDao.applyEnrichmentPatch`), `PhotoStore.reconcile`, app-open/import photo sweeps, edit-mode `observeTea`,
  and defensive saved-back-stack decode.
- **The main OCR/infra findings were fixed.** Current sidecar has pixel-budget checks, conditional low-res
  upscale, inference deadline plus worker recycle, content-length/byte caps, and private network isolation.
  Caddy now has body cap, HSTS/nosniff/referrer headers, hidden `Server`, and upstream timeouts.
- **The IaC/deploy drift found in the full review was fixed.** `OCR_SIDECAR_IMAGE` is threaded through
  cloud-init variables; `backup.sh` writes `.partial` and atomically renames; CI runs `tofu init
  -lockfile=readonly`.
- **Search abuse was bounded.** `/teas/search` now has size-capped params and a generous `searchRateLimiter`.

## Current Problems And Improvements

### P0 - Public release is still blocked by Room destructive migration

Evidence:
- `TeaDatabase` is still version 6 with `exportSchema = false` (`app/src/main/.../TeaDatabase.kt:22-37`).
- The Hilt Room builder still calls `fallbackToDestructiveMigration(dropAllTables = true)`
  (`app/src/main/.../AppModule.kt:41-48`).
- The release gate keeps this open (`context/plan.md:465-466`).

This is acceptable only for internal builds. A first public APK with this state risks silent full local
data loss on a future schema mistake, and local-first means the backend cannot reconstruct a user's boards,
notes, photos, ratings, or purchases.

Recommended next step:
- Flip `exportSchema = true`, commit the current schema JSON as the public baseline, remove destructive
  fallback for release builds, add real migrations from that baseline forward, and add migration tests.
- Build the diagnostics sentinel from run 15 in the same slice, because the sentinel's main job is catching
  a bad migration/data-loss event.

### P1 - Diagnostics and Room wipe sentinel are decided but not implemented

Evidence:
- Plan section 8 decides ACRA + first-party `/api/v1/client-diagnostics` with a DataStore row-count sentinel
  (`context/plan.md:512-523`).
- There is no `client-diagnostics` endpoint, no ACRA dependency, and no sentinel state in the app yet.

This is the right open-source reuse point: ACRA is a small GMS-free library and does not require adding a
heavy dashboard service to the 4 GB VM. The key architectural piece is not crash collection itself; it is the
out-of-Room before/after row-count sentinel that can report silent destructive migrations.

Recommended next step:
- Add an opt-in diagnostics setting, strict report allowlist, first-party Spring receiver, retention policy,
  and a CI GMS-dependency gate.
- Store only app/build/device metadata, stack traces, and numeric local row counts. Never send names, notes,
  photo paths, purchase text, coords, or raw OCR/source text.

### P1 - English is advertised before English resources exist

Evidence:
- `locales_config.xml` offers `ru` and `en` (`app/src/main/res/xml/locales_config.xml:5-8`).
- The repo has only `res/values/strings.xml`; no `values-en` exists.
- The release gate still requires either `values-en`/`values-zh` or explicit ru-only picker copy
  (`context/plan.md:487`).

This is a small but visible trust bug: Android can offer English even though the UI mostly remains Russian.
Decision #94 correctly removed Chinese UI, but English is still half-exposed.

Recommended next step:
- Either ship a complete `values-en` before public release, or remove `en` from locale config and the picker
  until it is real. Do not ship a partial locale.

### P1 - No public privacy/legal release artifact yet

The in-app Settings copy is now much more honest, but a public RuStore/sideload release still needs an actual
privacy policy and release note covering:

- local-first storage and manual export/import;
- catalog search and `/resolve` sending typed tea names;
- optional `sourceText` and packaging scan photo upload for OCR;
- transient OCR processing and deletion;
- no accounts, no server-side user identifiers by design;
- diagnostics opt-in if ACRA is added;
- retention for miss logs, diagnostics, and server logs.

This is not a code library decision. Treat it as a release-gate artifact and keep it versioned under `context/`
or a public docs page before distribution.

### P2 - Miss-log growth engine exists, but operator workflow is still too manual

Evidence:
- `MissLogService.topMisses()` exists (`server/src/main/.../MissLogService.kt:31-34`).
- The migration explicitly says operator review is SQL on the VM and no public endpoint exists
  (`server/src/main/resources/db/migration/V5__catalog_miss.sql:6-10`).
- `/resolve` writes misses best-effort (`server/src/main/.../ResolveService.kt:66-78`).

The storage design is correct: no IP, no session, no timestamp finer than date. The missing part is operational
friction. If review requires SSHing into Postgres and remembering the query, the feedback loop will decay and
catalog breadth will stall.

Recommended next step:
- Add a tiny operator script or Make target, not a public admin service yet. Example shape:
  `scripts/catalog-misses.sh top 100` -> CSV/Markdown; `scripts/catalog-misses.sh since 2026-06-19`.
- Add a one-page curation runbook: how to classify a top miss, how to promote into `catalog-seed.json`, how
  to verify provenance/license, and when to drop spam/gibberish.
- Add a retention policy or decay rule after observing traffic. Current forever-storage is probably fine for
  launch, but the policy should be explicit.

### P2 - Image publishing lacks provenance and deploy-by-digest discipline

Evidence:
- Server and OCR workflows push `:latest` plus a short SHA tag, but no signing or provenance attestation
  (`.github/workflows/publish-image.yml:52-57`, `.github/workflows/ocr-sidecar.yml:79-83`).
- Prod compose consumes image refs via `${SERVER_IMAGE}` and `${OCR_SIDECAR_IMAGE}`; the live convention is
  still mutable `:latest` unless the operator pins a tag/digest (`infra/deploy/docker-compose.prod.yml:46-48,
  127-129`).

Dependency scanning is now good: CycloneDX + OSV covers server, app, and sidecar deps. The missing open-source
reuse is at the artifact layer.

Recommended next step:
- Add keyless Sigstore/cosign signing and GitHub provenance attestations for both images.
- Deploy by immutable digest or at least the commit SHA tag, not `latest`.
- Consider Dependabot for Gradle, pip, Docker, and GitHub Actions so drift is visible before advisory scans fail.

### P2 - AI-enable-day reliability gaps remain, but they are dormant while AI is off

Evidence:
- `FoundationModelsClient.chatJson()` still retries any `RestClientException`, including likely permanent 4xx,
  with fixed sleep and no jitter (`server/src/main/.../FoundationModelsClient.kt:66-76`).
- The daily LLM budget is acquired once before dispatch (`server/src/main/.../ResolveService.kt:59-63` and
  `82-90`), while the client can attempt `props.maxAttempts` model calls.
- The async executor has no graceful drain configuration (`server/src/main/.../AsyncConfig.kt:20-28`).
- V2 created a partial `PENDING` index for restart recovery (`server/src/main/resources/db/migration/V2__enrichment_state.sql:13-14`),
  but no scheduled/startup sweep currently consumes it.

This is not a current MVP bug because decisions #88/#100 keep the LLM tier off in production. It becomes a
release blocker the day a real `TEATIERS_LLM_API_KEY` is deployed.

Recommended next step before AI-on:
- Run reserved research 14 immediately before enabling async Yandex APIs.
- Replace broad fixed retries with transient-only 5xx/timeout retry plus jitter, or a small Spring Retry/
  resilience wrapper.
- Count budget per model attempt or make the cap intentionally attempt-based.
- Enable graceful shutdown drain and a stale-`PENDING` TTL sweep that marks rows `FAILED`.

### P2 - API versioning and long-term service fallback are still policy-only

The app points at `https://tea.macsia.fun/api/v1/` by default, and the server currently has one public API version.
Plan section 11 already flags API versioning, but no concrete contract exists for old APKs once public distribution starts.

Recommended next step:
- Send app version/platform headers on every catalog call.
- Add a backward-compatible `minSupportedAppVersion`/`upgradeAvailable` response path or a tiny config endpoint.
- Keep `/api/v1` additive-only until a `/api/v2` exists.
- Bundle a compact read-only seed catalog in the APK as a last-resort fallback if the server disappears after the
  grant/budget window.

## Design Assessment

### What To Keep

- **Local-first/no-account spine.** It remains the strongest architecture decision. It deletes auth, sync, and
  per-user backend storage, and it fits the privacy posture of a tea journal.
- **Postgres `pg_trgm`, not a search service.** Typo search is now live and validated; Meilisearch/OpenSearch/
  Typesense would add RAM and sync cost without current need.
- **OCR sidecar, but bounded.** The current self-hosted RapidOCR path is imperfect but appropriately scoped:
  opt-in, review-before-use, no runtime egress, no stored image bytes, and isolated from DB/internet.
- **AI tier off for MVP.** Correct for cost, trust, and launch focus. The product should be sold as a strong
  offline tea tier-list with a curated reference catalog, not as an always-smart enrichment service.
- **Single VM.** Still correct while the workload is Caddy + Spring + Postgres + OCR. The boundary should stay
  explicit: no dashboard, no search engine, no queue worker, no AI batch service on this 4 GB host.

### What To Improve In The Idea

- Make **custom add + OCR + local flavor ratings** the hero path. The catalog is a famous-tea accelerator, not a
  promise of completeness.
- Turn the miss log into a weekly habit: top misses -> curate -> seed -> deploy -> repeat. This is now the only
  compliant growth loop.
- Treat export/import as a minimum safety net, not sufficient durability. After public launch, add recurring export
  nudges, and later consider user-owned cloud auto-export via SAF/Yandex Disk without accounts.
- Add first-run/onboarding before public release: backup expectation, catalog expectation, privacy disclosure before
  sourceText/OCR, and where to find export/import.

## Open-Source Reuse Recommendations

Use:
- **ACRA + first-party receiver** for diagnostics, because it is GMS-free and fits the existing backend.
- **Sigstore/cosign + GitHub provenance attestations** for published images.
- **Dependabot** for Gradle, pip, Docker, and Actions maintenance visibility.
- **Spring Retry or a very small resilience wrapper** only for outbound transient retries before AI-on.
- **JDK pooled RestClient request factory** if outbound traffic grows or AI is enabled; the current simple factory
  is tolerable for Wikidata/OCR scale but not an ideal long-term client pool.

Do not add now:
- Search service: `pg_trgm` meets the requirement.
- WorkManager: current copy honestly says queued enrichment retries on app open; a real background queue can wait.
- GlitchTip/Sentry self-host: too heavy for the 4 GB VM; ACRA receiver is the right MVP shape.
- New catalog data source: run 16 found no license-clean magic dataset beyond the already adopted open core.

## Research Follow-Ups

No new broad research run is warranted right now.

Already answered:
- Catalog breadth: run 16 judged and implemented into decision #116/#117.
- Crash telemetry: run 15 judged; next step is implementation, not more research.
- OCR sidecar: run 13 plus empirical reconsideration already picked the path.
- Typo search: run 09 is built and live.

Narrow future checks, not new research folders:
- **Run 14** only immediately before enabling the AI/background batch tier.
- **Rospatent GI/NMPT and EU eAmbrosia license check** if seed curation needs geographic-indication leads.
- **On-device OCR device-verification spike** only if privacy pressure justifies revisiting server-side OCR.
- **Real packaging OCR corpus** expansion as measurement, not architecture research.

## Suggested Next Sequence

1. Room public-schema cutover + migration tests + ACRA/client-diagnostics sentinel.
2. Resolve English locale exposure: complete `values-en` or remove English from the picker/config until ready.
3. Write privacy/legal release artifact and first-run/onboarding copy.
4. Add miss-log operator script/runbook; start weekly seed-from-misses workflow.
5. Add image signing/provenance and deploy by digest/SHA tag.
6. Prepare API versioning/app-version headers and bundled fallback seed before public APK.
7. Only then consider AI-on work: run 14, transient retry discipline, per-attempt budget accounting, graceful drain,
   and stale-`PENDING` sweep.

## External References Checked

- Android Room migration docs: https://developer.android.com/training/data-storage/room/migrating-db-versions
- ACRA setup/senders/privacy docs: https://www.acra.ch/docs/Setup / https://www.acra.ch/docs/Senders / https://www.acra.ch/docs/AdvancedUsage
- Spring RestClient/request factory docs: https://docs.spring.io/spring-framework/reference/integration/rest-clients.html
- Sigstore/cosign keyless signing: https://docs.sigstore.dev/cosign/signing/overview/
- GitHub Dependabot docs: https://docs.github.com/en/code-security/dependabot
