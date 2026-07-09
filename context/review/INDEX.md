# Review finding index — active status map

Authoritative tracker for the **current** review findings. Older review files remain as evidence/history;
this index reflects what is still open against `main`.

> **Infra findings obsolete (`2026-06-24`, decision #143).** Production migrated off the Yandex Cloud
> VM to **pelican-node** (Docker-Compose + Komodo); the whole `infra/` OpenTofu stack was deleted.
> Any finding scoped to `infra/` / OpenTofu / Yandex backups (e.g. `OPS-P1-5`, `OPS-P2-2`) no longer
> applies — do not chase them. App-level findings (`SRV-`/`AND-`/`PRIV-`/`OCR-`/`SCR-`/`FND-`) are
> unaffected. Current deploy truth: `deploy/README.md`.

> **Compaction (2026-07-10).** Every review file listed below (including `archive-2026-06/` and the
> round-1/2 usage-quality plans) was condensed into **`OLD-REVIEWS.md`** and the originals removed from
> the tree (recoverable via git history). Filenames below are kept as historical identifiers — read the
> matching section in `OLD-REVIEWS.md`. Round 3 lives in `2026-07-10-usage-quality-improvement-plan-v3.md`.

- Locked intent: `context/decisions.md` #136 (foundation) + **#137** (C1–C7 contracts) + **#139** (2026-06-22
  correction: the production-safe bar — several #137 "DONE" closures reopened).
- Source reviews:
  - `2026-06-21-post-scrape-foundation-architecture-review.md` (FND-/AND-/REL-/PRIV-/SRV-/OCR-/OPS-)
  - `2026-06-21-scraper-implementation-readiness-review.md` (SCR-)
  - `2026-06-22-full-architecture-design-review.md` (full re-assessment after #119/#120 merged; P0-1..P0-5, P1-1..P1-14)
  - `2026-06-22-research-glm-minimax-review.md` (independent re-analysis of the new GLM/MiniMax answers; no decision changes)
  - `2026-06-22-post-hardening-current-state-refresh.md` (refresh after #121/#122/#124; reopens the incomplete C4 apply-state contract)
  - `2026-06-22-oss-reuse-architecture-review.md` (same reopen via an open-source-reuse lens; library decisions in #141)
  - `2026-06-23-phase1-harsh-review.md` (harsh whole-project review of main + open PRs #128/#129/#130; findings H1–H6 below)
  - `2026-06-23-current-architecture-oss-reuse-review.md` (full current-state + OSS-reuse pass at `971af4d`; verdict: architecture is right, remaining gaps are implement/deploy/verify; triage below)
  - `2026-06-23-code-level-second-pass-review.md` (code-level pass at `8cda124` after #133–#136 landed; confirms verdict, adds new code findings SRV-P1-3/P1-5/P2-2..6, AND-P1-6/P1-7/P2-3/P2-4, OPS-P1-5/P2-2/P2-3, OCR-P2-3 + reuse list; table below)
  - `2026-07-06-usage-quality-improvement-plan.md` (full usage-quality/UX audit at `7c1705f`; UX-P0/P1/P2 + feature gaps UX-F-1..6, own status table + PR batching — tracked in that file, not duplicated here)
  - `2026-07-07-usage-quality-improvement-plan-v2.md` (round 2, after round 1's batches landed #191–#195; six parallel read passes over the whole app + server-felt client behavior; two new lost-update P0s on the shared tea row, UX2-P1-1..12, UX2-F-1..4, UX2-P2-1..27 — own status table + PR batching, tracked in that file, not duplicated here)
  - **Superseded reviews** (`2026-06-17`…early `2026-06-21`, dispositioned/rolled into the passes above)
    were archived `2026-06-24` to `archive-2026-06/`; cited there from `decisions.md`/`plan.md`.
- Status legend: `OPEN` · `WIP` · `PARTIAL` (core landed, contract not fully met) · `REOPEN` (was marked DONE,
  re-opened by a later review) · `DONE` (landed + tested) · `DEFERRED` (gated, not now).

Baseline at index creation: `21fda80`. **Re-assessed against `a622da0` (2026-06-22) by two independent
reviews** (post-hardening refresh + OSS-reuse pass). PRs #119/#120 landed the C1–C7 work at the
*blank-rebuild / core-invariant* bar; #121/#122/#124 closed several production gaps. Both new reviews reopen
C4's apply-state portion: safe preflight/terminal handling landed, but no reviewed/apply-authorized run state
exists and `allowed_hosts`/SSRF is unenforced. **Decision #141 locks Phase 1 (everything) as the next
workstream** — a sequence of PRs (C4 state machine → SSRF/evidence → strict validation → review CAS →
ranked-candidate matcher → Bucket4j), built with adopted libraries. Update Status/Evidence as PRs land.

## Architecture / OSS-reuse review (2026-06-23, at `971af4d`)

From `2026-06-23-current-architecture-oss-reuse-review.md`. **Verdict: the architecture is right; the
remaining gaps are implement/deploy/verify, not design.** No new research run. Triage below — `DONE` items
already closed this pass, `CODEABLE` are in-repo work not yet scheduled, `OWNER` need the VM/secrets/deploy.

**Closed this pass:**
- **REL-P0-1 (key perms)** — **DONE (ops):** `chmod 600` applied to `…/context/git/TeaTiers/release.jks` (was world/group-readable `0644`). Offline backup + signing-recovery drill still OWNER.
- **SRV-P1-1 (alias lock normalization)** — **DONE ([#133](https://github.com/MacsiaProduction/TeaTiers/pull/133)):** advisory lock keys on `hashtext(:locale || ':' || lower(f_unaccent(:alias)))`, the exact `alias_norm` form, via `lockAuthoritativeAlias(locale, alias)`; accent/case variants now serialize on one lock. IT in `IdentityMatchAndReviewIT`.

| ID | Finding (short) | Surface | Severity | Status / disposition |
|---|---|---|:--:|---|
| OPS-P0-1 | Prod serves the OLD catalog contract (no `publicId`, `/by-public-id/{uuid}`→404); deploy by digest + pre/post contract probes | deploy | **P0** | **RESOLVED (#143)** — the pelican-node fresh-seed deploy runs V7–V17; verified live 2026-06-24 (`publicId` in `/search`, `/by-public-id/{id}`→200, `/resolve`→200). Digest-pinned deploy is still OPS-P1-1. |
| REL-P0-1 | `release.jks` `0644`; recovery unproven | ops | **P0** | **DONE (chmod)** + OWNER (offline backup + recovery drill) |
| REL-P0-2 | Custom updater trusts server-selected `apkSha256`/`signingCertSha256` (code-exec channel) | android/server | **P0** | **SHORT PATH DONE (#144)** — Settings update prompt routes to GitHub releases / Obtainium; in-app install de-emphasized (machinery retained, dormant). TLS leaf pin (AND-P1-6) rejected (LE rotation bricks updates). **Full path still OPEN:** offline Ed25519 signed manifest (decision #119) — owner generates the key. |
| AND-P0-1 | Android DTOs decode numeric id only → contract split-brain (no `publicId`/`status`/`supersededByPublicId`, no `by-public-id` client) | android | **P0** | **PREPPED — coupled to AND-P0-2** (publicId has nowhere to persist until v7). Decisions locked + design cleaned (`a69c7d2`). Implement WITH the v7 migration so publicId flows wire→domain→`catalog_refs`. |
| AND-P0-2 | Room v7 sample/reference split is design-only; flat `TeaEntity.catalogTeaId: Long?`; v7 design doc has stale `Long` examples | android | **P0** | **PREPPED, NOT STARTED IN CODE.** Decisions Q3–Q8 locked + design doc cleaned (`a69c7d2`): Q4=multi-alias, Q7=ship-refresh-writer, defaults taken. The data-loss-critical, emulator-gated, multi-PR migration itself (catalog_refs(publicId) + tea_samples + tea_sample_names + Migration(6,7) + DAO/repo/UI + backup v2 + refresh writer + migration tests) is the one remaining workstream — sequenced per design doc §6–§7. |
| AND-P1-1 | Enrichment patch overwrites nonblank user names/type (DAO comment says "blank only") | android | P1 | **CODEABLE** — patch only blank fields / per-field ownership; best fixed by v7 split |
| AND-P1-2 | "Queued" enrichment is app-scope retry, not durable (survives nav, not process death) | android | P1 | **CODEABLE** — WorkManager 2.11.2 (minSdk 26 ok) or honest "retries when app opens" copy |
| AND-P1-3 | Unbounded file I/O + URI/path logging (OCR read, photo copy/delete, APK download) | android | P1 | **CODEABLE** — one bounded-copy helper (cap+tmp+fsync+atomic rename), canonical-path delete, MIME check, redact logs |
| SRV-P1-1 | Alias advisory lock key ≠ invariant normal form | server | P1 | **DONE (#133)** |
| SRV-P1-2 | `region` is free text; must not auto-canonicalize from scraped writes until QID work | server | P1 | **DEFERRED** — no action until the scraper pilot; keep region null/QID-gated then |
| PRIV-P1-1 | Miss-log keeps popular raw query strings indefinitely (no true max age) | server | P1 | **CODEABLE / policy** — hard-delete raw miss text after max age + keep aggregate, OR disclose the behavior. (No IP/device ids — good.) |
| SRV-P2-1 | Diagnostics binds DTO without `@Valid`/size caps; truncates after binding (body-memory, not just table) | server | P2 | **CODEABLE** — `@Valid` + Bean Validation size caps + request-size limit at proxy |
| AND-P2-1 | Offline catalog `LIKE` treats `%`/`_` as wildcards | android | P2 | **DONE (#145)** — `escapeLike` + `ESCAPE '\'` |
| OPS-P1-1 | Deploy provenance exists but is manual (`:latest`, manual compose) | ops/infra | P1 | **PARTIAL (#143)** — both image workflows surface `repo@sha256:digest` to the job summary for pinning; the full `deploy <digest>` script (cosign + `gh attestation verify` + pg_dump + probes + reject `:latest`) remains OWNER |
| OPS-P1-2 | Backups default same-disk; S3 optional; backup SA has folder-wide `storage.admin` | infra | P1 | **OBSOLETE as written (#143)** — the YC SA/S3/tfstate bucket is gone; re-homed as `deploy/backup.sh` (daily `pg_dump` + 14-day rotation, systemd timer). Off-box copy + restore-rehearsal = upgrade path. |
| OPS-P1-3 | SSH open to world, serial console on, no Docker log rotation, no external alerting | infra/server | P1 | **MIXED** — Docker `json-file` max-size/file + Micrometer Prometheus registry are CODEABLE; SSH-restrict/bastion + blackbox probe need OWNER (their IP/infra) |
| OPS-P1-4 | 4GB VM caps (~3.4GiB) tight with OCR on the hot host; OCR `cpus:1.5` on ~1 vCPU | infra | P1 | **OWNER** — OCR best-effort/off hot path; lower heap or raise VM; add load-smoke |
| OCR-P2-1 | OCR `/health` can lie after worker rebuild (`_ready` stays true, new worker unwarmed) | ocr | P2 | **CODEABLE** — on rebuild set `_ready=False`, warm, then true; or split `/live` vs `/ready` |
| OCR-P2-2 | Dockerfile Python 3.14 vs tests/OSV 3.12; stale comment | ocr/ci | P2 | **CODEABLE (tiny)** — align test/OSV to runtime major.minor; fix comment |
| OPS-P2-1 | OSV covers Gradle/Python deps, not OS/container layers | ci | P2 | **CODEABLE** — add pinned Trivy action for server + OCR images |

**Do-not-build (confirmed):** keep PG `pg_trgm` (no Meilisearch/ES), keep Bucket4j + IPAddress + Ackpine + local enum state machine (no Spring Statemachine), no CMS/admin UI, no Kafka/Rabbit/Redis, no scraper crawler until deploy/API/Android gates close.

## Code-level second pass (2026-06-23, at `8cda124`)

From `2026-06-23-code-level-second-pass-review.md`. Verdict unchanged (architecture right; gaps are
implement/deploy/verify). Closed since `971af4d`: SRV-P1-1 (#133), REL-P0-1 chmod (#133), SRV-P2-1 (#134),
OCR-P2-1/P2-2 + OPS-P1-3 log-rotation (#135), OPS-P2-1 Trivy (#136). New findings (all CODEABLE, no design
change, no new dependency):

| ID | Finding (short) | Surface | Severity | Status |
|---|---|:--:|:--:|---|
| SRV-P1-3 | `applyApprovedNew` check-then-insert: dedup_key race poisons whole apply tx (no lock/catch) | server | **P1** | **DONE (#137)** — flush wrapped, `DataIntegrityViolationException`→`CanonicalUpsertConflictException` (race aborts+retries; pre-check quarantines on retry) |
| SRV-P1-5 | `match_decision.import_run_id` has no index (apply-phase filter seq-scans) | server | **P1** | **DONE (#137)** — V17 `match_decision_import_run_idx` |
| SRV-P2-2 | `@Size` params on `TeaController` dead (class not `@Validated`) | server | P2 | **DONE (#137)** — `@Validated` + `ConstraintViolationException`→400; `@WebMvcTest` |
| SRV-P2-3 | `findIdByNormalizedName` re-derives normal form, ignores `name_norm` index (hot `/resolve`) | server | P2 | **DONE (#137)** — queries `name_norm = lower(f_unaccent(:q))` |
| SRV-P2-4 | `ReviewService.pending()` N+1 (per decision + per candidate `findById`) | server | P2 | **DONE (27c080f)** — batched: one `findAllById` records, one `findByMatchDecisionIdInOrderByRankAsc`, one `summaries()` (`findAllWithNames`); DTO byte-identical |
| SRV-P2-5 | `match_candidate.match_tier` no CHECK (twin column has one) | server | P2 | **DONE (#137)** — V17 adds the CHECK |
| SRV-P2-6 | `claimContext` `orElse(null)` degrades provenance (FK-unreachable, but wrong default) | server | P2 | **DONE (#137)** — `orElseThrow` |
| OE | server trims: HexFormat, 3 dup normalizers, dup 404 handlers, dup claim helpers, dead `CREATED` state, unused low-card indexes / `normalized_candidate` cols | server | trim | **MOSTLY DONE** — HexFormat (27c080f), claim-helper collapse + dead-param (27c080f/57de04e), 404-handler dedup (14b02eb). **Kept deliberately:** `CREATED` (documented future async-preflight state), the 3 NFD normalizers (NOT identical — MissLogService keeps diacritics on purpose). **Deferred (migration):** drop/partial the low-card indexes + confirm-or-drop `normalized_candidate` cols. |
| AND-P1-6 | Update manifest fetched over un-pinned client (live mechanism under REL-P0-2) | android | **P1** | **RESOLVED (#144)** — routed to GitHub releases / Obtainium instead of a TLS leaf pin (which a Let's Encrypt rotation would brick). Cryptographic integrity comes from the Ed25519 manifest (REL-P0-2 full path). |
| AND-P1-7 | `resumePending()` re-fires `/resolve` budget token on every board-open | android | **P1** | **DONE (#142)** — once-per-process guard (`AtomicBoolean`); test added |
| AND-P2-3 | Verified APK not deleted when install throws/cancels | android | P2 | **DONE (#142)** — download→install wrapped in `try/finally` |
| AND-P2-4 | Backup size constants inconsistent (compressed 200MB < decompressed 256MB) | android | P2 | **DONE (#142)** — documented as intentionally independent (compressed pre-check vs decompressed cap) |
| OE | android trims: `Sha256` (Okio), `delete` canonical-path, dup copy methods, single-stmt `@Transaction` | android | trim | **DONE (8070f83):** `Sha256`→Okio + `delete` canonical-path containment (a real `..`-escape hardening). **Left (low-value):** `copyIn`/`importInto` merge, single-stmt `@Transaction` removal. |
| OPS-P1-5 | Backup SA `storage.admin` at folder scope → leaked key can delete tfstate bucket (sharpens OPS-P1-2) | infra | **P1** | **CODE DONE (#143)** — SA → `storage.uploader`, bucket managed by the provider token; `tofu validate` clean. OWNER: `tofu plan` + apply (verify no bucket recreate). |
| OPS-P2-2 | Dependabot watches Docker only in `/ocr-sidecar`; Caddy/Postgres digests rot | ci | P2 | **DONE (#138)** — `/infra/deploy` docker entry |
| OPS-P2-3 | Backup prune mtime-only, no keep-last-N floor | infra | P2 | **DONE (#138)** — keep newest 3 + age-prune the rest |
| OCR-P2-3 | `description_correct.py` double-parses every candidate with pymorphy3 | ocr | P2 | **DONE (#138)** — parse once, reuse |

## Phase-1 harsh review (2026-06-23) — findings against the open PRs #128/#129/#130

From `2026-06-23-phase1-harsh-review.md`. H1/H2 are NEW (missed by the per-PR reviews); H3–H5 re-surface
known residuals. #128/#129/#130 merged; **H1/H2/H4/H5 + the #128 cross-site WIP + H7 landed as one
follow-up PR ([#131](https://github.com/MacsiaProduction/TeaTiers/pull/131)) on unified `main`**, and **H3
landed in [#132](https://github.com/MacsiaProduction/TeaTiers/pull/132)** (apply-collision quarantine). All
scheduled findings are DONE. **H6 re-investigated → confirmed benign, WONT-FIX** (accounting asymmetry, not
a publish bug; fix would risk the locked cross-run re-propose flow). **OE re-investigated → 2 of 3 ponytail
flags were false positives** (`match_candidate` + `EdgeOverloadException` are mandated/consumed); the lone
dead piece is the dup-alias repair query, kept as a security-invariant audit. See the table rows for
evidence. No further code work outstanding from this review; remaining items are OWNER OPERATIONAL only
(generate/back-up `release.jks`; rehearse V6→current on a prod-like backup + deploy by verified digest; the
"before public" Ed25519-signed update manifest per decision #119) — all already wired in-repo as far as code
allows.

| ID | Finding (short) | PR | Severity | Status |
|---|---|---|:--:|---|
| H1 | Apply gate checks the producing run for `dryRun` only — a `blockRun`'d (robots/ToS/SSRF) run's revision can publish via re-proposal | #128 | **MAJOR (new)** | **DONE (#131)** — `requireApplyAllowed` rejects producing-run `BLOCKED` (keeps `FAILED`); IT in `RevisionAndClaimsIT` |
| H2 | Active-only matcher proposes create_new for a name matching only a retracted tea → status-blind `dedup_key` collision → can't create AND can't merge (deadlock) | #129 | **MAJOR (new)** | **DONE (#131)** — V16 partial-unique `tea_dedup_key WHERE status='active'` + `findActiveByDedupKey`; IT in `RevisionAndClaimsIT` |
| H3 | `DuplicateAuthoritativeAliasException` in `writeNamesAndAliases` rolls back the WHOLE `applyRun` (all decisions), not the one colliding decision | #129 | MAJOR (operability) | **DONE (#132)** — read-only `applyCollisionReason` pre-check; `applyRun` quarantines + reports the colliding decision in `RunApplyResultDto.failures` and applies the rest (returns a reason, so the shared tx is never marked rollback-only). Integrity gates + the rare concurrent-race collision still abort the run |
| H4 | Global authoritative-alias invariant is service-layer check-then-act only — no DB index → TOCTOU under concurrent cross-run applies | #129 | MAJOR (concurrency) | **DONE (#131)** — tx-scoped advisory lock on `(locale, alias)` in `addAuthoritative` (a partial index can't reference the owner tea's status; demotion-at-tombstone infra deferred to H3-era) |
| H5 | `proposeFor` re-points a pending decision without the lock/`@Version` — a concurrent re-propose can clobber a just-committed approval (lost update) | #128/#129 | MINOR (narrow race) | **DONE (#131)** — `findByIdForUpdate` on the reused decision + re-check-then-bail if just consumed |
| cross-site | A decision's `import_run_id` can be re-pointed with no site check → a record could be applied via a run for a different `source_site` | #128 | MAJOR | **DONE (#131)** — `requireApplyAllowed` refuses a run/record site mismatch; IT in `ImportRunStateIT` |
| H7 | `deleteByMatchDecisionId` `@Modifying(clearAutomatically)` without `flushAutomatically` discards the re-pointed decision's pending UPDATE → cross-run re-propose silently lost (broke merged `main`) | auto-merge | **CRITICAL (regression)** | **DONE (#131)** — add `flushAutomatically = true` |
| H6 | Completeness gate keys on `source_record.import_run_id`; apply scopes on `match_decision.import_run_id` — scope mismatch on cross-run re-propose | #128 | MINOR (smell) | **NOTED — re-investigated 2026-06-23, confirmed benign, WONT-FIX.** Deep trace: `markReviewed` counts records staged in the run with NO terminal decision for their current revision; `applyRun` applies decisions scoped to the run. On a cross-run re-propose these diverge into an *accounting* asymmetry only — no bad content publishes (every applied decision is individually approved + revision-pinned), nothing is lost (the re-pointed decision applies under its own run), and a pending decision can never seal a run (the gate counts it unreviewed). A "fix" (scoping the gate by `match_decision.import_run_id`) would risk the locked, tested deferred-review / cross-run re-propose flow — risk without a bug. Left as-is with this evidence. |
| OE | Over-engineering (ponytail): `match_candidate` table, dup-alias repair report, `EdgeOverloadException` | #129/#130 | trim | **RESOLVED — 2 of 3 were false positives.** `match_candidate` is KEPT (mandated by FND-P1-1; written by `IdentityMatchService.persistCandidates`, read by `ReviewService.pending`→`ReviewCli`). `EdgeOverloadException` is KEPT (live path: thrown by `TeaController.search`/`resolve`, handled→503 by `CatalogExceptionHandler`, PRIV-P1-1). Only `findDuplicateActiveAuthoritativeAliases` + `DuplicateAuthoritativeAliasRow` are unconsumed — KEPT as a security-invariant audit (the only way to find pre-existing one-active-owner violations the H4 advisory lock can't heal); owner's call to wire to an ops surface or drop. Net trimmable LoC ≈ the ponytail estimate was wrong; nothing safely cut. |

## P0 — must close before ANY scraped canonical write (decision #137)

| ID | Finding (short) | Contract | Status | Closing PR / evidence |
|---|---|---|:--:|---|
| FND-P0-1 / SCR-P0-8 | Stable public id reproducible; numeric→legacy-map; visibility | C1, C3 | **DONE** | #119 (blank rebuild) + #121 (V11 production reconciliation) — both the clean-rebuild and existing-prod-row paths now yield the frozen seed UUID. Operator rehearsal-before-deploy still applies (see P0-1 row). |
| **P0-1 (2026-06-22)** | **Production-row UUID reconciliation** | C1 | **DONE (code) — operator must rehearse+deploy; P2 sub-gap folded into C4 batch** | #121 merged — V11 `SeedPublicIdReconciler` reconciles existing rows to the frozen seed UUID by `dedup_key`, rebuilds the legacy map + remaps merge pointers FK-safely, fails closed on collision / an unreconciled curated row, idempotent + no-op on blank DB. **C1.1 sub-gap (#141): DONE (#125)** — completeness gate widened to all rows whose `dedup_key ∈ seed` (not just `source='curated'`) + `failOnCollision` + seed-keyed tests added. **Owner action remains:** rehearse V6→current on a prod-like backup, then deploy by verified digest. |
| **P0-3 (2026-06-22)** | **Compact tombstone** | C3 | **DONE** | #122 merged — content-free `TeaLifecycleDto` + sealed `CatalogDetail{Full,Tombstone}`; retracted / broken-chain / retracted-survivor → 410 Gone, no content; numeric path shares it. |
| AND-P0-1 | v7 Android ref identity must be UUID, not `Long` | C2 | DONE (design) | `context/design/tea-sample-split-v7.md` amendment 2026-06-21; code at v7 impl time |
| FND-P0-2 / SCR-P0-2,3 | Robots/run-ownership/dry-run/apply-state/host/evidence gates | C4 | **DONE** | #119 + #124 (robots evidence, approval invalidation, ≤1 active run/source, terminal immutability) + **#125** (run state machine: V13 enum lifecycle `created→…→applied` with DB CHECK + row-locked `ImportRunStateMachine`; two-phase decide → `markReviewed` record-based completeness → `applyRun`; apply only from `reviewed`/`applying` for the exact revision) + **#126** (`allowed_hosts` mapped+administered with approval invalidation; `UrlSafety` SSRF/host gate via seancfoley IPAddress on every observed/robots/evidence URL; immutable `RawEvidence` written per revision + bound, NOT NULL; apply fails closed without a consistent evidence chain). |
| FND-P0-3 / SCR-P0-4 | Observation revisions stop flowing after first approval | C5 | DONE | branch `harden/scrape-foundation-137-c5c6` — immutable `source_record_revision` (V10), revision-bound decisions, correction flow, stale-approval rejection |
| SCR-P0-5 | Source identity (slug rename / new external id / redirect) not reconciled | C5 | DONE | branch `harden/scrape-foundation-137-c5c6` — slug-rename URL update + `source_record_url_history` + external-id attach + collision detection |
| FND-P0-4 / SCR-P0-6,7 | Value-bearing claims + alias promotion | C6 | **DONE** | #120 + #124 (R4) — claim model + `type` corroboration/conflict on merge, corroboration claims when sources agree, `brand` as a non-selected proposal (never auto-canonical), pessimistic target-tea lock for the selected-claim swap. Remaining nicety (not blocking): baseline claims for pre-existing curated rows (folds into PR-5/provenance work, #141). |
| FND-P0-5 / SCR-P0-9 | Operator HTTP surface unauthenticated when enabled | C7 | DONE | branch `harden/scrape-foundation-137-p0` — removed `IngestReviewController`; local review CLI + route-absence IT |

## P1 — same workstream, before second source / publication scale

| ID | Finding (short) | Status | Closing PR / evidence |
|---|---|:--:|---|
| FND-P1-1 / SCR-P1-1 | Matcher hides ambiguity (`firstOrNull`/single max); ties & multiple authoritative owners not surfaced; can propose an inactive target | PARTIAL → **PR-5** | apply-time tombstone guard landed (`InactiveMergeTargetException`, branch `…-c5c6`); **PR-5:** ranked candidate set + per-hit evidence + explicit tie/multi-owner conflict + `tea.status='active'` in the exact/trigram/authoritative SQL + a **global** authoritative-alias invariant (currently per-tea `tea_identity_alias_uk`) + dup-alias repair report; no auto-merge |
| FND-P1-2 / SCR-P1-3 | Vendor/brand semantics: `vendor` ignored; `brand` may land on canonical tea | OPEN | keep vendor on observation; brand only via explicit decision |
| FND-P1-3 / SCR — | Concurrent idempotency constraints + locking | **PARTIAL → PR-4** | V10 constraints + C4 run row-lock + C6 target-tea row-lock (#124) landed. **PR-4:** two CLI processes can load+consume the *same* pending decision — `findByIdForUpdate` / atomic `UPDATE … WHERE decision='pending'` / `@Version` on the review/approve path; assert exactly one affected row before any canonical write; two-transaction IT |
| FND-P1-4 / SCR-P1-2 | Evidence/input validation incomplete; unknown `type`→`OTHER`; country not ISO; region free-text; URL/host/evidence; lenient deserialization; **+ `allowed_hosts` administration + URL/redirect/SSRF (folded from C4/R3)** | **DONE (#126 + #127)** | **#126:** `allowed_hosts` mapped+administered (changes invalidate approval) + `UrlSafety` host/scheme/port/userinfo/fragment/IP-literal(+NAT64/6to4/TEST-NET) SSRF validation via **seancfoley/IPAddress** on every observation/robots/evidence URL + immutable `RawEvidence` proof chain bound to the revision (apply fails closed if absent/inconsistent; DNS-rebind re-resolution is the fetcher's job). **#127:** **jakarta.validation** `@Iso3166` country + `@KnownTeaType` (reject unknown `type`, no silent OTHER) + `FAIL_ON_UNKNOWN_PROPERTIES=true` on apply/review deser + `FactsValidator`/`FactsOnlyGuard` re-run at apply against the exact revision. **Deferred:** `region` stays free-text (prose-bounded) until the #138 Wikidata-QID region table (own workstream). |
| FND-P1-5 | Public lifecycle additive, not integrated (one visibility predicate everywhere) | DONE | folded into C3 (branch `harden/scrape-foundation-137-p0`) |
| AND-P1-1 | Sample/reference split (Room v6→v7), any-language names | **READY** | server prereq exists + amended v7 design; **prod UUID reconciliation (P0-1) must land first.** One doc cleanup: remove the obsolete `Long`-based sketches below the v7 amendment (or move to a labeled historical appendix) before coding |
| AND-P1-2 | Catalog DTO compat not executable (server↔app drift); Android decodes only numeric `id`, ignores lifecycle | OPEN | frozen server/app JSON fixtures in both modules + public-ID routes/lifecycle in contract tests; UUID for all new lookups; numeric compat-only |
| AND-P1-3 (2026-06-22) | Enrichment patch contradicts ownership: `applyEnrichmentPatch` prefers any nonblank catalog value over the user's + always replaces `type` | OPEN | fill blank-only or track per-field dirty/ownership; the v7 ref/sample split is the clean fix; align tests+comments to actual policy |
| AND-P1-4 (2026-06-22) | Queued enrichment not durable — app-coroutine + `resumePending()` on startup; process death/reboot strands work | OPEN — **ADOPT at v7 (#141)** | WorkManager (unique work/sample, network constraint, backoff, idempotent calls) lands with the v7 ref/sample split (Phase 2) **if** background completion is a product promise, else document the weaker app-open copy |
| AND-P1-5 (2026-06-22) | Unbounded image/file reads — `readBytes()` before bounds; no byte cap/MIME check on photo copy/import; prefix-based delete; URIs/paths logged in release | OPEN | bounded-stream caps, safe bounds/MIME decode, canonical-path boundary, redact URIs/paths |

## Release / privacy / server / ops — before next public APK (not ingestion-blocking)

| ID | Finding (short) | Status |
|---|---|:--:|
| REL-P0-1 | Release-signing recovery is a single point of failure (operator `release.jks` 0644, no tested recovery) | OPEN (owner) |
| REL-P0-2 | Custom updater trusts server-selected trust data | OPEN |
| REL-P1-1 | `release.yml` not a complete release gate (tag/version/versionCode/package/cert/emulator/SBOM) | OPEN |
| PRIV-P0-1 | Privacy copy/retention vs behavior (geopoints copy, scattered egress, miss-text retention, raw query in `ResolveService` conflict log, diagnostics binds before `@Size`) | OPEN | one data-flow table; cap/normalize+age-purge miss text; redact logs; validate diagnostic body before alloc |
| PRIV-P1-1 (2026-06-22) | Caffeine per-client rate-limit eviction resets abuse budgets | OPEN → **PR-6** | adopt **Bucket4j** core + caffeine integration (refill survives eviction) + one global edge bucket for `/resolve`+`/search`; keep expensive-op global semaphores (no Spring-Boot-starter/Redis at this scale) |
| SRV-P1-1 | LLM activation has unsafe dormant behavior (run 14 reserved) | DEFERRED (no LLM key) |
| SRV-P2-1 | Diagnostics binds oversized body before truncating (`@Valid` + caps) | OPEN |
| OCR-P2-1 | CI/runtime Python version drift (3.12 vs 3.14) | OPEN |
| AND-P2-1 | Offline catalog detail incomplete (cache last detail by UUID) | OPEN |
| AND-P2-2 | Remote catalog images must be first-party / disclosed (no hotlinking) | DEFERRED (no images yet) |
| OPS-P1-1 | Image identity produced but not enforced at deploy; ~~live prod is behind current migrations/contracts~~ (RESOLVED #143 — prod on V7–V17, verified live 2026-06-24) | **OPEN** — only the digest-enforced deploy script remains (Komodo redeploys are manual) |
| OPS-P1-2 | Backups: broad creds, no enforced restore evidence | **RE-HOMED (#143)** — `deploy/backup.sh` daily dump; off-box + restore-evidence = upgrade |
| OPS-P1-3 | Single-host: SSH `0.0.0.0/0`, no external alerts, no log rotation; add Micrometer Prometheus + Docker `max-size` | OPEN (owner) |
| OPS-P1-4 (2026-06-22) | 4 GB VM over-committed (~3420 MiB declared) + `core_fraction=50` ≈ 1 vCPU vs OCR `cpus:1.5` → JVM starvation/OOM under load | OPEN (owner) | trim server heap or raise `core_fraction`/resize; treat OCR as off the hot path |
| OPS-P2-1 | Dependency scan doesn't cover OS/container layers (add Trivy) | OPEN |

## Research ideas (2026-06-22 GLM/MiniMax review) — only 3 worth carrying

The new GLM/MiniMax answers changed no decision (see `2026-06-22-research-glm-minimax-review.md`). Three
genuinely-new ideas survived the strict filter:

| ID | Idea | Touches | Disposition |
|---|---|:--:|---|
| RES-1 | Curated ~300-term tea glossary (Хун, Гунфу, Да Хун Пао…) to guard the dict-gated OCR corrector from mangling domain terms | #123 OCR correction | **ADOPT** — pre-commit before OCR-correction Phase 1 ships (low cost) |
| RES-2 | MiniMax `scrape_upsert_canonical()` plpgsql as a reference sketch for source-record-keyed upsert | #136/#137 | **SPIKE only** — reconcile with the locked dedup arch (pypinyin + pg_trgm @0.3) before any use |
| RES-3 | Hilt provider scaffolding for map providers (`@Provides` + named list, configurable style URL) | #9 map abstraction | **ADOPT as reference** when map work resumes post-MVP (M6); not in scope now |

## Scraper module (SCR-P0-1) — deferred until the "ready for scraper" gate is met

> Gate = `2026-06-22-full-architecture-design-review.md` §"Definition of ready for scraper": deterministic
> rehearsed prod UUIDs; source-change approval invalidation + enforced hosts/redirects; fresh run-bound
> robots/ToS evidence; locked apply-authorized run state + immutable terminals; no dry/unreviewed/stale
> write; type/country/region cannot coerce to free text; ambiguity returns candidates; every canonical
> field has a reviewed claim; retraction removes content but keeps identity; idempotent rerun + withdrawal.


| ID | Finding | Status |
|---|---|:--:|
| SCR-P0-1 | No executable scraper boundary (module/lockfile/fetch/parser/fixtures) | DEFERRED — "the next deliverable is not a crawler" |
| SCR-P1-4 | One canonical row can't express unresolved field conflicts | DEFERRED (needs value-bearing claims = C6 first) |
| SCR-P1-5 | Stored normalization hints diagnostic only; PostgreSQL owns match normalization | NOTED (already correct in code; preserve) |
