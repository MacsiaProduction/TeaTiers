# TeaTiers — design / architecture review with an open-source-reuse lens — 2026-06-22

Status: current-state review against `main` at **`a622da0`** (`docs(INDEX): C1/C3/C4/C6 -> DONE (merged #121/#122/#124)`).
This pass runs **after** the same-day `2026-06-22-full-architecture-design-review.md` (which reviewed `399ed92`)
and after the three hardening PRs that review triggered: **#121** (V11 seed-UUID reconciler), **#122** (compact
lifecycle tombstone), **#124** (R3 ingestion gates + C6/R4 claim refinements). Its two distinct goals, per the
request:

1. **Verify** the freshly-claimed closures hold in code (the INDEX now marks C1/C3/C4/C6 `DONE`), and find what is
   still open or newly broken.
2. **Where can we reuse a ready open-source solution** instead of hand-rolling — the new angle. Library picks below
   were checked against upstream on 2026-06-22; pin exact versions at adoption time per `AGENTS.md`.

Method: three code-grounded subsystem surveys (server / Android / OCR+infra) reading the actual files at `a622da0`
with `file:line` evidence, plus targeted upstream verification of the load-bearing library recommendations.

---

## Executive verdict

The system boundary is still right and should not change: local-first personal data in Room, a small read-mostly
PostgreSQL catalog, no accounts, one Spring monolith, operator-controlled ingestion, a killable OCR sidecar. The
prior review's "what should remain unchanged" list stands in full. #121/#122/#124 are real improvements.

Two things this pass changes versus the INDEX:

- **The INDEX overstates the C4 closure.** `FND-P0-2 / C4` is marked `DONE`, but the **import-run state machine
  required by decisions #137-C4 and #139-R3 was not built**, and **`allowed_hosts` / SSRF validation is still
  unenforced on scraped URLs**. A never-reviewed `running` (non-dry) run can be applied to canonical data today.
  C4 is **PARTIAL**, not done. This is the single most important correction here.
- **The C1 reconciler (#121) has a real completeness sub-gap**: its straggler check only scans `source='curated'`
  rows, so a non-curated row whose `dedup_key` matches a frozen seed entry can keep its V7 random UUID and never
  trip the fail-closed gate.

The headline opportunity from the reuse lens: **five subsystems hand-roll something a well-known library does
better and safer** — per-client rate limiting (→ Bucket4j), SSRF/host validation (→ allowlist + seancfoley
IPAddress), the import-run state machine (→ a tiny enum transition table; **explicitly *not* Spring Statemachine,
which is EOL for OSS and has no Spring Boot 4 support**), DTO input validation (→ jakarta.validation), and durable
Android retry (→ WorkManager). Adopting these closes open P0/P1 findings *and* deletes code.

Everything else the prior review flagged (Android v7 split, updater trust root, deploy digest enforcement, backup
IAM, single-host ops, OCR Python drift) remains open and is re-confirmed against current code below.

---

## 1. Verification of the #121 / #122 / #124 closures

| Contract | INDEX says | This review finds | Evidence |
|---|---|---|---|
| **C1** — reproducible public UUID (#121) | DONE | **CONFIRMED, one P2 sub-gap** | reconciler is idempotent, FK-safe, fail-closed on collision + missing curated row; tests cover existing-row upgrade, blank rebuild, reorder, FK remap. |
| **C3** — compact tombstone (#122) | DONE | **CONFIRMED** | retracted / broken-chain / retracted-survivor → content-free `CatalogDetail.Tombstone` → 410; numeric path shares it. |
| **C4** — source/robots/run gates (#124) | DONE | **PARTIAL — state machine + allowed_hosts/SSRF absent** | re-registration invalidation, robots freshness, one-active-run, terminal-immutable `finishRun` landed; the 5-state run lifecycle and apply-from-`reviewed` gate did **not**. |
| **C6** — value-bearing claims + merge (#124) | DONE | **CONFIRMED, one nicety open** | `type` corroboration/conflict on merge, corroboration claims, `brand` non-selected, target-tea row lock all present; curated rows still lack baseline claims. |

### C1 (#121) — CONFIRMED with a P2 completeness sub-gap

`SeedPublicIdReconciler.reconcile()` is correct on the main path: matches existing rows to the frozen seed UUID by
`dedup_key` and rewrites in place; idempotent and no-op on a blank DB; fail-closed on a frozen UUID already held by
a different `dedup_key`; FK-safe (drops the discovered `tea_legacy_id_map_public_id_fkey` + `tea_merged_into_fk`,
rewrites, rebuilds the legacy map, remaps `merged_into_public_id` old→new, recreates both FKs). Tests cover
existing-row upgrade, idempotency, merge-pointer remap, absent-curated-row fail-closed, and blank rebuild
(`SeedPublicIdReconcileIT.kt`; reorder via `CatalogSeederIT.kt`).

- **Sub-gap (P2 / new):** `failOnIncompleteCuratedReconcile` scans only `WHERE source = 'curated'`
  (`SeedPublicIdReconciler.kt:128`). A frozen seed UUID for a row whose `source` is `'mixed'`/`'scrape'`/`'ai'`
  (e.g. a row enriched after seeding) but whose `dedup_key` is in the seed set would silently retain its V7 random
  UUID without tripping the completeness gate. There is also **no test for the collision path** (`failOnCollision`).
  *Fix:* assert completeness over **all** rows whose `dedup_key ∈ seed` (not just curated), and add a collision IT.

### C3 (#122) — CONFIRMED

`detailByPublicId` returns content-free `CatalogDetail.Tombstone(toLifecycle(...))` for retracted
(`TeaCatalogService.kt:97`), broken/cyclic merge chains (`:101-108`, with operator `log.error`), and chains ending
in a retracted survivor (`:112`). `toLifecycle` carries only `publicId/status/supersededByPublicId/message`
(`:187-192`). The numeric path shares it (`:79-82`); the controller maps Tombstone → 410 (`TeaController.kt:84-94`).

- **Minor leftovers (not leaks):** `TeaCatalogService.detail(Long)` (`:70`) still does `findById().toDetail()`
  bypassing lifecycle, but it is internal-only (the `ResolveService` poller) and unreachable from the client
  controller. The **cyclic-chain** branch has no dedicated test (only retracted is covered in `CatalogVisibilityIT`).
  *Fix (P2):* add a cyclic-chain tombstone test and an explicit assertion that the DTO contains no
  names/flavors/images.

### C4 (#124) — PARTIAL. The run state machine and host validation are missing.

Genuinely landed: material re-registration clears approval + deactivates (`SourceSiteService.kt:36-52`); robots
evidence freshness/2xx/hash/url/UA + ≤1h age (`CatalogImportService.kt:87-96`); one active run per source via
app pre-check + a `WHERE status='running'` partial-unique (V12); `finishRun` row-locks and rejects re-finishing a
terminal run (`:241-252`); decorative counters dropped.

**Still open against the locked #137-C4 / #139-R3 contract:**

1. **No enforced `created → preflight_allowed → ingesting → reviewed → applied` state machine.** V12's CHECK is
   only `status IN ('running','succeeded','failed','blocked')` and runs start at `running` (`ImportRun.kt:34`).
   **Apply does not require a reviewed state:** `requireApplyAllowed` rejects only `dryRun`, `blocked`, `failed`
   (`CanonicalUpsertService.kt:295-305`), so a `running`, never-reviewed, non-dry run **can be applied to canonical
   data**. This is the gap the prior review (P0-2) demanded be closed before any crawler, and it is not closed.
2. **`allowed_hosts` is unmapped and unenforced.** The column exists (V8) but `SourceSite.kt:14` says it is
   "managed in SQL and not mapped", and grep shows zero enforcement. `obs.canonicalUrl` flows through
   `ingest`/`reconcileSourceRecord` (`CatalogImportService.kt:150,165,187`) with **no host / scheme / SSRF /
   redirect / credential validation**. `UrlValidation.requireHttpUrl` is wired only to operator config endpoints
   (Wikidata/LLM/OCR) and only checks the `http(s)://` prefix.

*Fix:* see §3 (Bucket4j-adjacent) and §3 SSRF + state-machine reuse picks. **INDEX correction: set FND-P0-2 / C4
back to PARTIAL.**

### C6 (#124) — CONFIRMED, one nicety open

`type` corroboration/conflict on merge (`CanonicalUpsertService.kt:137`); corroboration claim when incoming==existing
(`:171-181`); `brand` is a non-selected `proposalClaim`, never auto-canonical, on both create and merge
(`:94,:139,:239-240`, left null on `Tea` create `:72-73`); pessimistic target-tea row lock for the selected-claim
swap (`findByIdForUpdate(targetTeaId)` `:123`); inactive-merge-target backstop (`:128`).

- **Open nicety (PARTIAL, non-blocking):** pre-existing curated rows still have no baseline field claims, so the
  selected-claim model is incomplete until a scrape touches them. *Fix:* a one-off backfill of curated baseline
  claims makes the provenance model total (also strengthens P1-1).

---

## 2. Problems still open (prioritized)

These re-confirm and sharpen the prior review against current code. IDs reuse the INDEX where possible.

### Ingestion / catalog (server)

- **[P0-before-scraper] C4 run state machine + apply-from-reviewed gate** — §1. *Reuse: enum transition table.*
- **[P0-before-scraper] FND-P1-4 strict validation** — unknown `type` → `OTHER` silently
  (`CanonicalUpsertService.kt:316-317`); country not constrained to ISO 3166-1; `region` is free-text length-only
  (#138's QID queue is design-only); all facts mappers deserialize with `FAIL_ON_UNKNOWN_PROPERTIES=false`; no
  jakarta `@Valid` on `ScrapedFacts`/`SourceObservation`. *Reuse: jakarta.validation + a custom ISO-3166 validator.*
- **[P0-before-scraper] allowed_hosts / SSRF** — §1.2. *Reuse: allowlist-first + seancfoley IPAddress.*
- **[P1] FND-P1-2 matcher hides ambiguity** — `IdentityMatchService` returns a single proposal via
  `firstOrNull()` (authoritative/exact/translit) and `maxByOrNull()` (trigram); candidate SQL has **no
  `tea.status='active'` filter** (`TeaRepository.kt:67,78-87`), with the apply-time `InactiveMergeTargetException`
  as the only backstop; alias uniqueness is per-tea (`tea_identity_alias_uk (tea_id, locale, alias)`, V9:40), so the
  same authoritative alias can map to many teas. *Fix: ranked candidate set + explicit tie/multi-owner conflict +
  `status='active'` in SQL + a global authoritative-alias invariant.*
- **[P1] FND-P1-3 review concurrency** — `ReviewService.pendingDecision` does `findById` + `require(pending)` +
  `save` with no row lock / atomic `UPDATE … WHERE decision='pending'` / `@Version` (`ReviewService.kt:82-88,
  106-111`); the one-pending partial-unique prevents a second pending row, not double-consumption of one. Two CLI
  processes can both consume it. (INDEX already PARTIAL — correct.)
- **[P1] rate-limiter eviction resets budgets** — `FixedWindowRateLimiter` Caffeine `maximumSize(10_000)` +
  `expireAfterAccess`; `windows.get(id){ Window() }` recreates an evicted entry with `count=0`
  (`FixedWindowRateLimiter.kt:29-32,38,55`), granting a fresh budget; no global/edge ceiling for `/resolve`/`/search`
  (only the OCR semaphore is global). *Reuse: Bucket4j.*

### Android

- **[P1] AND-P1-1 / P1-6 — no v7 ref/sample split** — Room still v6; `teas.catalogTeaId` UNIQUE
  (`Entities.kt:64-66`) blocks multiple physical samples/purchases of one catalog tea and lets enrichment overwrite
  user fields. The v7 design doc is **still internally inconsistent** — obsolete `Long`-based sketches remain below
  the UUID amendment (`tea-sample-split-v7.md:56,99,105` + §4.3 SQL). *Must consolidate the doc before coding.*
- **[P1] AND-P1-2 / P1-5 — app ignores public UUID + lifecycle** — `CatalogDtos.kt:21-22,68-69` decode only numeric
  `id`; no `publicId`/`status`/`supersededByPublicId`; `CatalogApi.detail(id: Long)` is the only lookup route.
- **[P1] AND-P1-3 / P1-7 — enrichment patch contradicts "fill blank only"** — `TeaDao.applyEnrichmentPatch`
  (`:248-274`) selects the catalog candidate first when nonblank and **always replaces `type`** (`:270,272`,
  `patchEnrichment :193-209`), opposite to the comments at `:190-192,238-244`. The `@Transaction` fixes the race,
  not the ownership.
- **[P1] AND-P1-4 / P1-8 — queued enrichment not durable** — `TeaEnrichmentManager` uses `@AppScope`
  `SupervisorJob()+Dispatchers.Default` + `resumePending()` on open (`:33-70`); process death strands work.
  WorkManager is **not** a dependency. *Reuse: WorkManager 2.x.*
- **[P1] AND-P1-5 / P1-9 — unbounded image/file I/O + logging** — `AndroidImageReader` `readBytes()` before bounds
  (`ImageReader.kt:38`); `AndroidPhotoStore.copyIn/importInto` stream with no byte cap / MIME check
  (`:48-52,74-77`); `delete()` uses string-prefix containment, not canonical-path boundary (`:89`); release logs
  include content URIs / absolute paths (`ImageReader.kt:60`, `AndroidPhotoStore.kt:54,59,63,91`,
  `ApkDownloader.kt:51,59,62`). *Reuse: Coil 3 bounded decode (already a dep).*
- **[P0] P0-4 updater has no independent trust root** — `ApkVerifier` trusts `apkSha256`/`versionCode`/
  `signingCertSha256` from an **unsigned** manifest (`ApkVerifier.kt:44-63`, `AppUpdateDtos.kt:13-25`); no embedded
  Ed25519 key, no manifest-signature check, no package-name/byte-size/rollback check. `ApkDownloader` writes
  directly to final `update.apk` with no caps/fsync/atomic rename (`:39,55`). *Reuse: Obtainium now + offline
  Ed25519 manifest; keep Ackpine for install.*
- **[P2 / new] offline `LIKE` not escaped** — `CatalogCacheEntity.kt:40` interpolates the raw query with no
  `ESCAPE`; a literal `%`/`_` behaves differently offline vs the online `pg_trgm` path.
- **[P2 / new] no `SavedStateHandle`/draft persistence** — add/edit form is in-memory only; a backgrounded-then-killed
  edit loses the draft.
- **[P2] no Compose UI tests** (`androidTest` has only the migration test); **no detekt/ktlint** enforced by
  `check`; **no `dataExtractionRules`** for Android 12+ (benign while `allowBackup=false`). *Note: the
  `hiltViewModel` deprecation in the backlog is already fixed — all call sites use the new import.*

### OCR sidecar / infra / ops

- **[P1 / OPS-P1-1] image identity not enforced at deploy** — prod compose takes mutable `${SERVER_IMAGE}`/
  `${OCR_SIDECAR_IMAGE}` (`docker-compose.prod.yml:47,128`); CI cosign-signs by digest + SLSA provenance, but **no
  deploy script consumes it** (no `infra/deploy/`; `cosign verify` only as a README copy-paste, README still shows
  `:latest`). *Reuse: cosign verify + gh attestation verify in a `deploy.sh` gate.*
- **[P1 / OPS-P1-2] backups optional + over-privileged** — off-box S3 is conditional on `BACKUP_S3_URI`
  (`backup.sh:39`, defaults same-disk); the backup SA has **folder-wide `storage.admin`** (`backups.tf:14-18`); no
  automated restore drill. *Fix: per-bucket `storage.uploader`, enforce off-box, scheduled restore-validation.*
- **[P1 / OPS-P1-3] single-host gaps** — SSH ingress `0.0.0.0/0` (`security_group.tf:7-12`); serial console enabled
  (`compute.tf:54`); **no Docker log rotation** in either compose (default unbounded `json-file` + per-request OCR
  `log.info`); **no metrics/alerting** — Actuator is on the classpath but only `health` is exposed
  (`application.yml:128-133`), no Micrometer Prometheus registry. *Reuse: Micrometer Prometheus registry (dep
  already present) + Docker `max-size` log driver.*
- **[P1 / new] 4 GB VM is over-committed and CPU-throttled** — declared `mem_limit` sums to **3420 MiB** (caddy 128 +
  server 1500 + db 768 + ocr 1024) on a 4 GB VM, leaving ~580 MiB for host+daemon; and `core_fraction = 50` on 2
  vCPU (`compute.tf:27-29`) means **effective ~1 vCPU**, while OCR is allowed `cpus: 1.5` — concurrent OCR + DB load
  can starve the JVM and risk OOM. *Fix: trim server heap or raise core_fraction / resize; treat OCR as off the hot
  path.*
- **[P2 / new] OCR readiness never reset after pool rebuild** — `/health` returns `ok` whenever the module
  `_ready` is true; the N6 rebuild replaces `_executor` but never flips `_ready=False` nor re-probes the new pool
  (`app.py:206,216-218,260-273`), so a broken-then-rebuilt-but-failing pool still reports healthy and is never
  restarted. *Fix: reset `_ready`, run a warmup probe on rebuild, split liveness vs readiness.*
- **[P2 / OCR-P2-1] Python 3.12 (CI) vs 3.14 (runtime) drift** — `Dockerfile:8,17` pin `python:3.14-slim`; CI
  (`ocr-sidecar.yml:40`, `osv-scanner.yml:67`) tests on 3.12. Runtime-only failures (numpy/onnxruntime/opencv) can
  pass CI. *Fix: matrix `["3.12","3.14"]` or move CI to 3.14.*
- **[P2 / OPS-P2-1] no OS/container-layer scan** — OSV covers app/pip SBOMs only; no Trivy/Grype on image layers.
  *Reuse: Trivy in the two image workflows.*
- **[P1 / REL-P1-1] release.yml not a full gate** — has tag-required + `check` + sign + apksigner verify, but **no
  tag→versionName/versionCode binding, no versionCode monotonicity, no package/cert assertion, no SBOM/scan/emulator
  migration bound to the release**. *(REL-P0-1 release.jks recovery + REL-P0-2 updater = owner/P0-4 above.)*

OCR controls that are **correct and should not change**: byte/pixel/decompression caps + 15 s wedge guard
(`app.py:227-264`), no-egress internal network + baked SHA-pinned models (`models.lock`, `fetch_models.sh`),
1g/1.5cpu/read-only/cap-drop hardening.

---

## 3. Open-source reuse decision matrix (the headline)

Verified upstream on 2026-06-22; **pin exact versions at adoption** per `AGENTS.md`. "Adopt" entries each close an
open finding above *and* delete hand-rolled code.

| Need (open finding) | Decision | Reuse | License / status | Boundary / why |
|---|---|---|---|---|
| Per-client + global rate limit (rate-limiter eviction; missing `/resolve` ceiling) | **Adopt** | **Bucket4j** core + `bucket4j_jdk17-caffeine` (8.19.0, May 2026) | Apache-2.0, active | Token-bucket whose refill survives eviction; add one **global bandwidth bucket** for the edge ceiling. Use the **core lib directly** with the existing Caffeine — **no Spring-Boot-starter / Redis** needed at single-host scale. |
| SSRF / host validation on scraped URLs (allowed_hosts unenforced) | **Adopt (allowlist-first)** | DB `allowed_hosts` allowlist **+ seancfoley/IPAddress (Java v5.6)** for IPv4/IPv6/CIDR classification | Apache-2.0, active | Allowlist is the primary gate; IPAddress robustly rejects octal/hex/`inet_aton` obfuscation and tests private/loopback/link-local CIDR membership. **Must also** re-resolve + re-check after DNS and block off-allowlist redirects (DNS-rebind/TOCTOU). Configure a *strict* parser (reject ambiguous forms). |
| Import-run state machine (the C4 gap) | **Adopt small; REJECT Spring Statemachine** | A ~30-line **enum/sealed transition table** with a DB CHECK + row-locked transitions; *optional* KStateMachine if hierarchy is ever needed | hand-rolled / KStateMachine Apache-2.0 | **Spring Statemachine is EOL for OSS (Spring, Apr 2025) and its Boot-4 support request was closed "not planned"** — TeaTiers is on Boot 4.1.0, so it is a dead end. A typed enum table fits the minimalist ethos and the one-operator model. |
| Strict observation/facts validation (FND-P1-4) | **Adopt** | **jakarta.validation** (Hibernate Validator, already transitive) `@Pattern/@Size` + a custom `@Iso3166` validator | Apache-2.0 | Declarative DTO constraints on `ScrapedFacts`; keep `FactsOnlyGuard` as defense-in-depth against prose smuggling, and a Wikidata-QID region queue (#138) instead of free text. |
| Durable Android enrichment retry (AND-P1-4) | **Adopt with v7** | **AndroidX WorkManager** `work-runtime-ktx` (2.x) | Apache-2.0 | `enqueueUniqueWork` per sample id + `NetworkType.CONNECTED` + exponential backoff + idempotent server call. Only if background completion is a product promise; else keep the honest app-open copy. |
| Bounded image decode (AND-P1-5) | **Adopt (already present)** | **Coil 3** (3.5.0) bounded `ImageRequest`/`size()` instead of `readBytes()`+`decodeByteArray` | Apache-2.0 | Already a dependency; use its size-bounded decode for the OCR downscale path. |
| Updater trust + install (P0-4) | **Adopt** | **Obtainium** for testers now; offline **Ed25519** manifest (Tink/BouncyCastle) before public; **Ackpine** (0.23.0, present) for install | Apache-2.0 | No maintained JVM TUF client exists — reuse the TUF *threat model*, not abandoned code. Ackpine `PackageInstaller` wrapper is already the right install reuse. |
| Metrics / alerting (OPS-P1-3) | **Adopt minimal** | **Micrometer Prometheus registry** + Spring Actuator (already on classpath) | Apache-2.0 | Expose `health,metrics,prometheus` on a private path; add external health/TLS-expiry/disk/backup-age alerts. Don't hand-roll; don't add a heavy APM. |
| Container/OS scan (OPS-P2-1) | **Adopt** | **Trivy** (`aquasecurity/trivy-action`) on the built image before push | Apache-2.0 | Complements OSV (app deps) with OS/base-layer CVEs. Grype is an equal alt. |
| Digest-pinned verified deploy (OPS-P1-1) | **Adopt** | **cosign verify** + **gh attestation verify** inside a `deploy.sh` | Apache-2.0 | The verify commands already live in the README — move them into a gate that accepts only `@sha256:` digests, preflights a backup, runs migrations, validates contracts, supports rollback. |
| robots.txt parsing (when scraper lands) | **Adopt then** | **Protego** (Python sidecar) / **crawler-commons `SimpleRobotRules`** (if any JVM-side) | BSD / Apache-2.0 | Already named in the locked design; don't hand-parse robots. |
| Off-box encrypted backups (OPS-P1-2, optional) | **Consider** | **restic** for encrypted/versioned S3 backups | BSD-2 | Optional upgrade over conditional `pg_dump`+S3; not required if IAM is narrowed and off-box is enforced. |
| Catalog search | **Keep** | PostgreSQL `pg_trgm` + curated aliases | — | Sufficient; add Meilisearch/Typesense/OpenSearch only after a committed multilingual gold set fails. |
| Search service / queue / cache / admin CMS / Spring Statemachine | **Reject for now** | Meilisearch · Kafka/RabbitMQ/Redis · admin platform · Spring Statemachine | — | No demonstrated need at this scale/operator model; each adds RAM, sync, and failure modes the 4 GB host can't spare. |

---

## 4. "Improve our idea / architecture" — higher-level observations

Beyond bug-fixing, four design moves are worth weighing:

1. **Build the scraper as a Python sidecar, not in the JVM.** When the crawler lands, reuse the proven OCR-sidecar
   isolation pattern (killable process, internal-only network, resource caps, baked deps). The locked design already
   chose Python tools (`httpx`/`selectolax`/Protego/`pypinyin`); keeping crawl/parse/robots concerns out of the
   request-serving JVM keeps the Spring service small and the fetch surface auditable. The JVM stays the
   review/canonical-write authority; the sidecar only produces facts-only observations.
2. **Plan a UUID-only `/api/v2` (or a dated v1 deprecation).** v1 now carries two identities (numeric + public
   UUID). New Android lookups should use UUID + lifecycle from day one (P1-5); a clean v2 contract with frozen JSON
   fixtures in both modules stops the numeric dependency from deepening.
3. **Make provenance total before the first scrape.** Backfill baseline field claims for curated rows (C6 nicety)
   so the selected-claim model is uniform — otherwise the first merge onto a curated row reasons over a partial
   claim set. Pairs naturally with the matcher's ranked-candidate rework (P1-2).
4. **Treat OCR co-tenancy as a capacity decision, not just limits.** With effective ~1 vCPU (`core_fraction=50`) and
   3.4 GB committed, OCR `cpus:1.5` can starve the JVM under load. Either raise `core_fraction`, drop OCR's CPU cap,
   or accept OCR as a best-effort opt-in that sheds load first — and load-test OCR + API together before relying on
   it publicly.

---

## 5. Candidate research questions

The same-day GLM/MiniMax review concluded "no new research run is warranted — the remaining work is
code/migration/verification." That holds: none of the findings above need a model opinion. If a run is ever wanted,
only two questions are genuinely open and would benefit from the standard multi-model pass (glm-5.2 + minimax +
gemini-3.5-flash + deepseek-v4), and both can wait until the relevant build starts:

- **R-A (defer until updater goes public):** the offline-signed-manifest protocol details (key rotation, rollback
  counter semantics, manifest schema) given no maintained JVM TUF client. Low novelty — the threat model is known;
  this is mostly a design write-up, not research.
- **R-B (defer until scraper pilot):** a concrete SSRF/redirect/DNS-rebind validation recipe for the JVM fetch path
  (allowlist + IPAddress strict-parser config + post-resolution re-check). Again largely engineering, not research.

Recommendation: **do not open a research run now.** Fold R-A/R-B into the respective build tasks.

---

## 6. INDEX corrections to apply

| Row | Current | Should be | Reason |
|---|---|---|---|
| FND-P0-2 / SCR-P0-2,3 (C4) | DONE | **PARTIAL (P0-before-scraper)** | run state machine + apply-from-`reviewed` gate not built; `allowed_hosts`/SSRF unenforced. |
| FND-P0-1 / SCR-P0-8 (C1) | DONE | **DONE with a tracked P2 sub-gap** | curated-only straggler scan; no collision test. |
| FND-P0-4 / SCR-P0-6,7 (C6) | DONE | **DONE, nicety open** | curated baseline claims still absent (already noted in #137). |

(C3 `DONE` is accurate. The P1/REL/OPS rows are accurate as OPEN.)

---

## 7. Recommended sequence (unchanged in spirit from the 2026-06-22 review)

1. **Protect identity/users:** restrict+back up the release key (drill); deploy the V11 reconciliation by verified
   digest after a production-like rehearsal; probe numeric/UUID/lifecycle.
2. **Finish C4 properly before any crawler:** enum run-state machine + apply-from-`reviewed`; `allowed_hosts` +
   SSRF (allowlist + IPAddress); strict facts validation (jakarta.validation + ISO-3166 + #138 region queue);
   ranked-candidate matcher; review-decision locking; Bucket4j rate limiting.
3. **Android cutover:** consolidate the v7 doc to UUID-only; implement `CatalogTeaRef`/`TeaSample` + lossless
   migration + backup v2; decode UUID/lifecycle; fill-blank-only enrichment; WorkManager; bounded I/O.
4. **Release/ops:** offline-signed updater (or Obtainium + disabled updater, documented); digest-only verified
   deploy; Micrometer/Prometheus + external alerts; log rotation; Trivy; narrowed backup IAM + restore drill;
   fix the 4 GB/CPU budget.
5. **One-source scrape pilot** only after the "definition of ready for scraper" (prior review) is fully met —
   which now explicitly includes the C4 state machine this pass reopened.
