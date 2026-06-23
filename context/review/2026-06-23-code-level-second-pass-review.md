# TeaTiers — code-level second-pass review (2026-06-23, HEAD `8cda124`)

Scope: a fresh **code-level** pass, run *after* `2026-06-23-current-architecture-oss-reuse-review.md`
(at `971af4d`) and the five hardening commits that landed since it. Goal: re-baseline what that review
left open, then surface **new** findings by reading the actual code at HEAD rather than re-arguing the
architecture. Ponytail bias: reuse what's already on the dependency list before adding anything.

Surfaces read: `server/src/`, `app/src/`, `infra/`, `ocr-sidecar/`, `.github/workflows/`. Three parallel
code surveys + spot-verification of every P1 and the headline P2s against the live source.

## Verdict (unchanged, confirmed)

The architecture is right and does not need a bigger platform — same conclusion as every prior pass. The
remaining work is **implement / deploy / verify**, not design. **No new research run is warranted** (see
§Research). What this pass adds is a batch of concrete, mostly small code fixes the architecture-level
reviews didn't go deep enough to catch.

## Closed since `971af4d` (verified in code)

| Prior finding | Closed by | Verified |
|---|---|---|
| SRV-P1-1 — alias advisory lock ≠ invariant normal form | #133 | `lockAuthoritativeAlias(locale, alias)` on `hashtext(... lower(f_unaccent ...))` |
| REL-P0-1 (key perms half) | #133 (chmod) | done; offline-backup + recovery drill still OWNER |
| SRV-P2-1 — diagnostics binds oversized body | #134 | request body now bounded |
| OCR-P2-1 — `/health` lies after worker rebuild | #135 | `_swap_executor` sets `_ready=False` → warm → re-arm; tested |
| OCR-P2-2 — Python 3.12/3.14 drift | #135 | Dockerfile + both CI workflows pin `3.14` |
| OPS-P1-3 (log-rotation slice) | #135 | prod compose `*default-logging` caps all 4 services |
| OPS-P2-1 — OS/container CVE scan | #136 | Trivy pinned-by-SHA, report-only baseline |

Everything else from the prior INDEX remains open as listed there.

---

## New findings — server

- **SRV-P1-3 (P1, race) — `applyApprovedNew` is check-then-insert with no lock and no catch.**
  `CanonicalUpsertService.kt:73-75` pre-checks `findActiveByDedupKey` then `saveAndFlush` with no row
  lock and no try/catch. Two concurrent applies of the same `dedup_key` both pass the check; the second
  `saveAndFlush` hits the `tea_dedup_key` partial-unique and dooms the **whole** apply tx (rollback-only)
  — defeating the per-decision quarantine the merge path was built for (#132/H3). The comment even claims
  it surfaces "a clear conflict instead of aborting the tx on the unique index", but the race window does
  exactly that abort. **Fix:** wrap the insert and convert `DataIntegrityViolationException` →
  `CanonicalUpsertConflictException` (same as the merge path).

- **SRV-P1-5 (P1, perf) — `match_decision.import_run_id` has no index.**
  `V8__ingest_staging.sql:149` declares the FK; the only indexes are on `source_record_id` and pending
  decisions (`V8:152-153`). `MatchDecisionRepository.findByImportRunIdAndDecisionIn` (the apply phase,
  `ReviewService.applyRun`) filters on `import_run_id` → seq-scan that grows with the decision table.
  **Fix:** `CREATE INDEX match_decision_import_run_idx ON match_decision (import_run_id);`

- **SRV-P2-2 (P2, dead protection) — `@Size` caps on `TeaController` params never run.**
  `TeaController.kt:57-60` annotates `q`/`locale`/`origin` with `@Size`, but the class is `@RestController`
  only — **no `@Validated`** (verified: no occurrence in the package). Spring runs method-param validation
  only on `@Validated` beans, so the documented "bound the unauthenticated public endpoint" cap is silently
  off. (`@Valid` on the `/resolve` body is unaffected.) **Fix:** add `@Validated` to the controller class.

- **SRV-P2-3 (P2, perf) — `findIdByNormalizedName` re-derives the normal form instead of using `name_norm`.**
  `TeaRepository.kt:49` matches `WHERE lower(unaccent(n.name)) = lower(unaccent(:q))` — a per-row function
  eval that can't use any index — while the indexed generated column `tea_name.name_norm`
  (`lower(f_unaccent(name))`, V4) already exists and the matcher's `findTeaIdsByExactNameNorm`
  (`TeaRepository.kt:78`) uses it. This is the **hot `/resolve` cache-hit path** (`ResolveService.kt:44,100`).
  **Fix:** `WHERE n.name_norm = lower(f_unaccent(:q))` — one shared invariant, reuses the GIN/btree index.

- **SRV-P2-4 (P2, perf) — `ReviewService.pending()` is an N+1.**
  `ReviewService.kt:170-193`: per decision a `findById(sourceRecord)` + candidate `summary()` (own
  `findById`) + one `catalogService.summary()` (own `findById`) **per ranked candidate row**. At
  `DEFAULT_LIMIT=50` × up to 5 candidates that is hundreds of round-trips per CLI page. **Fix:** batch with
  `findAllById` for records and candidate teas, map in memory.

- **SRV-P2-5 (P2, consistency) — `match_candidate.match_tier` has no CHECK.**
  `V15__match_candidate.sql:11` is bare `TEXT NOT NULL`; its twin `match_decision.match_tier` (`V8`) is
  `CHECK (match_tier IN ('authoritative','exact','trigram','transliteration','none'))`. The two can drift.
  **Fix:** add the same CHECK to V15's column.

- **SRV-P2-6 (P2, smell) — `claimContext` degrades provenance to null instead of asserting.**
  `CanonicalUpsertService.kt:327` does `sourceSiteRepository.findById(...).orElse(null)`, dropping CC-license
  + `scrape:<site>` provenance. The FK makes the null path unreachable today, so this is not a live bug —
  but a silent `orElse(null)` on an invariant is the wrong default. **Fix:** `orElseThrow`.

**Server over-engineering / reuse (trim):**
- `CanonicalUpsertService.kt:348` hand-rolls SHA-256 hex via `joinToString { "%02x".format(it) }` → use
  `java.util.HexFormat.of().formatHex(digest)` (JDK 17+, already on the toolchain).
- Three byte-identical NFD normalizers: `DedupKeys.normalize`, `CrossScriptKeys.normalizeHint`, and
  (minus the strip) `MissLogService.normalize` → one shared helper.
- `CatalogExceptionHandler.kt:16-26`: `TeaNotFoundException` / `TeaPublicNotFoundException` have identical
  404 handlers; the codebase already uses `@ResponseStatus(NOT_FOUND)` elsewhere — pick one style.
- `conflictClaim`/`corroborationClaim`/`proposalClaim` (`CanonicalUpsertService.kt:269-289`) are identical
  one-liners differing only by doc comment → collapse to one `nonSelectedClaim`.
- `ImportRunState`: the `CREATED` state is dead (`startRun` persists directly at `PREFLIGHT_ALLOWED`,
  nothing writes `CREATED`). Drop it until an async preflight actually exists (YAGNI).
- Bare single-column btree indexes on low-cardinality `tea.type/origin_country/status` (`V1:51-52`, `V7`)
  that every query gates behind `status='active'` first — planner won't use them; drop or make partial.
- `normalized_candidate.{name_en,name_zh,name_ru_norm,pinyin_from_hanzi,palladius_bridge}` (`V8:114-128`)
  look stored-but-never-read (the matcher normalizes on the fly); confirm a consumer exists before GA or drop.

**Server — genuinely solid, do not touch:** the append-only revision model (V10) + revision-bound
decisions + `match_decision_one_pending_uk` + single-consumer `findByIdForUpdate` CAS; `ResolveService`
race handling (insert-race → re-read → cache-hit; budget fail-closed; best-effort miss-log);
`TeaCatalogService` lifecycle/410-tombstone handling; `requireApplyAllowed` + evidence-chain gating;
`SeedPublicIdReconciler` FK-safe rewrite; `ClientDiagnosticsController` (constant-time token, fail-closed,
no PII); RFC-7807 `ProblemDetail` contract; actuator locked to `health`.

---

## New findings — Android

- **AND-P1-6 (P1, trust transport) — the update manifest travels an un-pinned connection.**
  `AppUpdateChecker` / `ApkDownloader.kt:30` reuse the plain catalog `OkHttpClient` (`NetworkModule.kt`).
  The entire updater trust model rests on the manifest (`apkUrl` + `apkSha256` + `signingCertSha256`), yet
  it is fetched with no certificate pinning. A MITM that can serve a manifest chooses the APK **and** both
  pins. This is the concrete live mechanism behind REL-P0-2 and is fixable independently: pin the
  `/app/latest` host with OkHttp `CertificatePinner` (already a dependency) now; the offline-signed-manifest
  work (REL-P0-2) lands later.

- **AND-P1-7 (P1, quota/correctness) — `resumePending()` re-fires `/resolve` (a budget token) on every board-open.**
  `TeaEnrichmentManager.kt:48,73`: `resumePending()` runs on app-open **and** board-open and re-dispatches
  every `PENDING`/`QUEUED` row; the `inFlight` set only suppresses *concurrent* dupes within one process. A
  tea stuck `PENDING` (killed mid-poll) burns a server LLM/resolve token on every board-open. Distinct from
  AND-P1-2 (durability): this is over-eager retry against a metered endpoint. **Fix:** gate re-dispatch on a
  `lastAttemptAt`/backoff column, or only resume on true app-launch.

- **AND-P2-3 (P2, leak) — verified APK is not deleted when install throws / is cancelled.**
  `AppUpdateViewModel.kt:85,91`: the `apk.delete()` cleanup is unreachable if `installer.install` throws or
  the coroutine is cancelled mid-install, leaving a verified APK in cache. **Fix:** wrap download→install in
  `try/finally`.

- **AND-P2-4 (P2, smell) — backup size constants are inconsistent.**
  `BackupManager.MAX_ARCHIVE_BYTES` (200 MB) < `BackupArchive.Limits.maxTotalBytes` (256 MB). The
  compressed pre-check being smaller than the decompressed cap is backwards for a zip-bomb and invites a
  wrong "alignment" later. **Fix:** document that 200 MB is the *compressed* SAF-declared size and is
  intentionally independent, or derive one from the other.

**Reinforcing (already AND-P1-3 — not new, but confirmed at HEAD):** `ApkDownloader.kt:55`
(`body.byteStream().copyTo(out)`) is unbounded, has no `Content-Length` check, no fsync, fixed
`update.apk`, and logs the URL on every failure path; `ImageReader` reads the whole picked URI with
`readBytes()` before bounds. The prior fix (one bounded-copy helper + redaction) covers these — note that
the **truncation** angle (a short-but-HTTP-200 body returns `true` and skips remaining mirrors; sha256
usually saves it) should be in that fix's scope: compare bytes written to `contentLength()`.

**Android over-engineering / reuse (trim):**
- `Sha256.ofFile` hand-rolls a 64 KB digest loop → you already depend on Okio; use a `HashingSink`, or at
  least `DigestInputStream`.
- `AndroidPhotoStore.delete` uses `file.path.startsWith(rootDir.path)` (`:89`) → use canonical-path
  containment (`canonicalFile.toPath().startsWith(...)`), which also closes any `..`.
- `AndroidPhotoStore.copyIn` vs `importInto` are near-identical → one private `writeTo(ext, input)`.
- `TeaDao.removePlacement`/`deleteTea`/`addPhoto` wrap a single statement in `@Transaction` for no
  atomicity benefit → call the underlying query directly.

**Android — genuinely solid:** `ApkVerifier` (sha256-pin + downgrade guard + `apkContentsSigners`,
fail-closed on empty pin); `BackupArchive.extractTo` (streamed, per-entry + cumulative bounds, traversal
rejection, atomic stage-validate-swap); `applyEnrichmentPatch` single-`@Transaction` lost-update close;
`AndroidPhotoStore.reconcile` grace-window TOCTOU; the pure reorder math; `decideUpdate` kept pure.

---

## New findings — infra / OCR / CI

- **OPS-P1-5 (P1, blast radius — sharpens OPS-P1-2) — backup SA is `storage.admin` at folder scope.**
  `infra/backups.tf:14-17` (the comment admits it). The static key lives on the VM in `backup.env`; a leak
  lets it **delete every bucket in the folder — including the Terraform state bucket.** **Fix:** create the
  bucket with the bootstrap identity; grant the backup SA only `storage.uploader` on
  `yandex_storage_bucket.backups`.

- **OPS-P2-2 (P2, supply-chain rot) — Dependabot watches Docker only in `/ocr-sidecar`.**
  `.github/dependabot.yml`: the prod compose pins `caddy:2-alpine` + `postgres:16-alpine` (+ OCR runtime)
  by digest in `infra/deploy/docker-compose.prod.yml`, but no `docker` ecosystem entry points at
  `/infra/deploy`, so those digests silently rot. **Fix:** add a `docker` entry for `/infra/deploy`.

- **OPS-P2-3 (P2, data loss) — backup prune is mtime-only with no "keep last N" floor.**
  `infra/deploy/backup.sh:51` does `find ... -mtime +N -delete`. If backups stop running for >N days while
  the VM lives, the last good dump is reaped. **Fix:** always retain the newest 3 regardless of age.

- **OCR-P2-3 (P2, perf) — `description_correct.py` double-parses every candidate with pymorphy3.**
  `description_correct.py:102-110`: `_is_real_word` parses, then the rank loop parses again for `.score` —
  up to 512 morph parses per 256-candidate token. **Fix:** parse once, reuse the `ParseResult` list.

**Deploy gap (sharpens OPS-P1-1):** both image workflows compute the immutable digest for cosign /
attestation, but the deploy path still tells the operator `SERVER_IMAGE=...:latest` / `OCR_SIDECAR_IMAGE=
...:latest` — the signed digest is never surfaced into `.env`. **Fix:** emit the digest to the job summary
so the operator can pin `@sha256:`. (The deploy-by-digest script is already tracked under OPS-P1-1.)

**Trim:** committed `ocr-sidecar/.pytest_cache/` + `__pycache__/*.cpython-31x` artifacts (gitignore +
remove); local `docker-compose.yml` (dev) has no logging caps (dev-only, low priority).

**Infra/OCR — genuinely solid:** OCR security posture (byte cap + Content-Length early-out +
decompression-bomb pixel guard + output cap + single killable subprocess with hard deadline +
SIGKILL-respawn + asyncio serialization; `internal: true` nets, sidecar has no egress/DB route);
supply-chain (models SHA-256-verified at build, base images digest-pinned, cosign keyless + SLSA
provenance, OSV over the full freeze graph, `init -lockfile=readonly`); `backup.sh` atomicity
(`.partial`→`mv`, EXIT trap, region pinning); Caddyfile (per-route body caps, XFF overwrite, HSTS); prod
compose hardening (`cap_drop: ALL`, `no-new-privileges`, `read_only` + tmpfs, `pids_limit`, health-gated
`depends_on`).

---

## OSS-reuse conclusion

The prior do-not-build list still holds (no Meilisearch/ES, no Kafka/Rabbit/Redis, no CMS, no Spring
Statemachine, keep Bucket4j / IPAddress / Coil / Ackpine). The reuse story this pass is **"use what you
already depend on,"** not "add a library":

| Instead of hand-rolling | Reuse (already available) |
|---|---|
| SHA-256 hex loops (server `CanonicalUpsertService`, app `Sha256`) | `java.util.HexFormat` (JDK) / Okio `HashingSink` |
| `@Size` params with no effect | `@Validated` (Spring, already present) |
| per-row `lower(unaccent(name))` | the existing `name_norm` generated column + index |
| un-pinned manifest fetch | OkHttp `CertificatePinner` (already a dep) |
| path `startsWith` containment | `java.nio.file.Path.startsWith` |
| app-scope enrichment retry (AND-P1-2) | AndroidX WorkManager `2.11.2` (minSdk 26 ✓) — only if durable background is a product promise |

Two genuinely-additive libraries already greenlit elsewhere and worth doing: **Trivy** (done, #136) and a
**Micrometer Prometheus registry** for `/actuator/health` observability (OPS-P1-3).

## Research decision

**No new research run.** Consistent with the `971af4d` pass: the open items are deploy/implement/verify,
not "which architecture." The deep-research folder moved to `../researches/projects/TeaTiers/` and existing
runs already cover auto-update (17), scraping (20/21), OCR (10/13), telemetry (15), and catalog breadth
(16). Open a new run only if one becomes an implementation blocker: (1) exact Android Ed25519/signed-
manifest verifier library (REL-P0-2), (2) fetcher-side DNS-rebind/robots for the future scraper, (3)
region-QID source mapping if Wikidata coverage is thin.

## Recommended order (cheap correctness first)

1. **SRV-P1-3** catch DIVE in `applyApprovedNew` (1-line guard, removes a tx-poison race).
2. **SRV-P1-5** add the `match_decision_import_run_idx` (one migration line).
3. **AND-P1-6** pin the manifest host (independent of the full REL-P0-2 signed manifest).
4. **AND-P1-7** stop re-firing `/resolve` on every board-open.
5. **OPS-P1-5** scope the backup SA to `storage.uploader` on one bucket.
6. The P2 batch (`@Validated`, `name_norm` query, N+1, match_tier CHECK, apk-delete-on-throw, dependabot
   docker scope, backup floor, OCR double-parse) — all small, no architecture change.
7. The trim list whenever the file is open anyway.

## Bottom line

Same shape, same verdict: the architecture is correct and the platform is right-sized. This pass found one
real server race (`applyApprovedNew`), one un-pinned trust channel and one quota-burning retry on Android,
and one over-broad backup credential — plus a long tail of cheap perf/consistency/trim fixes and several
"you already have the library, use it" reuses. None require a new design or a new dependency.
