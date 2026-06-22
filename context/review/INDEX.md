# Review finding index — active status map

Authoritative tracker for the **current** review findings. Older review files remain as evidence/history;
this index reflects what is still open against `main`.

- Locked intent: `context/decisions.md` #136 (foundation) + **#137** (C1–C7 contracts) + **#139** (2026-06-22
  correction: the production-safe bar — several #137 "DONE" closures reopened).
- Source reviews:
  - `2026-06-21-post-scrape-foundation-architecture-review.md` (FND-/AND-/REL-/PRIV-/SRV-/OCR-/OPS-)
  - `2026-06-21-scraper-implementation-readiness-review.md` (SCR-)
  - `2026-06-22-full-architecture-design-review.md` (full re-assessment after #119/#120 merged; P0-1..P0-5, P1-1..P1-14)
  - `2026-06-22-research-glm-minimax-review.md` (independent re-analysis of the new GLM/MiniMax answers; no decision changes)
  - `2026-06-22-post-hardening-current-state-refresh.md` (refresh after #121/#122/#124; reopens the incomplete C4 apply-state contract)
  - `2026-06-22-oss-reuse-architecture-review.md` (same reopen via an open-source-reuse lens; library decisions in #141)
- Status legend: `OPEN` · `WIP` · `PARTIAL` (core landed, contract not fully met) · `REOPEN` (was marked DONE,
  re-opened by a later review) · `DONE` (landed + tested) · `DEFERRED` (gated, not now).

Baseline at index creation: `21fda80`. **Re-assessed against `a622da0` (2026-06-22) by two independent
reviews** (post-hardening refresh + OSS-reuse pass). PRs #119/#120 landed the C1–C7 work at the
*blank-rebuild / core-invariant* bar; #121/#122/#124 closed several production gaps. Both new reviews reopen
C4's apply-state portion: safe preflight/terminal handling landed, but no reviewed/apply-authorized run state
exists and `allowed_hosts`/SSRF is unenforced. **Decision #141 locks Phase 1 (everything) as the next
workstream** — a sequence of PRs (C4 state machine → SSRF/evidence → strict validation → review CAS →
ranked-candidate matcher → Bucket4j), built with adopted libraries. Update Status/Evidence as PRs land.

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
| OPS-P1-1 | Image identity produced but not enforced at deploy; **live prod is behind current migrations/contracts** | **OPEN — urgent** |
| OPS-P1-2 | Backups: broad creds, no enforced restore evidence | OPEN |
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
