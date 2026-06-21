# Review finding index — active status map

Authoritative tracker for the **current** review findings (post-scrape-foundation, 2026-06-21).
Older review files remain as evidence/history; this index reflects what is still open against `main`.

- Locked intent: `context/decisions.md` #136 (foundation) + **#137** (correction; the C1–C7 contracts).
- Source reviews:
  - `2026-06-21-post-scrape-foundation-architecture-review.md` (FND-/AND-/REL-/PRIV-/SRV-/OCR-/OPS-)
  - `2026-06-21-scraper-implementation-readiness-review.md` (SCR-)
- Status legend: `OPEN` · `WIP` (in progress this workstream) · `DONE` (landed + tested) · `DEFERRED` (gated, not now).

Baseline at index creation: `21fda80`. Update the Status/Evidence columns as PRs land.

## P0 — must close before ANY scraped canonical write (decision #137)

| ID | Finding (short) | Contract | Status | Closing PR / evidence |
|---|---|---|:--:|---|
| FND-P0-1 / SCR-P0-8 | Stable public id not reproducible from seed; numeric detail bypasses legacy map; merged/retracted leak into search | C1, C3 | OPEN | server PR — seed UUIDs + legacy-map resolve + visibility predicate |
| AND-P0-1 | v7 Android ref identity must be UUID, not `Long` | C2 | DONE (design) | `context/design/tea-sample-split-v7.md` amendment 2026-06-21; code at v7 impl time |
| FND-P0-2 / SCR-P0-2,3 | Robots/run-ownership/dry-run are metadata, not enforced gates | C4 | OPEN | server PR — `ImportRun` state machine + robots snapshot + `ingest()` run/site/dryRun checks |
| FND-P0-3 / SCR-P0-4 | Observation revisions stop flowing after first approval | C5 | OPEN | server PR — immutable revisions keyed by content hash; revision-bound decisions |
| SCR-P0-5 | Source identity (slug rename / new external id / redirect) not reconciled | C5 | OPEN | same PR as FND-P0-3 — URL alias/history + collision check |
| FND-P0-4 / SCR-P0-6,7 | Merge omits scalar provenance; provenance has no value; approved aliases stay unverified | C6 | OPEN | server PR — field-claim model + same-tx selected value + `addAuthoritative` promotion |
| FND-P0-5 / SCR-P0-9 | Operator HTTP surface unauthenticated when enabled | C7 | OPEN | server PR — remove `IngestReviewController`; local review CLI/profile |

## P1 — same workstream, before second source / publication scale

| ID | Finding (short) | Status | Closing PR / evidence |
|---|---|:--:|---|
| FND-P1-1 / SCR-P1-1 | Matcher hides ambiguity (`firstOrNull`/single max); ties & multiple authoritative owners not surfaced | OPEN | matcher returns ranked candidate set + conflict flags |
| FND-P1-2 / SCR-P1-3 | Vendor/brand semantics: `vendor` ignored; `brand` may land on canonical tea | OPEN | keep vendor on observation; brand only via explicit decision |
| FND-P1-3 / SCR — | DB constraints don't support concurrent idempotency (no unique `normalized_candidate.source_record_id`, no one-pending-per-revision, no provenance uniqueness; no row locks) | OPEN | Flyway constraints + row-lock/CAS in review/apply |
| FND-P1-4 / SCR-P1-2 | Evidence/input validation incomplete; `raw_evidence` never written; unknown `type` silently `OTHER`; approval doesn't re-run guard | OPEN | strict schema + URI/host validation + type mapping queue + evidence persist |
| FND-P1-5 | Public lifecycle additive, not integrated (one visibility predicate everywhere) | OPEN | folded into C3 |
| AND-P1-1 | Sample/reference split is the next Android migration (Room v6→v7) | DEFERRED | gated behind C1/C2 server work + amended v7 design |
| AND-P1-2 | Catalog DTO compat not executable (server↔app drift) | OPEN | frozen JSON contract fixtures |

## Release / privacy / server / ops — before next public APK (not ingestion-blocking)

| ID | Finding (short) | Status |
|---|---|:--:|
| REL-P0-1 | Release-signing recovery is a single point of failure (operator `release.jks` 0644, no tested recovery) | OPEN (owner) |
| REL-P0-2 | Custom updater trusts server-selected trust data | OPEN |
| REL-P1-1 | `release.yml` not a complete release gate (tag/version/versionCode/package/cert/emulator/SBOM) | OPEN |
| PRIV-P0-1 | Privacy/diagnostics/capability copy inconsistent with deployed behavior | OPEN |
| SRV-P1-1 | LLM activation has unsafe dormant behavior (run 14 reserved) | DEFERRED (no LLM key) |
| SRV-P2-1 | Diagnostics binds oversized body before truncating (`@Valid` + caps) | OPEN |
| OCR-P2-1 | CI/runtime Python version drift (3.12 vs 3.14) | OPEN |
| AND-P2-1 | Offline catalog detail incomplete (cache last detail by UUID) | OPEN |
| AND-P2-2 | Remote catalog images must be first-party / disclosed (no hotlinking) | DEFERRED (no images yet) |
| OPS-P1-1 | Image identity produced but not enforced at deploy (digest-only verified deploy) | OPEN |
| OPS-P1-2 | Backups: broad creds, no enforced restore evidence | OPEN |
| OPS-P1-3 | Single-host: SSH `0.0.0.0/0`, no external alerts, no log rotation | OPEN (owner) |
| OPS-P2-1 | Dependency scan doesn't cover OS/container layers (add Trivy) | OPEN |

## Scraper module (SCR-P0-1) — deferred until the P0 set above is closed

| ID | Finding | Status |
|---|---|:--:|
| SCR-P0-1 | No executable scraper boundary (module/lockfile/fetch/parser/fixtures) | DEFERRED — "the next deliverable is not a crawler" |
| SCR-P1-4 | One canonical row can't express unresolved field conflicts | DEFERRED (needs value-bearing claims = C6 first) |
| SCR-P1-5 | Stored normalization hints diagnostic only; PostgreSQL owns match normalization | NOTED (already correct in code; preserve) |
