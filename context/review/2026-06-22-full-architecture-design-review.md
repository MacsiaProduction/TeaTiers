# TeaTiers full architecture and design review — 2026-06-22

Status: current-state review against `main` at `399ed92` (`harden(server): close scrape-foundation P0 C5/C6 + tombstone guard (#120)`).

Scope: product model, Android app, shared catalog/API, scrape-ingestion foundation, OCR, privacy, updater/release, infrastructure/operations, all prior review artifacts, all research runs, and the newly added GLM-4.7/MiniMax answers for runs 01–21. Historical `task.md` and `architecture.md` were used as intent, not treated as the live implementation.

## Executive verdict

The overall system boundary remains appropriate for the product: local-first personal data in Room, a small read-mostly shared catalog in PostgreSQL, no user accounts, a single Spring service, and operator-controlled enrichment/import. TeaTiers does not need microservices, an external search engine, Kafka, a vector database, or a general-purpose admin platform.

The current implementation is materially stronger than the June 21 baseline. Room migrations are no longer destructive, backup import is bounded and validated, the catalog has stable-public-ID/lifecycle schema, scrape observations are revisioned, canonical writes require review, OCR is isolated, and CI produces SBOM/provenance/signatures and scans dependencies.

It is not yet safe to start a real scraper, publish scraped canonical rows, or enable the custom updater. Five blockers dominate:

1. **Production catalog identity is not reproducible yet.** The live database is still pre-V7. Deploying V7 as written will assign random UUIDs to its existing rows; the seeder then skips those rows and never applies the committed frozen UUIDs.
2. **The import source/run gate is incomplete.** Source re-registration preserves old approval, allowed hosts are not administered or enforced, robots evidence can be stale/empty, and the run state machine permits apply before an explicit reviewed state.
3. **Lifecycle tombstones expose full content.** Retracted and broken-chain merged records still serialize names, descriptions, images, and provenance instead of a compact lifecycle-only tombstone.
4. **The updater lets the same remote server choose the APK and its trust data.** Hash and signer fields from an unsigned manifest do not establish an independent trust root.
5. **The Android personal-data model still conflates a canonical tea with a physical sample.** The unique numeric `catalogTeaId` prevents multiple lots/purchases of one tea and the client has not adopted UUID lifecycle fields.

Recommended sequence: identity-reconciliation migration and production rehearsal; close the import gates/state machine/tombstone; implement Room v7 sample/reference split and API UUID contract; harden release/signing/updater; then implement one fixture-backed local scraper pilot.

## Evidence and verification

- Read the current decision log, focused designs, plans, all review files, live app/server/infra code, migrations, tests, and every research folder.
- Reviewed all 42 newly added `glm-4-7.md` and `minimax.md` answers across runs 01–21. Their disposition is recorded below.
- Fresh Android verification: `./gradlew test lint --rerun-tasks` passed. Android lint reports 0 errors and 15 warnings.
- Fresh server verification: `./gradlew test --rerun-tasks` passed, including PostgreSQL/Testcontainers integration tests.
- OCR Python tests were not rerun locally because `pytest` is not installed in this environment; CI remains the current executable evidence.
- Public API read-only probes on 2026-06-22 showed `https://tea.macsia.fun/api/v1/teas/search?q=Longjing&limit=10` still returning the pre-public-ID shape and `/api/v1/teas/by-public-id/acba9bbe-3663-4f79-8054-dad1da9f7287` returning 404. This is direct evidence that production has not applied V7–V10.
- The external operator context was inspected. The release keystore itself was not read, but its filesystem mode is `0644`, confirming the existing signing-key exposure/recovery finding.

## What should remain unchanged

These choices are sound and should not be replaced without measured evidence:

- **Local-first/no-account product boundary.** Personal notes, ranks, photos, and purchases stay on-device. The server remains a shared reference catalog, not user storage.
- **One user tea pool plus board placements.** Cross-board reuse via placements is simpler and correct. The next change is to distinguish a canonical reference from a physical sample, not to duplicate teas per board.
- **PostgreSQL + `pg_trgm`.** It fits the current catalog scale and multilingual alias strategy. Do not add Meilisearch/Typesense/OpenSearch until a committed multilingual gold set demonstrates an unmet requirement.
- **Spring MVC monolith.** Current load and operator model do not justify service decomposition.
- **Staging/review/canonical separation.** `source_record` identity, immutable revisions, match decisions, and value-bearing claims are the correct foundation.
- **No automatic fuzzy merge.** All cross-script/fuzzy candidates remain human-reviewed until labeled evidence proves safe thresholds.
- **OCR process isolation.** A killable sidecar process with no runtime egress and container resource limits is a reasonable fit for the small VM.
- **Streaming backup archive.** The current bounded, validate-before-database replacement design is much stronger than ad hoc JSON/file copying.
- **First-party minimal diagnostics.** ACRA plus a narrow endpoint is preferable to operating Sentry/GlitchTip on this host.
- **Facts-only scraping policy.** Vendor prose and images do not belong in the public catalog merely because a parser can extract them.

## P0 blockers

### P0-1 — production UUID continuity is not actually closed

**Evidence**

- V7 assigns `public_id UUID NOT NULL DEFAULT gen_random_uuid()` to existing rows (`server/.../V7__scrape_source_and_public_id.sql:21-25`).
- The committed seed now contains frozen UUIDs and `CatalogSeeder` applies them only when inserting a missing `dedup_key` (`CatalogSeeder.kt:35-43, 78-80`). Existing production rows are skipped.
- The seeder integration test proves a blank database gets the frozen UUID (`CatalogSeederIT.kt:50-59`). It does not exercise a V6-like database with the existing 100 rows.
- Production is still pre-V7, so this failure window is current, not hypothetical.

**Failure mode**

First deploy of V7 assigns random UUID A to every production row and records numeric-id → A. A later rebuild from the committed seed assigns frozen UUID B. Installed clients that cached A are orphaned, while numeric compatibility can also drift if row insertion order changes. This violates decision #137-C1 even though the review index marks it done.

**Required correction**

1. Add a new reconciliation migration; do not silently edit an already-versioned migration.
2. Commit an explicit mapping artifact containing at least `dedup_key`, frozen `public_id`, and the legacy numeric ID for every already-shipped catalog row.
3. In the migration, reconcile each existing row to its frozen UUID while preserving/rebuilding the legacy map. Handle the `tea_legacy_id_map.public_id` FK in a safe order or make the identity update cascade explicitly.
4. Fail the migration if an existing row is missing, duplicated, or maps to a different semantic record. Do not generate another UUID as fallback.
5. Rehearse V6→current against a production-like backup, then verify representative numeric and UUID endpoints before deployment.
6. Add integration tests for: existing-row upgrade; blank rebuild; reordered seed; inserted seed row; legacy numeric lookup; UUID lookup; merged and retracted lifecycle.

**Acceptance criterion:** the same semantic seed row has exactly the same public UUID after production upgrade, clean rebuild, restore, and seed reorder; every previously returned numeric ID resolves to that UUID.

### P0-2 — reopen decision #137-C4: source, robots, host and run gates are incomplete

The existing implementation enforces several useful invariants, but not the full contract claimed by the index.

**Problems**

- `SourceSiteService.register()` says it always lands inactive/unapproved, but updating an existing row preserves `active`, `termsSignedOffBy`, and timestamps (`SourceSiteService.kt:19-40`). Changing base URL, ToS URL, robots URL, parser assumptions, or license can therefore retain obsolete approval.
- `source_site.allowed_hosts` exists in V8, but registration has no parameter for it and ingestion never validates `canonicalUrl` against it.
- `startRun()` accepts robots evidence solely when `decision == "allow"` (`CatalogImportService.kt:51-74`). It does not require freshness, a 2xx fetch, a nonblank content hash, the exact robots URL, user-agent/parser identity, or an allowlist snapshot.
- `import_run` still has a coarse free workflow: `running/succeeded/failed/blocked/dry_run`. It has no enforced `created → preflight_allowed → ingesting → reviewed → applied` transition.
- Canonical apply blocks dry/failed/blocked runs, but does not require that review is complete or that the run is in an apply-authorized state.
- `finishRun()` loads without a row lock and can rewrite one terminal state into another (`CatalogImportService.kt:217-225`).
- Run counters are not maintained and therefore cannot support reconciliation or audit.
- Multiple live runs for one source are permitted. They can race through separate run locks into the same source-record and review constraints.

**Required correction**

- Any material source registration change must atomically clear approval, deactivate the source, and require a fresh owner sign-off.
- Administer `allowed_hosts` explicitly. Validate HTTPS, normalized host, port policy, redirects, and final URL for every observation/evidence record. Reject credentials, fragments, local/private/link-local IPs, and off-allowlist redirects.
- Persist a complete preflight snapshot: robots URL, fetched time, status, content hash, decision, user agent, parser/tool version, allowed-host snapshot, ToS URL/hash/check/sign-off identity.
- Define an enum-backed state machine and CHECK constraint. Lock the run on every transition. Terminal states must be immutable.
- Apply only from an approved/reviewed state and only from the exact reviewed revision.
- Maintain counters transactionally or remove them; decorative audit fields are worse than absent fields.
- Allow at most one nonterminal run per source, or take a source-level advisory/row lock.

**Acceptance criterion:** a changed source registration, stale/missing robots response, off-host URL, dry run, unreviewed run, concurrent second run, or terminal-state rewrite all fail before any canonical write.

### P0-3 — compact tombstone contract is not implemented

`TeaCatalogService.detailByPublicId()` returns `tea.toDetail()` for a retracted tea and for a merged tea with a broken/cyclic chain (`TeaCatalogService.kt:81-92`). That exposes the old names, descriptions, images, flavor, and provenance.

This is not only response bloat. Retraction may be required because content is incorrect, unsafe, or not licensed for publication. Returning it from a tombstone defeats withdrawal.

**Required correction**

- Create an explicit lifecycle response shape or status-aware serializer containing only `publicId`, `status`, `supersededByPublicId`, and optionally a localized generic message.
- A successful merged lookup should return/redirect to the survivor with a clear supersession field. A broken chain must fail closed to lifecycle-only data and emit an operator alert.
- Numeric compatibility must use the same lifecycle behavior.
- Add tests asserting retracted/merged tombstones contain no names, descriptions, images, flavor, origin, brand, or provenance.

### P0-4 — updater manifest has no independent trust root

The backend manifest currently supplies `apkUrl`, `apkSha256`, and `signingCertSha256`. If that backend or its credentials are compromised, the attacker can choose all three. Android's same-signer rule provides protection only if the installed package really is signed with the original key and package identity is checked; the app should not describe a server-selected certificate as an independent pin.

Additional downloader problems: no maximum `Content-Length` or streaming cap; writes directly to final `update.apk`; no fsync/atomic rename; URL strings are logged; package name is not part of the signed release metadata.

**Required correction before enabling the endpoint**

- Embed an offline Ed25519 public key in the app and sign a canonical manifest offline. Include package name, version code/name, minimum version, APK byte size, SHA-256, signer history/policy, issued/expiry time, and monotonic rollback counter.
- Verify manifest signature before using any URL/hash/cert field. Reject expired or rollback manifests.
- Verify package name, version code, APK hash, byte size, and Android signer continuity before install.
- Stream into a random `.partial` file with header and actual-byte caps, fsync, verify, then atomically rename.
- Keep forced updates disabled for a local-first app unless a real compatibility/security need exists; always preserve export/backup access.
- For testers, prefer **Obtainium** now. A full TUF client is not recommended because the official TUF organization currently has maintained Python/Go/Rust/JavaScript implementations but no maintained JVM/Android client. Use the TUF threat model, not an abandoned Java port.

### P0-5 — release-signing recovery remains an owner-controlled single point of failure

The external `release.jks` is mode `0644`, and no tested encrypted backup/recovery ceremony is evidenced. Losing the key prevents updates; leaking it enables malicious same-signer updates.

**Required owner action**

- Restrict local permissions immediately; keep the working key outside synced/general context directories.
- Create two encrypted offline backups with separately stored recovery material.
- Document alias, certificate fingerprints, package ID, validity, and recovery steps without storing passwords.
- Perform and record a restore-and-sign drill, then verify the restored key updates an installed prior APK.

## Ingestion and catalog P1 findings

### P1-1 — C6 provenance/merge semantics are incomplete

- `applyApprovedMerge()` never merges or records `facts.type` (`CanonicalUpsertService.kt:107-149`).
- When an incoming value equals the existing value, `mergeScalar()` records nothing (`:152-166`). Corroboration from an independent source is therefore lost.
- Existing curated rows have no baseline field claims, so the selected-claim model can be incomplete even after a scrape merge.
- `brand` is written on create and fills null on merge simply from approving the tea identity. Decision #137 requires brand to be a separately reviewed field decision; vendor stays observation-only.
- Selection replacement is protected by a partial unique index but not a tea/field row lock or optimistic version. Concurrent applies may fail nondeterministically rather than serialize cleanly.

**Improve:** introduce explicit per-field decisions, include type, record corroborating claims, backfill curated claims, keep vendor noncanonical, and lock/CAS the selected claim set. Canonical identity approval must not implicitly approve every mutable field.

### P1-2 — matcher hides ambiguity

`IdentityMatchService` uses `firstOrNull()` for authoritative and exact matches and `maxByOrNull()` for trigram results. Multiple authoritative owners and score ties are silently collapsed. Candidate queries do not filter `tea.status='active'`; the apply-time tombstone guard is only a last backstop. The alias uniqueness constraint is per tea, so the same authoritative alias can be assigned to multiple teas.

**Improve:** return a ranked candidate set with match reasons; make multiple authoritative/exact owners an explicit conflict; surface ties; filter active candidates in SQL; add an operator repair report and a defensible global identity invariant for authoritative aliases.

### P1-3 — review concurrency is still open

The one-pending partial unique index prevents two pending rows, but two CLI processes can load and consume the same pending decision. Add `findByIdForUpdate`, an atomic `UPDATE ... WHERE decision='pending'`, or `@Version`, and assert exactly one winner. Caller-provided `--reviewer` is useful attribution for a single operator, not authenticated identity; document it accordingly.

The review index row FND-P1-3 should be **PARTIAL**, not DONE.

### P1-4 — strict observation validation remains open

- Unknown tea type still maps to `OTHER`; invalid type is not rejected into a mapping queue.
- Country is not constrained to ISO 3166-1 alpha-2.
- Decision #138 is design-only while importer code still promotes free-text `region` directly.
- URLs/hosts and evidence hashes are insufficiently validated; `raw_evidence` is not a complete proof chain.
- Facts are deserialized with unknown properties ignored in canonical apply, weakening schema evolution/audit.

**Improve before scraper work:** implement #138 country/region gate or temporarily reject/omit region facts; use strict DTO/schema validation; persist evidence metadata; re-run the facts guard at apply time against the exact revision; queue unknown enum/country/region values rather than coercing them.

### P1-5 — public API transition needs an executable contract

The server exposes `publicId/status/supersededByPublicId`, but Android DTOs still decode only numeric `id` and ignore lifecycle fields (`app/.../CatalogDtos.kt:20-28, 67-87`). New code should not deepen dependency on numeric IDs.

**Improve:** freeze server/app JSON fixtures in both modules; add public-ID routes and lifecycle cases to contract tests; use UUID for all new lookups; keep numeric lookup compatibility-only; consider `/api/v2` or a clearly documented v1 deprecation path because v1 now has two identities.

## Android architecture findings

### P1-6 — implement the canonical-reference / physical-sample split

Room v6 has a unique index on `teas.catalogTeaId` (`Entities.kt:59-78`). It enforces one personal row per catalog tea, so a user cannot represent two vendors, harvests, batches, purchases, photos, notes, or rankings for the same canonical tea. It also allows catalog enrichment to mutate user-owned fields.

The amended `context/design/tea-sample-split-v7.md` is the correct direction:

- `CatalogTeaRef` keyed by public UUID and containing refreshable catalog facts/lifecycle.
- Many personal `TeaSample` rows may reference one catalog ref.
- Personal names/notes/flavor/purchases/photos never get overwritten by catalog refresh.
- Any nonblank ru/en/zh/pinyin name can be primary.
- Numeric IDs exist only in legacy reconciliation stubs.
- Backup v2 and Room v6→v7 are lossless and use the same reconciliation rules.

One design cleanup is still required before coding: obsolete Long-based examples remain below the amendment. Either rewrite the document into one coherent UUID design or place the old sections in an explicitly historical appendix; contradictory executable sketches are too risky for a data migration.

**Required tests:** generated schema identity validation; `MigrationTestHelper` v6→v7; multiple samples per ref; custom sample; zero-board sample; multi-board placement; every child table and photo path; unresolved legacy stub; UUID reconciliation without personal-field changes; merged/retracted ref; backup v1→v2 and v2 round-trip.

### P1-7 — enrichment patch contradicts its ownership contract

Comments say catalog values fill only blank user fields, but `applyEnrichmentPatch()` selects every nonblank candidate before the current value (`TeaDao.kt:238-266`) and always replaces type. A user edit that lands before the transaction starts can therefore be overwritten. The transaction removes the read/write race but does not solve ownership semantics.

The v7 ref/sample split is the clean fix. Until then, either fill only blank fields or track dirty/ownership per field. Update tests and comments to the actual policy; do not keep “catalog wins” behavior under “user wins” documentation.

### P1-8 — queued enrichment should use durable unique work

`TeaEnrichmentManager` launches in an application coroutine and resumes only when app/board startup calls `resumePending()` (`TeaEnrichmentManager.kt:35-69`). Process death, reboot, or a user who does not reopen leaves work dormant.

Adopt **AndroidX WorkManager 2.11.2** for persistent enrichment when background completion is a product promise. Use one unique work name per sample ID, a network constraint, exponential backoff, and idempotent server calls. Keep the current app-open path only if UI copy continues to promise exactly that weaker behavior.

Official evidence: <https://developer.android.com/develop/background-work/background-tasks/persistent/how-to/manage-work> and <https://developer.android.com/jetpack/androidx/releases/work>.

### P1-9 — unbounded image/file reads

- `AndroidImageReader` calls `readBytes()` before inspecting dimensions (`ImageReader.kt:35-52`), so a hostile/huge content provider can cause OOM before downscaling.
- `AndroidPhotoStore.copyIn()` and `importInto()` stream without a byte cap or validated image type (`AndroidPhotoStore.kt:43-80`), allowing storage exhaustion.
- `delete()` uses string prefix containment rather than canonical-path boundary (`:86-94`).
- Release logging includes content URIs and absolute local paths (`ImageReader.kt:59-61`, `AndroidPhotoStore.kt:53-64, 89-92`).

**Improve:** enforce stream byte caps while copying; inspect MIME and image bounds safely; decode from a bounded file/descriptor rather than a full byte array; use canonical paths plus parent equality; redact URIs/paths in release logs. Apply the same bounded-stream helper to APK and backup-adjacent imports.

### Android P2 backlog

- Persist an in-progress add/edit draft with `SavedStateHandle` or a draft table; the current entry token handles recomposition/rotation but not process death.
- Escape `%` and `_` in offline Room `LIKE` search so literal queries behave the same online/offline.
- Cache last successful detail by public UUID for offline detail cards.
- Use `LocalResources.current` instead of `LocalContext.current.resources`; add Android 12+ `dataExtractionRules`; clean the remaining actionable lint warnings.
- Add critical Compose UI tests: add/edit sample, board placement, backup/restore, OCR review, updater denial/error, large font, screen reader semantics, and narrow/wide screens. Unit coverage is good but UI behavior is largely unexecuted.
- Add detekt/ktlint or adjust AGENTS/build expectations; today “check” does not enforce those conventions.

## Privacy and security findings

### P1-10 — privacy copy and retention do not match behavior

- Copy still discusses geopoints even though purchase geopoints are not implemented.
- Network behavior includes typed catalog queries, optional pasted/OCR text, diagnostics, updater checks/download, and potentially remote image hosts. The disclosure should be one explicit data-flow table, not scattered feature prose.
- Catalog misses store free-form typed queries plus first/last dates. “Anonymous” does not make arbitrary text non-sensitive.
- The purge policy can retain frequently repeated misses indefinitely, while current copy implies periodic deletion.
- A rare `ResolveService` conflict log includes the raw typed name; user input should be redacted or hashed.
- Diagnostics truncates after request binding and lacks DTO `@Size`; the proxy permits a much larger request than the application needs.

**Improve:** define purpose, fields, recipient, retention, and opt-in for each egress; cap and normalize miss text; purge all raw miss keys on a fixed maximum age while retaining only aggregate buckets if needed; remove raw query/path/URI logging; validate diagnostic payload sizes before allocation and reduce proxy body limit on that route.

Remote catalog images should remain disabled until images are first-party mirrored with verified license/attribution and the egress is disclosed. The current seed has no images, so this is a safe deferred gate.

### P1-11 — rate-limit eviction can reset abuse budgets

Per-client Caffeine eviction can remove an active counter; a new entry starts with a fresh budget. The global LLM ceiling limits spend and OCR concurrency limits workers, but search/resolve churn can still create avoidable load.

**Improve:** add an edge/global overload budget, keep expensive-operation global semaphores, and use bounded token-bucket entries whose eviction cannot grant unlimited aggregate capacity. Do not add Redis solely for this at current scale.

## Release and operations findings

### P1-12 — produced image identity is not enforced at deploy

CI produces image digests, signatures and attestations, but production compose takes mutable `${SERVER_IMAGE}` and `${OCR_SIDECAR_IMAGE}`. Documentation still demonstrates `:latest`, and the live server is visibly behind current migrations.

**Improve:** create one deploy command that accepts immutable digests only, verifies Cosign identity/attestation, records the release manifest, takes/preflights a backup, runs migration checks, starts services, validates health and representative API contracts, and supports a documented rollback. A pull plus `compose up` is not a deployment system.

### P1-13 — backup controls are optional and overprivileged

Off-box Object Storage is not enforced, the service account has folder-wide storage administration, and restore evidence is not automatically produced.

**Improve:** narrow IAM to one bucket/prefix and required operations; encrypt/version/retain backups; alert on missed uploads; run a scheduled restore into an isolated database; validate row counts, constraints, checksums and API smoke tests; store the drill result outside the source VM.

### P1-14 — single-host operational gaps

- SSH allows `0.0.0.0/0`; serial console is enabled.
- No external uptime/expiry/backup alerting is evidenced.
- Docker log rotation is not configured.
- Declared container memory approaches the capacity of a 4 GB VM and OCR can consume most CPU on a 2-vCPU host.

**Improve:** restrict SSH to operator CIDRs or use a private access path; disable unused serial access; configure log rotation; export JVM/HTTP/DB/OCR/run/backup metrics; add external health, TLS expiry, disk, memory, restart-loop and backup-age alerts; load-test with OCR and API traffic together.

Reuse Spring Boot Actuator/Micrometer and add only a Prometheus registry if metrics will actually be scraped. Protect actuator endpoints on a private network/path. Official reference: <https://docs.spring.io/spring-boot/reference/actuator/metrics.html>.

### P2 operations/quality

- Align OCR CI with the Python 3.14 runtime image or test both 3.12 and 3.14. The current split can miss runtime-only dependency failures.
- OCR readiness remains true after a broken worker pool; readiness should fail until a replacement pool passes a probe.
- Add container/OS vulnerability scanning (for example Trivy) in addition to existing OSV dependency scanning.
- Pin deployable artifacts by digest everywhere, not only Docker base images.
- Remove Gradle deprecations before the Gradle 10 upgrade becomes urgent.

## Open-source reuse decision matrix

| Need | Decision | Reuse | Reason / boundary |
|---|---|---|---|
| Persistent Android retry | **Adopt now with v7** | AndroidX WorkManager 2.11.2 | Official, unique work + constraints + backoff; replaces custom process-lifetime retry. |
| Maps/region visualization | **Defer UI; adopt later** | MapLibre Native Android (current docs 13.0.2) | GMS-free and has offline-region APIs. Use bundled/first-party licensed tiles; never bulk-download public OSM tiles. |
| App updates for testers | **Adopt now operationally** | Obtainium | Removes custom updater risk while the signed-manifest protocol is unfinished. |
| Production update trust model | **Reuse design, implement small** | TUF threat model + offline Ed25519 manifest | No maintained official JVM TUF client was found; do not import abandoned security code. |
| Catalog search | **Keep** | PostgreSQL `pg_trgm` + curated aliases | Sufficient footprint and existing integration. Add a search service only after gold-set failure. |
| Scrape pilot | **Keep planned stack** | `httpx`, `selectolax`, Protego, `pypinyin` | Small, auditable, appropriate for local one-source pilot. Use Scrapy only for later sitemap/queue scale. |
| Cross-script identity | **Curate** | `pypinyin` + committed Palladius alias table | No generic transliterator safely establishes tea identity; generated forms are candidates, never authoritative. |
| Metrics | **Adopt minimal** | Spring Actuator/Micrometer + Prometheus registry | Already aligned with Spring; avoid another large application. |
| Crash telemetry | **Keep** | ACRA + narrow Spring endpoint | GMS-free and cheaper operationally than Sentry/GlitchTip. |
| OCR | **Keep current engine until corpus says otherwise** | RapidOCR/ONNX stack | New research claims about PP-OCR variants and accuracy are contradictory/unverified; use measured RU/EN packaging CER. |
| Search service | **Reject for now** | Meilisearch/Typesense/OpenSearch | Adds sync, RAM and failure modes without demonstrated catalog need. |
| Admin/review platform | **Reject for now** | generic admin/CMS | Local CLI and SQL are adequate for one trusted operator; build a UI only when queue volume proves it. |
| Message queue/cache | **Reject for now** | Kafka/RabbitMQ/Redis | PostgreSQL state + WorkManager/app DB cover current durability; no independent scaling need. |

MapLibre references: <https://maplibre.org/maplibre-native/android/api/> and <https://maplibre.org/maplibre-native/android/api/-map-libre%20-native%20-android/org.maplibre.android.offline/index.html>.

## New research review (GLM-4.7 and MiniMax, runs 01–21)

All new answers were read as model syntheses. They do not override current decisions or verified source/code evidence.

| Runs | Useful signal | Discard / verify before reuse |
|---|---|---|
| 01, 16 — catalog sources/breadth | Provenance and license isolation; demand-driven misses; Wikidata as a canonical identifier source. | Invented Wikidata/OFF record counts, automatic bulk breadth claims, wrong license details, arbitrary suggestion licensing, and monthly dump plans. Current curated/facts-only pilot is safer. |
| 02 — maps | Provider abstraction, tap-to-select without location permission, MapLibre/offline future path. | Old MapLibre/Yandex versions and unverified quotas. Current locked region-QID design plus MapLibre official docs is stronger. |
| 03, 04, 06, 08 — LLM/egress/web/model bakeoff | Strict structured output, global spend caps, fail-closed grounding, and explicit uncertainty. | Fabricated model IDs/benchmarks/MAE/prices/free tiers, VPN routing assumptions, Redis/Bloom-filter overengineering, and prompt “sanitizers” presented as security. Keep AI dormant until a current executable bakeoff. |
| 05 — Yandex Terraform | Remote state, private DB, least-privilege intent, immutable deploy flow. | Wrong current topology, managed-Postgres recommendation contrary to the chosen small-host design, plaintext/overbroad secret patterns, `latest` deployment, and stale cost/provider claims. Use live `infra/` as source of truth. |
| 07, 11, 12 — flavor/batch | Gold-set evaluation, provenance, idempotent durable jobs, conservative unknowns. | Made-up confidence formulas and gates, Alembic/Python migration suggestions for a Flyway/Kotlin service, fabricated model results, and premature ML/XGBoost. |
| 09 — typo search | Stay on PostgreSQL first; build a multilingual gold set; avoid OpenSearch. | False claim that `unaccent` bridges Cyrillic/Latin, invalid settings/check commands, stale service versions, and “Meilisearch-on-fail” before evidence. |
| 10, 13, 18, 19 — OCR | User review, byte/text caps, conditional preprocessing, local-first default, handwriting manual fallback. | Contradictory recommendations (ML Kit vs on-device Paddle vs Tesseract vs server models), fabricated CER/RAM/latency gains, nonexistent or unverified artifacts, and a 16 GB VM upgrade without measured ROI. Current real-photo corpus is authoritative. |
| 15 — telemetry | ACRA + minimal endpoint; explicit consent/data minimization. | Outdated ACRA pins/config examples and nonexistent Room destructive-migration callbacks. Current implementation already supersedes the premise. |
| 17 — updater | Same-signer enforcement, package/version/hash checks, Yandex-hosted mirror, Obtainium comparison. | Server-selected signer pin as trust, forced-update UX, direct final-file downloads, and incomplete signed-manifest design. |
| 20, 21 — scraping foundation | Local `httpx`/`selectolax`, facts-only staging, source-record idempotency, ToS/robots hard gate, stable public identity, per-field provenance. | Specific source suitability/yields are unverified; `tea.ru` characterization is unreliable; raw vendor prose-to-LLM storage is not approved; auto-merge thresholds are rejected; GLM UUIDv7/Postgres-16 and cross-script-trigram claims are wrong; MiniMax lacks stable public ID and defers provenance. |

The modified run-21 rating correctly leaves Opus/GPT first, places MiniMax above GLM, and explicitly rejects both new answers' unsafe auto-approval. No new research run is warranted now. The remaining questions are code/migration/production-verification tasks, not model-opinion gaps.

## Review-index corrections

Update `context/review/INDEX.md` when implementation work starts. Current statuses overstate closure:

| Existing row | Correct status | Reason |
|---|---|---|
| FND-P0-1 / SCR-P0-8 (C1/C3) | **REOPEN P0** | Frozen seed UUIDs do not reconcile the existing production rows; tombstone is not compact. |
| FND-P0-2 / SCR-P0-2,3 (C4) | **REOPEN P0** | Source re-registration, allowed hosts, robots freshness/evidence, run/apply state and terminal immutability remain incomplete. |
| FND-P0-4 / SCR-P0-6,7 (C6) | **PARTIAL P1** | Type omitted on merge, corroboration dropped, curated claims absent, brand lacks field decision, concurrency not serialized. |
| FND-P1-3 | **PARTIAL P1** | Schema uniqueness improved, but concurrent decision consumption/provenance selection still needs locking/CAS. |
| FND-P1-4 / SCR-P1-2 | **OPEN P0 before scraper** | Host/URL/evidence/type/country/region validation is part of safe ingestion, not optional cleanup. |
| AND-P1-1 | **READY / next app migration** | Server design prerequisite exists; production UUID reconciliation must land first. |
| OPS-P1-1 | **OPEN, urgent** | Live production API is behind current migration/contracts. |

## Ordered implementation plan

### Phase 0 — protect current users and identity

1. Restrict and back up the release key; perform recovery drill.
2. Add the deterministic production UUID/legacy-ID reconciliation migration and upgrade-from-V6 tests.
3. Take a production backup and rehearse the full migration offline.
4. Deploy by verified digest and run numeric/UUID/lifecycle contract probes.

### Phase 1 — finish ingestion foundation before any crawler

1. Re-registration invalidation and allowed-host administration.
2. Strict URLs/redirects/SSRF controls and complete robots/ToS evidence.
3. Locked run state machine, one active run/source, immutable terminal states, real counters.
4. Compact tombstone DTO and contract tests.
5. Strict type/country validation; implement #138 region QID mapping queue or reject region text.
6. Complete field claims/type/brand semantics and review/provenance concurrency.
7. Candidate-set matcher with explicit ambiguity and active-only queries.

### Phase 2 — Android data-model/API cutover

1. Consolidate the v7 design into UUID-only executable documentation.
2. Implement `CatalogTeaRef`/`TeaSample`, any-language names, lossless migration and backup v2.
3. Decode/use public UUID and lifecycle fields; numeric reconciliation only for upgraded rows.
4. Make enrichment catalog-owned facts refresh refs only.
5. Add WorkManager unique enrichment and bounded image/file I/O.

### Phase 3 — release/ops hardening

1. Offline-signed updater manifest or keep updater disabled and document Obtainium.
2. Complete release gates: exact tag/versionName/versionCode binding, monotonicity, package/cert checks, emulator migration, SBOM/OSV/container scan, provenance verification.
3. Digest-only deploy, external monitoring, log rotation, backup restore evidence, least-privilege IAM.

### Phase 4 — one-source scrape pilot

1. Select one source only after owner ToS sign-off and live robots/host verification.
2. Commit parser fixtures and expected facts; no network in unit tests.
3. Run locally in dry-run; ingest 5–10 facts-only records.
4. Review every identity and field decision; apply through the locked run state.
5. Audit DB/API/APK outputs for prohibited prose/images and exercise re-import/correction/retraction.
6. Expand only after ambiguity, conflict, correction and withdrawal metrics are acceptable.

## Definition of “ready for scraper”

TeaTiers is ready to implement a crawler only when all are true:

- Production UUIDs are deterministic and rehearsed across upgrade/rebuild.
- Source changes invalidate approval; allowed hosts and redirects are enforced.
- Robots/ToS evidence is fresh, complete and bound to the exact run/tool/parser.
- Runs have a locked apply-authorized state and terminal states are immutable.
- No dry/unreviewed/stale revision can write canonical data.
- Type, country and region cannot silently coerce into canonical free text.
- Ambiguous identity returns multiple candidates/conflict, never the first row.
- Every written canonical field has a reviewed selected claim; corroboration/conflicts remain queryable.
- Retraction removes public content while preserving lifecycle identity.
- A full pilot can be rerun idempotently and withdrawn without hard delete.

Until then, crawler implementation would increase risk faster than catalog value.
