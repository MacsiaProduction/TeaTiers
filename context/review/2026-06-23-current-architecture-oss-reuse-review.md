# TeaTiers architecture/design review - 2026-06-23

Scope: full current-state architecture review with a Ponytail bias: keep the boring architecture, reuse proven libraries only where they remove real risk, and do not add infrastructure until the current shape breaks.

Reviewed against repo `main` at `971af4d` on 2026-06-23T12:02Z. I read the root sketches (`architecture.md`, `task.md`), `context/plan.md`, `context/decisions.md`, the active review index, focused designs, live app/server/infra code, relocated research pointers, and `/Users/macsia/context/git/TeaTiers`. I also probed the live public API on 2026-06-23 and checked current upstream docs for the OSS items named below.

## Executive verdict

The base architecture is still the right one:

- Local-first Android app; server only owns a shared catalog.
- One Spring/Postgres backend on one VM; no accounts, no social/cloud user data.
- PostgreSQL `pg_trgm`/normalization for typo search instead of a separate search service.
- Operator-reviewed ingestion instead of an exposed admin UI.
- OCR sidecar isolated from DB and internet.

The server-side ingestion foundation is no longer the main blocker. The old C4 criticism was substantially closed by the current code: runs now have an explicit lifecycle, row-locked transitions, host/SSRF gates, immutable fetch evidence, strict facts validation, revision-bound review, and apply-only-from-reviewed-run checks.

The project is still not ready for the next public APK or for trusting production as "current":

1. Production is behind the current contract: live search still returns the old DTO shape without `publicId`, and a known `/by-public-id/{uuid}` route returned 404 during this review.
2. Android is still on Room v6 with `TeaEntity.catalogTeaId: Long?`, while the server contract and v7 design require UUID public ids, lifecycle/tombstones, and a sample/reference split.
3. The custom updater still trusts server-selected trust metadata. That is a code-execution channel and must not ship as the primary update path without a signed manifest or an external updater.
4. Release-key recovery, deploy-by-digest, backup restore proof, SSH/logging/metrics, and container scanning remain owner/ops blockers.
5. There is one fresh server race: the authoritative-alias advisory lock key does not use the same normalized form as the actual invariant.

No new research run is needed right now. The remaining problems are implement/deploy/verify tasks. Existing research runs already cover app auto-update, catalog scraping, and scraping foundation (`../researches/projects/TeaTiers/17-*`, `20-*`, `21-*`). Create a new research only if choosing an exact Android signed-manifest verifier library, not to re-decide the architecture.

## Already solid

### Local-first product boundary

The root files are explicitly historical. `architecture.md:1-3` and `task.md:1-3` point to `context/plan.md` and `context/decisions.md`; `context/plan.md:3-17` says the live truth is decisions, focused designs, live code, and `context/review/INDEX.md`.

The current product split still matches the simplest viable system: user boards/photos/notes stay on-device, the backend holds only catalog data, and maps/geopoints stay deferred from MVP (`context/plan.md:32-45`, `context/plan.md:73-78`). Do not add accounts, cloud sync, a queue, a second database, or a CMS until there is evidence of real pressure.

### Server ingestion foundation

The ingestion/run architecture is now much better than the older review files imply:

- Run lifecycle is explicit (`created -> preflight_allowed -> ingesting -> awaiting_review -> reviewed -> applying -> applied`) and terminal states are immutable (`server/src/main/kotlin/com/macsia/teatiers/service/ImportRunState.kt:3-52`).
- Status transitions go through a row-locked gateway (`ImportRunStateMachine.kt:9-39`).
- `startRun` gates ToS/robots/allowed-hosts and one-active-run-per-source before persisting an ingestible run (`CatalogImportService.kt:58-95`).
- `ingest` locks the run, checks site/parser/robots/state, validates the observed URL, requires fetch evidence, and re-runs facts validation before staging (`CatalogImportService.kt:115-154`).
- Raw evidence is persisted per immutable revision and requires 2xx + SHA-256 body hash (`CatalogImportService.kt:235-289`).
- URL safety rejects non-HTTPS, credentials, fragments, non-default ports, unallowed hosts, and private/reserved IP literals via `seancfoley/IPAddress` (`UrlSafety.kt:10-87`).
- Apply is only allowed from a non-dry reviewed/applying run, and the producing run cannot be dry or blocked (`CanonicalUpsertService.kt:340-413`).
- Per-decision alias collisions are quarantined instead of rolling back the whole run (`ReviewService.kt:120-160`).

Keep the homegrown enum state machine. Pulling in Spring Statemachine would be extra framework for a small finite state machine. The current Spring Statemachine docs are for 4.0.2 and show Spring Framework 6.2 / Spring Boot 3.5-era setup; this repo is on Spring Boot 4.1. More importantly, the current local code is shorter and easier to audit than a framework integration.

### Existing OSS reuse that should stay

- Keep `Bucket4j` for per-client/global rate limiting; the repo pins `8.19.0` (`server/gradle/libs.versions.toml:17-20`) and the official releases page marks 8.19.0 current.
- Keep `seancfoley/IPAddress` for SSRF/IP literal classification; the repo pins `5.6.2` (`server/gradle/libs.versions.toml:14-16`).
- Keep `jakarta.validation` at ingest/apply; this removed silent unknown-type/country coercion.
- Keep Coil 3.5.0 for image display (`app/gradle/libs.versions.toml` pins `coil = "3.5.0"`), but do not confuse it with bounded upload/file-copy handling.
- Keep Ackpine only as the PackageInstaller wrapper. It is not a trust boundary.

## P0 before next public APK / trusted production

### OPS-P0-1 - Production is still serving the old catalog contract

Current server code exposes `publicId` in search/detail DTOs (`server/src/main/kotlin/com/macsia/teatiers/dto/CatalogDtos.kt:14-27`, `CatalogDtos.kt:60-87`) and `/api/v1/teas/by-public-id/{publicId}` (`TeaController.kt:82-89`).

Live production did not match that on 2026-06-23:

- `GET https://tea.macsia.fun/api/v1/teas/search?q=Longjing&limit=1` returned the old summary shape with numeric `id` and no `publicId`.
- `GET https://tea.macsia.fun/api/v1/teas/by-public-id/acba9bbe-3663-4f79-8054-dad1da9f7287` returned 404.

Fix:

- Deploy by immutable digest, not `:latest`.
- Before and after deploy, run contract probes:
  - `/actuator/health` is `UP`.
  - search response contains `publicId`.
  - `/by-public-id/{known_seed_uuid}` returns active detail or a 410 lifecycle tombstone, never 404 for an issued id.
  - numeric compat route still resolves through the legacy map.
- Make the deploy script verify cosign signature and GitHub attestation before changing `/opt/teatiers/.env`.

### REL-P0-1 - Release signing key is readable by group/world and recovery is unproven

Local evidence: `/Users/macsia/context/git/TeaTiers/release.jks` is currently `-rw-r--r--` (`0644`).

Fix:

- `chmod 600` the key immediately.
- Store an encrypted offline backup and a separate copy of passwords/recovery steps.
- Run a recovery drill: build a signed APK from backup material and verify the signer certificate hash matches the published cert.
- Never commit the keystore, passwords, or generated signing config.

### REL-P0-2 - The custom updater trusts server-selected trust data

`AppManifestDto` carries `apkUrl`, `apkSha256`, and `signingCertSha256` from the server (`app/src/main/kotlin/com/macsia/teatiers/data/remote/dto/AppUpdateDtos.kt:5-25`). `ApkVerifier` then verifies the APK against those manifest-supplied values (`ApkVerifier.kt:22-63`). If the manifest endpoint or deploy path is compromised, the attacker can choose both the APK and the claimed hash/cert. Android's same-signer check still helps, but the app-side trust model is weaker than the comments claim.

Fix:

- Short path: do not promote the custom updater as the primary release channel; point users to Obtainium/GitHub releases until a signed manifest lands.
- If keeping the custom updater: pin an offline public key in the app and verify a detached Ed25519-signed manifest before trusting any URL, SHA-256, cert hash, force flag, or mirror.
- Add manifest fields: `packageName`, `versionCode`, `apkSha256`, `apkSizeBytes`, `signingCertSha256`, `minSupportedVersionCode`, `sequence`, `publishedAt`, `expiresAt`, `signature`, `keyId`.
- Reject package-name mismatch, downgrade/rollback sequence, expired manifest, oversize body, unsigned manifest, and unknown key id.

### AND-P0-1 - Android cannot consume the UUID/lifecycle catalog contract

Server detail/search DTOs now carry `publicId`, `status`, and `supersededByPublicId` (`server/src/main/kotlin/com/macsia/teatiers/dto/CatalogDtos.kt:14-27`, `60-100`). Android DTOs still decode numeric ids only (`app/src/main/kotlin/com/macsia/teatiers/data/remote/dto/CatalogDtos.kt:20-28`, `67-87`), and the API client only has `GET teas/{id}` (`CatalogApi.kt:38-39`).

This is a contract split-brain: new server data can be correct while the public app remains stuck on legacy numeric ids and cannot represent retracted/merged lifecycle.

Fix:

- Add app DTO fields `publicId`, `status`, `supersededByPublicId`.
- Add `GET teas/by-public-id/{uuid}` and use it for all new detail/poll paths.
- Keep numeric `GET teas/{id}` only for v6 legacy reconciliation.
- Add frozen JSON fixtures in both modules: active detail, merged redirect, retracted tombstone, broken merge 410, and old numeric compat.

### AND-P0-2 - Room v7 sample/reference split is still design-only

Current Room schema still has one flat `TeaEntity`, scalar names, and `catalogTeaId: Long?` with a unique index (`app/src/main/kotlin/com/macsia/teatiers/data/db/Entities.kt:64-80`). That prevents multiple personal samples for one catalog tea and keeps catalog facts in the same row as user-owned notes/photos/flavor.

The v7 design correctly amends catalog identity to UUID (`context/design/tea-sample-split-v7.md:7-23`), but the same file still contains stale `Long` examples below the amendment (`tea-sample-split-v7.md:52-108`, `143-147`). Clean the design before coding so migration work does not copy the obsolete shape.

Fix:

- Implement Room v7 with `catalog_refs(publicId)` and `tea_samples`.
- Move catalog facts to refs; personal names/notes/flavor/photos/purchases stay on samples.
- v6 `catalogTeaId: Long?` becomes a legacy-unresolved ref stub, then an idempotent reconciliation pass resolves the real `publicId`.
- Add migration tests for custom tea, catalog-linked tea, multiple boards, zero-board tea, purchases, photos, flavors, and failed legacy reconciliation.

## P1 architecture improvements

### AND-P1-1 - Enrichment patch still overwrites user-owned fields

The DAO comment promises "catalog wins only where the user is blank" (`TeaDao.kt:238-245`), but the implementation prefers any nonblank catalog name over the current user value (`TeaDao.kt:260-266`) and always writes catalog `type` (`TeaDao.kt:268-272`).

Fix:

- Best fix: v7 split. Catalog names/type live on `catalog_refs`; samples keep personal fields.
- If v7 is delayed: patch only blank fields, or add per-field dirty/ownership flags before applying catalog values.
- Add tests for nonblank user ru/en/zh/pinyin/type surviving an enrichment patch.

### AND-P1-2 - "Queued" enrichment is app-open retry, not durable background work

`TeaEnrichmentManager` launches work in the app scope (`TeaEnrichmentManager.kt:50-70`). That survives screen navigation but not process death/reboot. The comments and UI copy should not imply durable background processing unless the implementation uses WorkManager.

Fix:

- If background completion is a product promise, adopt AndroidX WorkManager. Official AndroidX docs list `2.11.2` as the latest stable on 2026-06-23; 2.11 raised minSdk to 23, and this app is already `minSdk = 26` (`app/build.gradle.kts:26`).
- Use unique work per sample/ref, network constraint, exponential backoff, and idempotent server calls.
- If not adopting WorkManager now, rename/copy the state as "will retry when the app opens" and stop pretending it is durable.

### AND-P1-3 - Image/photo/update I/O needs byte caps and redaction

The app still has several unbounded or path-leaking file paths:

- OCR image read loads the entire URI with `readBytes()` before bounds checks and logs the URI on failure (`ImageReader.kt:35-60`).
- Photo copy/import uses unbounded `copyTo`, trusts extension-ish input, and logs URIs/paths (`AndroidPhotoStore.kt:43-84`).
- Photo delete checks `file.path.startsWith(rootDir.path)`, not canonical containment (`AndroidPhotoStore.kt:86-94`).
- APK download writes to a fixed `update.apk`, has no content-length/body cap, no fsync, and logs URLs (`ApkDownloader.kt:33-65`).

Fix:

- Add one shared bounded-copy helper: max bytes, temporary file, fsync, atomic rename, cleanup on failure.
- For URI images, stream only up to the OCR/upload cap before decode; do not allocate arbitrary input.
- Verify MIME/decoded image before keeping a photo.
- Compare canonical paths for delete boundaries.
- Redact `content://` URIs, local paths, and update URLs from release logs.

### SRV-P1-1 - Authoritative-alias advisory lock key does not match the invariant

The actual owner check uses `a.alias_norm = lower(f_unaccent(:alias))` (`TeaIdentityAliasRepository.kt:32-43`). The lock key uses Kotlin `alias.trim().lowercase()` (`IdentityAliasService.kt:36-40`) and the comment admits accent variants are residual.

That means two concurrent transactions adding accent/case variants that collapse to the same `alias_norm` can take different advisory locks, both pass the service-level check, and violate the global one-active-owner invariant. The repair query can find it later, but the lock is supposed to prevent it.

Fix:

- Move lock-key normalization into SQL: lock on `hashtext(:locale || ':' || lower(f_unaccent(:alias)))`.
- Or add a repository method `lockAuthoritativeAlias(locale, alias)` so the lock and the owner check share the exact DB expression.
- Keep the repair query as an audit, but do not rely on it for correctness.

### SRV-P1-2 - Region is intentionally still free text; keep it out of scraped canonical writes until QID work lands

`ScrapedFacts.region` remains prose-bounded free text until the planned region-QID table work; the active index already calls this deferred. That is acceptable for seed/manual data, but not for automated source writes at scale.

Fix:

- For first scraper pilot, either set region to `null` unless the source maps to a known region QID, or keep region as a reviewed claim that cannot auto-canonicalize.
- Do not invent a custom region taxonomy. Use Wikidata QIDs or no structured region.

### PRIV-P1-1 - Miss-log retention keeps popular raw queries indefinitely

`MissLogService` stores normalized free-text misses and purges only rows older than the window with low count; popular strings survive (`server/src/main/kotlin/com/macsia/teatiers/service/MissLogService.kt:37-49`). That may be fine as a catalog-demand signal, but privacy copy must say so; otherwise raw user-entered tea strings have no true max age.

Fix:

- Either hard-delete raw miss text after a max age and keep only aggregate/nonreversible buckets, or disclose the "popular miss strings survive" behavior.
- Keep no IP/device/session identifiers, as the current service already does (`MissLogService.kt:13-21`).

### SRV-P2-1 - Diagnostics sanitizes after body binding

The diagnostics controller binds `ClientDiagnosticReportDto` without `@Valid`/field-size constraints (`ClientDiagnosticsController.kt:38-53`) and truncates after binding (`ClientDiagnosticsService.kt:31-49`). The daily cap helps table growth, not request-body memory.

Fix:

- Add request-size limits at server/proxy.
- Add Bean Validation caps to DTO fields and `@Valid` on the controller.
- Keep the existing allowlist/truncation as defense in depth.

### AND-P2-1 - Offline catalog LIKE treats `%` and `_` as wildcards

The offline cache search concatenates the user query into a Room `LIKE` pattern (`CatalogCacheEntity.kt:39-43`). `%` and `_` become wildcards, so a query of `%` can match everything.

Fix:

- Escape `%`, `_`, and the escape char before calling DAO; use `LIKE '%' || :escaped || '%' ESCAPE '\'`.
- This is low severity because it is local-only, but it is a cheap correctness fix.

## Ops / release hardening

### OPS-P1-1 - Deploy provenance exists but is still manual

`publish-image.yml` signs and attests images (`.github/workflows/publish-image.yml:61-81`), and `infra/README.md` documents verification commands (`infra/README.md:94-106`). But deploy instructions still tell the operator to set `SERVER_IMAGE=...:latest`/manual compose up (`infra/README.md:84-90`, `108-115`, `124-137`), and `docker-compose.prod.yml` accepts whatever `${SERVER_IMAGE}`/`${OCR_SIDECAR_IMAGE}` contains (`infra/deploy/docker-compose.prod.yml:46-48`, `124-132`).

Fix:

- Add one deploy script that takes only `repo@sha256:digest`.
- Verify cosign and `gh attestation verify`.
- Run backup + restore smoke or at least `pg_dump` success before migration.
- Pull, restart, then run API contract probes.
- Reject mutable `:latest` in production env.

### OPS-P1-2 - Backup is present but restore/off-box guarantees are weak

The backup script defaults to same-disk local dumps and makes S3 upload optional (`infra/deploy/backup.sh:1-6`, `37-46`). The backup service account currently has folder-wide `storage.admin` with a comment saying it can later be tightened (`infra/backups.tf:6-18`).

Fix:

- Turn off-box upload on before production data matters.
- Tighten IAM to bucket-scoped upload/list as soon as bootstrap is done.
- Add a restore rehearsal artifact: date, dump filename, command, row counts, app/API smoke result.

### OPS-P1-3 - Single-host exposure/observability is still hobby-grade

Current infra leaves SSH open to the world (`infra/security_group.tf:7-12`), serial console enabled (`infra/compute.tf:52-55`), no explicit Docker log rotation in compose, and no external alerting.

Fix:

- Restrict SSH to known IPs or require a bastion/VPN later; at minimum add fail2ban and key-only hardening to the VM checklist.
- Disable serial port if no longer needed for recovery.
- Add Docker `json-file` `max-size`/`max-file` logging options.
- Add Micrometer Prometheus registry and one external blackbox probe for `/actuator/health`.

### OPS-P1-4 - VM resource math is tight once OCR is on the hot host

The VM is 2 cores at `core_fraction=50` and 4 GB RAM (`infra/compute.tf:26-30`). Compose caps add up to about 3.4 GiB before OS/Docker overhead: Caddy 128 MiB, server 1500 MiB, Postgres 768 MiB, OCR 1 GiB (`docker-compose.prod.yml:35-52`, `90-95`, `124-132`). OCR also declares `cpus: 1.5` on an effectively 1-vCPU host.

Fix:

- Treat OCR as best-effort and off the hot path.
- Lower server heap/caps or increase VM core fraction/memory before OCR traffic.
- Add a load-smoke that hits search/resolve while one OCR request is running.

### OCR-P2-1 - OCR sidecar health can lie after worker rebuild

Startup warms the worker and sets `_ready = True` (`ocr-sidecar/app.py:200-207`). After timeout or `BrokenProcessPool`, the code replaces the executor but does not mark `_ready` false or warm the new worker before `/health` returns ok (`ocr-sidecar/app.py:254-273`, `216-218`).

Fix:

- On rebuild, set `_ready = False`, warm the new executor, then set true.
- Or split `/live` from `/ready`: live means process up, ready means warmed worker available.

### OCR-P2-2 - Python runtime/CI drift

The OCR Dockerfile uses Python 3.14 (`ocr-sidecar/Dockerfile:8-17`), while the OCR tests and OSV scan use Python 3.12 (`.github/workflows/ocr-sidecar.yml:37-48`, `.github/workflows/osv-scanner.yml:61-82`). The Dockerfile comment also still says refresh `python:3.12-slim` (`ocr-sidecar/Dockerfile:1-4`).

Fix:

- Test/OSV against the same major/minor as runtime, or intentionally pin runtime to the tested version.
- Fix the stale comment.

### OPS-P2-1 - Dependency scanning does not cover OS/container layers

OSV scans Gradle SBOMs and Python dependencies (`.github/workflows/osv-scanner.yml:1-82`). It does not scan OS packages in the server/OCR images.

Fix:

- Add Trivy for image filesystem/OS package scanning, pinned to a stable action tag or commit.
- Scan both server and OCR images before push or as a required follow-up job.

## Reuse / do-not-build decisions

| Area | Decision | Reason |
|---|---|---|
| Search | Keep PostgreSQL `pg_trgm`; do not add Meilisearch/Elasticsearch/Tantivy now. | Task wanted "ready search index"; Postgres already gives typo-tolerant search without another service. Add a search engine only after row count/latency proves it. |
| Background app work | Adopt WorkManager 2.11.2 if queued enrichment must survive process death/reboot. | It deletes custom app-scope retry semantics and gives network constraints/backoff/unique work. |
| Rate limiting | Keep Bucket4j + Caffeine/global edge bucket. | Already adopted and simpler than Redis for one VM. |
| URL safety | Keep IPAddress. | It handles odd IP literal forms that ad hoc parsing misses. |
| Update channel | Use Obtainium/GitHub releases until a signed in-app manifest exists. | Existing custom updater is not yet a trust boundary. |
| Metrics | Use Spring Boot Actuator + Micrometer Prometheus registry. | Native to Boot; no custom metrics stack needed. |
| Container scanning | Add Trivy action. | Covers OS/image layers OSV does not cover. |
| Ingestion state | Keep local enum state machine. | Spring Statemachine is more framework than this flow needs and not worth Boot 4 integration risk. |
| Admin UI | Do not build a CMS/admin panel. | Local CLI/operator review avoids auth/session/audit surface until there are multiple operators. |
| Queue/broker | Do not add Kafka/Rabbit/Redis. | Current bottleneck is correctness and ops, not throughput. |
| Scraper | Do not build the crawler until deploy/API/Android gates close. | The foundation is now close, but production/app contract drift would make scraper output hard to consume safely. |

External version/source checks used:

- AndroidX WorkManager release notes: latest stable `2.11.2` on 2026-03-25; 2.11.0 raised minSdk to 23. https://developer.android.com/jetpack/androidx/releases/work
- Bucket4j releases: `8.19.0`. https://github.com/bucket4j/bucket4j/releases
- Coil changelog: `3.5.0`. https://coil-kt.github.io/coil/changelog/
- Spring Statemachine current docs: 4.0.2, Spring Framework 6.2 / Boot 3.5 examples. https://docs.spring.io/spring-statemachine/docs/current/reference/
- Trivy action. https://github.com/aquasecurity/trivy-action
- Spring Boot metrics/Actuator docs. https://docs.spring.io/spring-boot/reference/actuator/metrics.html

## Research decision

Do not create a new research folder for this pass.

Reasons:

- `research/README.md:1-18` says deep research moved to `../researches/projects/TeaTiers/`.
- Existing runs cover the open areas: app auto-update, catalog scraping, scraping foundation, OCR, crash telemetry, and catalog breadth.
- The current blockers are not "what architecture should we choose?" They are "deploy the code that already exists", "finish Android v7", "sign/verify update metadata", and "tighten ops".

Only create a new research run if one of these becomes an implementation blocker:

1. Exact Android Ed25519/signed-manifest verifier library choice.
2. Fetcher-side DNS-rebinding/robots implementation for the future scraper sidecar.
3. Region-QID source mapping if Wikidata coverage is insufficient.

## Recommended sequence

1. Owner ops: `chmod 600` release key, backup keystore/passwords, prove restore/signing, enable off-box DB backups.
2. Deploy current server by verified digest and prove public API UUID/lifecycle contract with live probes.
3. Add server/app JSON contract fixtures for UUID, lifecycle, tombstone, merge, and numeric compat.
4. Implement Android Room v7 sample/reference split and public-id reconciliation.
5. Either remove the custom updater from the public path or add signed manifest verification and bounded/atomic downloads.
6. Add bounded file-copy/image-read helpers and redact URI/path/update logs.
7. Fix alias advisory lock normalization.
8. Add deploy script, Docker log rotation, Prometheus/blackbox probe, Trivy image scan, and OCR readiness rebuild.
9. Only then start a first scraper pilot; keep region null/QID-gated and keep every canonical write reviewed.

## Bottom line

The architecture does not need a bigger platform. It needs fewer trust gaps between the code already written and the public app: deploy the current server contract, finish the Android UUID/sample split, remove server-selected updater trust, and turn the manual ops notes into executable gates. After that, the scraper can be added without changing the core shape.
