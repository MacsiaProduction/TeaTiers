# Old reviews — compacted archive (2026-07-08, extended 2026-07-10)

> One-file condensation of all review docs prior to the round-4 usage-quality audit. Originals removed
> from the tree (recoverable via git history). Live finding status: `INDEX.md`. Round-1/2/3 UX finding
> details preserved below because later rounds carry some items forward.

Sections below are chronological. For each pre-archived (`archive-2026-06/`) doc, one short paragraph
is given (they were already dispositioned before archiving). For the eleven top-level dated reviews,
a fuller essence + compact finding list is given, with the three usage-quality plans kept in full detail
(tables, every finding ID) since later rounds reference them directly.

---

## Archived reviews (originally `archive-2026-06/`)

### 2026-06-17 — architecture-review
First formal review. Confirmed local-first/no-account architecture as correct. Found search lacked
typo tolerance (`LIKE '%q%'` despite `pg_trgm` present), backup dropped v5 enrichment fields, Room used
destructive migration pre-launch, `/resolve` `sourceText` was accepted but ignored, catalog image model
lagged the app's photo-list UI, and the release gate wasn't explicit. Recommended pg_trgm fuzzy search,
WorkManager for durable enrichment, GHCR migration, off-box backups, curated-seed expansion. All P0/P1
items were tracked in the companion disposition file and resolved within days (decisions #69–#95).

### 2026-06-17 — disposition
Tracking doc, not itself a review: records disposition of every 06-17-architecture-review finding.
Search fixed via #84 (pg_trgm+f_unaccent+word_similarity); backup v5 fields fixed; Room
destructive-migration cutover explicitly deferred to M5 (#70.1); WorkManager background durability
deferred as open decision #70.6; catalog image list shipped (#75); `catalogTeaId`-first dedup shipped
(#72); global LLM daily budget shipped (#71); GHCR migration and off-box backup shipped (#76/#77). Two
items left as open owner decisions: curated-seed expansion pace and en/zh UI timing.

### 2026-06-17 — full-design-architecture-review
Second full pass after decisions #70–#86, confirming most 06-17 items resolved (privacy copy, typo
search, backup v5, resolve/pending state via #78, GHCR, off-box backup). New findings: Room destructive
migration still the public-release blocker (kept deliberately per #70.1); queued enrichment still
app-open-only, not WorkManager-durable; `sourceText` grounding existed server-side with no add-flow UI
field (fixed shortly, #87); catalog reference flavor vs. user rating not shown together (fixed #90);
backend image-list vs. app single-image consumption gap (fixed #89); LLM production enablement gated on
billing/IAM, not just code; seed still far below the 300-tea target. All closed within the week.

### 2026-06-17 — second-pass
One-hour refresh after PRs #36–#39 merged; confirmed backup/async/dedup fixes landed. Found one new P0:
a `FAILED` server enrichment stub could be silently mapped to local `DONE` when the LLM tier was
disabled or the daily budget was exhausted (`ResolveService.cacheHit()` → `applyPatch()` always wrote
`DONE`), hiding the retry affordance. Also flagged `catalogTeaId` dedup as app-layer only, no DB unique
index yet. Both fixed shortly after (index added #78; FAILED-state mapping corrected).

### 2026-06-18 — current-design-architecture-review
Post decisions #85–#95. Confirmed typo search, `sourceText` grounding, image list, reference-vs-mine
flavor display, honest app-open enrichment copy, and the 100-tea seed all live. New P0: global privacy
copy still said "only the typed name is sent" after `sourceText` shipped (fixed, folded into #96). Also
flagged Room destructive migration (deferred), missing CI dependency/security scanning (later
OSV-Scanner, #96), backup import/export loading full photo payloads into memory (later streamed/bounded,
#97), and public deploy SSH/Caddy-header hardening gaps (later addressed).

### 2026-06-18 — post-ocr-architecture-review
First deep code-level pass covering the newly-merged OCR sidecar contract (#99). Flagged that the
decision text framed server-side OCR as "user choice" without acknowledging it reversed research run
10's on-device recommendation — the one genuinely undecided architectural fork, later resolved by
disclosure updates in #107. New P1s: duplicate-`catalogTeaId` enrichment permanently dead-ends a tea
(fixed #101), OCR rate limit shared with `/resolve` and charged before validation (fixed #103), zero
container privilege hardening ahead of the untrusted-image sidecar (fixed #102), no measured VM memory
headroom for OCR, OSV-Scanner decided but not wired (fixed #96/#102). Also flagged photo-orphan growth,
unpooled HTTP clients, LLM budget under-count/4xx-retry, and an unvalidated OCR sidecar URL — all closed
across #100–#138.

### 2026-06-18 — ocr-workstream-complete-review
Extends the post-OCR review to the code that landed after it (#101–#107: OCR sidecar, scan UI, container
hardening). Re-verified every prior fix held. New robustness gaps unique to the now-live sidecar: no
`Image.MAX_IMAGE_PIXELS` guard (decompression-bomb DoS, later fixed in the OCR pixel-budget work), no
EXIF-orientation handling (rotated camera scans OCR'd sideways — fixed, confirmed closed by the 06-19
review), a flat Docker network letting the sidecar reach Postgres directly (fixed via network
segmentation, confirmed closed 06-19), no global OCR concurrency bound, and an over-strict Wikidata
zh-label SPARQL filter. Scaffolded research run 15 (crash telemetry), later resolved via ACRA (#111).

### 2026-06-19 — full-architecture-design-review
Fresh code-led pass (decisions #1–#113), confirming EXIF/URL-validation/network-segmentation fixes held.
Verdict: no redesign needed; headline concern is catalog breadth once the AI tier is off (nothing grows
the catalog from usage while `/resolve` enrichment is dormant) — scaffolded research run 16, later
judged and implemented as the miss-log-driven seed pipeline (#116/#117). Also flagged device-loss
recovery resting on a manual export ritual, `plan.md` doc drift, no product-usage telemetry, no curation
queue for unverified rows, no API-versioning policy, and no RU privacy-policy/legal artifact. Numerous
smaller app/server/infra P2/P3s (non-transactional enrichment merge, backup-import photo-orphan leak,
OCR inference deadline, Caddy body-cap/headers) were fixed within days.

### 2026-06-19 — current-state-refresh
Post-review-program refresh after decision #117 implemented the miss-log/seed-catalog growth strategy.
Confirms Wikidata resolve, typo search, OCR bounds/isolation, Caddy hardening, atomic off-box backups,
and the catalog miss log all live. Remaining blockers are release discipline, not redesign: Room still
destructive-migrates (public-release blocker), the planned ACRA/wipe-sentinel diagnostics isn't built
yet, English is offered in the locale picker before `values-en` exists, the miss-log has no operator
workflow, image publishing lacks signing/provenance, and the dormant AI tier has enable-day gaps (retry/
budget accounting, stale-`PENDING` recovery). All subsequently addressed across #108–#144.

### 2026-06-21 — full-architecture-design-review (archived)
First review after the public v0.1.0 signed release (baseline `a76b888`) — the "no longer pre-release"
turning point. Three new release blockers: Room still permits destructive migration on a public APK
(P0-1, since resolved for v6), privacy copy doesn't match miss-log retention/LLM-promotion behavior
(P0-2), release-key recovery has no tested path / keystore `0644` (P0-3, remained open through many
later reviews, chmod'd in #133, offline backup+drill still owner-pending). Introduced the
canonical-tea-vs-personal-sample split as the key product-model correction (became the locked v7 design,
decision #132). Also flagged the updater's server-selected trust anchor (became REL-P0-2, still open),
OCR timeout non-termination (fixed via killable process, later further hardened), and single-host ops
gaps. Superseded three days later by the post-fix refresh below.

### 2026-06-21 — catalog-scraping-plan-review (archived)
Dedicated review of research run 20 and decision #131's scraping plan (baseline `9ff8ed2`), well before
the scrape-foundation code landed. Central finding: `dedup_key` is not cross-script canonical identity,
source-record idempotency was missing, `CatalogSeeder` is insert-or-skip not an upsert, multi-source
provenance wasn't representable, and stable public catalog identity was unresolved — the direct ancestor
of the later FND-P0-1/P0-2/P0-4 findings in the 06-21/06-22 scrape-foundation reviews, closed by the
C1–C7 contracts (decision #137) and PRs #119–#127. Recommended httpx+selectolax for a fixed-URL pilot
and Scrapy only for later full traversal — this tool split was adopted and still holds.

### 2026-06-21 — post-fix-current-state-refresh (archived)
Refresh after PRs #108–#113 fixed Room v6 export, miss-log retention/privacy copy, streamed backups,
bounded rate-limiter memory, and killable OCR timeout handling. Surfaced a new regression: deleting the
last board reseeds sample data on next process start (no onboarding marker) — fixed shortly after.
Confirmed the v7 sample/reference split design (decision #132) as sound but not yet built, the updater
as still trust-anchor-less, and diagnostics/OCR/deploy-provenance gaps as still open. This file was the
primary input the third-pass synthesis below adversarially re-verified.

### 2026-06-21 — third-pass-synthesis-adversarial (archived)
Independent adversarial synthesis of the two prior 06-21 passes plus 8 fresh component reviewers, same
`9ff8ed2` baseline. Confirmed almost all prior findings but overturned one: decision #98's claim that
Caddy strips client-supplied `X-Forwarded-For` is **false** — `reverse_proxy` appends rather than strips,
so the backend's first-hop read was spoofable and every cost limiter (`/resolve`, `/ocr`, `/search`) was
bypassable (reopened #98, later fixed). New findings: a backup export/import self-inconsistency could
make one stale photo path un-restore the whole archive; prod Compose delivered no LLM key/diagnostics
token/update vars to the server; the LLM prompt injected the user's typed name unfenced (prompt-injection
risk); LLM stubs were immediately public/searchable while PENDING/FAILED, contradicting consent copy; the
OCR sidecar had two real wedge modes invisible to `/health`; and the Add/Edit form was wiped on
rotation/process death with no `SavedStateHandle`. Corrected the prior reviews' committed-tfstate concern
as a false premise (file is gitignored, never in history). Most P1s here were resolved over the following
weeks per later reviews' "resolved since" sections.

---

## Top-level dated reviews (2026-06-21 to 2026-06-23)

### 2026-06-21 — post-scrape-foundation-architecture-review
Full current-state review at `21fda80` after decision #136 (scrape foundation). Verdict: core
architecture (local-first Android, Spring/PostgreSQL catalog, `pg_trgm` search, offline operator
ingestion, one Compose host) is right; the newly-built scrape foundation is directionally correct but
**not safe for a public catalog import yet**. Introduced FND-P0-1..P0-5 plus FND-P1-1..P1-5 and
Android/release/ops findings, establishing the C1–C7 acceptance-gate contracts later locked as decision
#137.

- **FND-P0-1** (stable catalog identity not end-to-end/rebuild-stable) → became **C1**; **DONE**
  (#119+#121, V11 reconciler); production deploy rehearsal was the residual owner action, resolved live
  #143.
- **FND-P0-2** (robots/run-ownership/dry-run not enforced) → became **C4**; reopened repeatedly through
  06-22/06-23 for the missing state machine; **DONE** via #125/#126 (run state machine + allowed_hosts/
  SSRF).
- **FND-P0-3** (observation revisions stop after first approval) → became **C5**; **DONE** (branch
  `harden/scrape-foundation-137-c5c6`).
- **FND-P0-4** (merges omit scalar provenance/alias promotion) → became **C6**; **DONE** (#120+#124).
- **FND-P0-5** (operator HTTP surface unauthenticated) → became **C7**; **DONE**, controller removed.
- **FND-P1-1** (matcher hides ambiguity) → **PARTIAL**, ranked-candidate matcher + `match_candidate`
  table landed via #129 (curated-baseline-claims nicety still tracked).
- **FND-P1-2** (vendor/brand semantics) → **DONE** (#124: brand non-selected proposal, vendor
  observation-only).
- **FND-P1-3** (no concurrent idempotency constraints) → **PARTIAL** long-term (schema constraints via
  V10; review-decision locking via #128's CAS).
- **FND-P1-4** (evidence/input validation incomplete) → **DONE** (#126 SSRF/evidence + #127
  jakarta.validation/ISO-3166/`FAIL_ON_UNKNOWN_PROPERTIES`); region stays free-text pending #138.
- **FND-P1-5** (public lifecycle additive not integrated) → **DONE**, folded into C3 tombstone work.
- **AND-P0-1/P1-1** (v7 Android ref identity, sample/reference split) → design amended; code **NOT
  STARTED** as of latest INDEX (AND-P0-2).
- **REL-P0-1/P0-2** (release-key recovery, updater trust) → REL-P0-1 chmod **DONE** (#133), offline
  backup+drill still **OWNER**; REL-P0-2 short path **DONE** (#144, GitHub/Obtainium), full Ed25519
  manifest **OPEN**.
- **OPS-P1-1..P1-3** (deploy digest enforcement, backup IAM, single-host ops) → progressively closed
  across #135/#136/#138/#143; OPS-P1-1 (digest-enforced deploy script) still **OPEN**.

### 2026-06-21 — scraper-implementation-readiness-review
Companion review scoped to the ingestion/scraper pipeline (source policy through canonical apply).
Verdict: **no scraper implementation exists yet**, only a server-side foundation; a fixed 30–50 page Art
of Tea parser spike is conditional-GO, but staging into the DB and any canonical publication remain
NO-GO until run/robots/revision invariants are fixed. SCR-P0-1..P0-9 mirror/extend FND-P0-1..P0-5 at an
implementation-specific level; SCR-P1-1..P1-5 cover matcher ambiguity, type coercion, brand/vendor,
unresolved field conflicts, and normalization-hint reproducibility.

- **SCR-P0-1** (no executable scraper module) → still **DEFERRED** ("the next deliverable is not a
  crawler").
- **SCR-P0-2/P0-3** (robots hard gate, run/dryRun state) → same fate as C4 above: **DONE** #125/#126.
- **SCR-P0-4** (corrections can't re-review after approval) → **DONE**, c5c6 branch.
- **SCR-P0-5** (source identity/redirect reconciliation) → **DONE**, same branch.
- **SCR-P0-6/P0-7** (merge provenance, alias promotion) → **DONE** (#120/#124).
- **SCR-P0-8** (stable client identity) → **DONE** (#119/#121).
- **SCR-P0-9** (operator review auth) → **DONE**, controller removed.
- **SCR-P1-1** (matching hides multiple identities) → **PARTIAL**→ranked matcher landed #129.
- **SCR-P1-2** (type/country/region silently coerced) → **DONE** (#127); region **DEFERRED** pending
  #138.
- **SCR-P1-3** (brand/vendor semantics) → **DONE** (#124).
- **SCR-P1-4** (one canonical row can't express conflicts) → **DEFERRED** (needs C6 value-bearing claims
  first).
- **SCR-P1-5** (normalization hints diagnostic only) → **NOTED**, already correct in code, preserve.

### 2026-06-22 — full-architecture-design-review
Full re-assessment at `399ed92` after #119/#120 merged the C1–C7 work at the "blank-rebuild" bar.
Materially stronger than 06-21, but 5 blockers dominate: production catalog identity not reproducible
(live DB still pre-V7 — the first real-world discovery of the C1 production-reconciliation gap), import
gate incomplete, tombstones expose full content on retracted/broken-chain merges, updater has no
independent trust root, Android still conflates canonical tea with physical sample. Triggered #121
(seed-UUID reconciler), #122 (compact tombstone), #124 (ingestion gates + C6 refinements) same day.

- **P0-1** (production UUID continuity not closed) → **DONE** (#121, V11 `SeedPublicIdReconciler`);
  production rehearsal was residual owner action, resolved live #143.
- **P0-2** (reopen C4) → **DONE** eventually via #125/#126.
- **P0-3** (compact tombstone not implemented) → **DONE** (#122, `CatalogDetail.Tombstone`, 410).
- **P0-4** (updater manifest no independent trust root) → became REL-P0-2; short path **DONE** (#144),
  full manifest **OPEN**.
- **P0-5** (release-signing recovery SPOF) → became REL-P0-1; chmod **DONE** (#133), drill **OWNER**.
- **P1-1** (C6 provenance/merge semantics incomplete) → **DONE** (#124), curated-baseline-claims nicety
  open.
- **P1-2** (matcher hides ambiguity) → **PARTIAL**→#129.
- **P1-3** (review concurrency) → confirmed **PARTIAL**, index corrected accordingly.
- **P1-4** (strict observation validation) → **DONE** (#126/#127); region deferred to #138.
- **P1-5** (API transition needs executable contract) → still **OPEN** (AND-P1-2, Android decodes
  numeric id only).
- **P1-6** (Android canonical-ref/physical-sample split) → design-ready; code **NOT STARTED**
  (AND-P0-2).
- **P1-7** (enrichment patch contradicts ownership) → still **OPEN/CODEABLE** (AND-P1-1/AND-P1-3).
- **P1-8** (queued enrichment not durable) → still **OPEN/CODEABLE** (AND-P1-2), WorkManager deferred.
- **P1-9** (unbounded image/file reads) → still **OPEN/CODEABLE** (AND-P1-3/AND-P1-5).
- **P1-10** (privacy copy/retention mismatch) → **PARTIAL**, miss-log retention still CODEABLE/policy
  (PRIV-P1-1).
- **P1-11** (rate-limit eviction resets budgets) → **DONE** (#130, Bucket4j).
- **P1-12/13/14** (deploy image enforcement, backup IAM, single-host gaps) → progressively closed
  #135/#136/#138/#143; OPS-P1-1 (digest-enforced deploy script) remains **OPEN**.

### 2026-06-22 — oss-reuse-architecture-review
Same-day follow-up at `a622da0` after #121/#122/#124 landed, adding an explicit open-source-reuse lens.
Verifies the three PRs' closures and finds two overstatements: **C4 (run state machine + allowed_hosts/
SSRF) was NOT actually built** despite being marked DONE, and the C1 reconciler's completeness check only
scanned `source='curated'` rows (P2 gap). Headline recommendation: adopt Bucket4j, seancfoley IPAddress,
a tiny enum state machine (reject Spring Statemachine — EOL, no Boot 4 support), jakarta.validation, and
WorkManager — each closes an open finding while deleting hand-rolled code.

- C1 sub-gap (curated-only completeness scan) → **DONE** (#125, gate widened to all seed dedup_keys +
  `failOnCollision`).
- C4 PARTIAL correction → confirmed, eventually **DONE** (#125/#126).
- C6 nicety (curated rows lack baseline claims) → still open, folds into future provenance work (#141).
- Bucket4j / seancfoley IPAddress / jakarta.validation adoption → all **DONE** (#130 / #126 / #127).
- WorkManager adoption → still **DEFERRED/OPEN** (AND-P1-2), pending v7.
- Micrometer Prometheus registry → **CODEABLE/OPEN** (OPS-P1-3, mixed).
- Trivy container scan → **DONE** (#136).
- cosign+gh-attestation deploy script → **PARTIAL** (#143 surfaces digests to job summary; full script
  still **OPEN**, OPS-P1-1).

### 2026-06-22 — post-hardening-current-state-refresh
Parallel same-day refresh at `a622da0`, independently reaching the identical C4-not-actually-done
conclusion (labeled ING-P0-1) plus CAT-P1-1/P1-2/P1-3 (matcher ambiguity, review concurrency, incomplete
field decisions) and the Android/release/ops findings restated as P0-1..P0-3 (production still
pre-public-ID, release-key SPOF, updater trust). Confirms C1/C3 code-closed but production not yet
deployed live at review time.

Disposition mirrors the OSS-reuse review above for shared items. **CAT-P1-1** (matcher) → ranked matcher
landed #129. **CAT-P1-2** (review concurrency) → **PARTIAL**, further addressed by #128's CAS lock.
**CAT-P1-3** (field-decision model incomplete) → open nicety, folds into #141. **P0-1** (production
behind contract) → **RESOLVED** live 2026-06-24 (#143, per INDEX).

### 2026-06-22 — research-glm-minimax-review
Independent adversarial re-analysis of newly-added GLM-4.7/MiniMax research answers across runs 01–21,
complementing the same-day full-architecture-design-review's research section. Of 21 runs only 6 had new
GLM/MiniMax answers; **zero answers overturned a locked decision**, and where the new models diverged
from locked decisions they were wrong (MiniMax mislabeled Wikidata's license, recommended
Yandex-as-MVP/map-in-MVP against #9/#20; GLM moved normalize+match into Spring against #123). Verdict: no
new research run warranted. Three genuinely new ideas carried forward:

- **RES-1** (curated ~300-term tea glossary to guard the OCR corrector) → **ADOPT**, pre-Phase-1.
- **RES-2** (MiniMax's `scrape_upsert_canonical()` plpgsql sketch) → **SPIKE only**, not directly
  adopted.
- **RES-3** (Hilt provider scaffolding for future map work) → **ADOPT as reference** when map work
  resumes; still out of scope.

No other findings; this review changed no code directly.

### 2026-06-23 — phase1-harsh-review
Adversarial 10-angle + gap-sweep review of the merged scrape-catalog foundation (`090cb47`: #119–#127)
plus three open Phase-1 PRs (#128 review-CAS, #129 ranked-candidate matcher, #130 Bucket4j). Found 6
distinct issues from 55 candidates; two were genuinely new safety-critical gaps the per-PR reviews had
missed. All fixes merged as one follow-up PR (#131) on unified `main` except H3 (landed separately as
#132). All findings **DONE**:

- **H1** (a BLOCKED producing run's facts could still publish via re-proposal) → **DONE** #131.
- **H2** (active-only matcher + status-blind `dedup_key` → create/merge deadlock on a retracted tea's
  identity) → **DONE** #131 (partial-unique `tea_dedup_key WHERE status='active'`).
- **H3** (one alias collision rolled back the WHOLE apply run) → **DONE** #132 (per-decision
  quarantine).
- **H4** (global alias invariant service-layer-only, TOCTOU) → **DONE** #131 (tx-scoped advisory lock);
  DB partial-unique index deferred.
- **H5** (`proposeFor` re-points a decision without a lock, narrow lost-update) → **DONE** #131
  (`findByIdForUpdate`).
- **H6** (completeness-gate vs apply-scope key mismatch) → re-investigated, confirmed benign,
  **WONT-FIX**.
- **cross-site** (decision's `import_run_id` re-pointable with no site check) → **DONE** #131.
- **H7** (merged-`main` regression: `deleteByMatchDecisionId` discarded a re-pointed pending UPDATE) →
  **DONE** #131 (`flushAutomatically=true`).
- **OE** (over-engineering: `match_candidate` table, dup-alias repair report, `EdgeOverloadException`) →
  **RESOLVED**, 2 of 3 were false positives (both live-consumed); dup-alias repair query kept
  deliberately as a security-invariant audit.

### 2026-06-23 — current-architecture-oss-reuse-review
Full current-state + OSS-reuse pass at `971af4d` (after #131/#132 landed). Verdict: architecture is
right; remaining gaps are implement/deploy/verify, not design. The old C4 criticism is "substantially
closed" here (run lifecycle now explicit, row-locked transitions, host/SSRF gates, immutable evidence,
revision-bound review). Confirms one fresh server race (alias advisory lock key normalization mismatch)
and restates the release/production blockers.

- **OPS-P0-1** (production serving old catalog contract) → **RESOLVED** live 2026-06-24 (#143).
- **REL-P0-1** (release key 0644, no drill) → chmod **DONE** (#133); drill still **OWNER**.
- **REL-P0-2** (updater trust) → short path **DONE** (#144); full manifest **OPEN**.
- **AND-P0-1** (Android can't consume UUID/lifecycle) → **PREPPED**, coupled to AND-P0-2.
- **AND-P0-2** (Room v7 split design-only) → **PREPPED, NOT STARTED IN CODE** (still the single biggest
  remaining Android workstream).
- **SRV-P1-1** (alias advisory lock ≠ invariant normal form) → **DONE**, #133 (`lockAuthoritativeAlias`
  on the exact SQL expression).
- **SRV-P1-2** (region free-text; scraper must not auto-canonicalize) → **DEFERRED** until #138.
- **PRIV-P1-1** (miss-log retains popular raw queries indefinitely) → still **CODEABLE/policy**.
- **AND-P1-1/P1-2** (enrichment ownership, durable retry) → **CODEABLE**, still **OPEN**.
- **AND-P1-3** (unbounded file/image I/O) → **CODEABLE**, **OPEN** (partially addressed later by
  #142/#145).
- **AND-P2-1** (offline `LIKE` not escaped) → **DONE** #145.
- **OPS-P1-1..P1-4** (deploy enforcement, backup IAM, single-host gaps, VM sizing) → progressively closed
  #135/#136/#138/#143; OPS-P1-1 (digest-only script) and OPS-P1-4 (VM sizing) remain **OWNER/OPEN**.
- **OCR-P2-1/P2-2** (health lies after rebuild, Python 3.12/3.14 drift) → **DONE** #135.
- **OPS-P2-1** (no OS/container scan) → **DONE** #136 (Trivy).

### 2026-06-23 — code-level-second-pass-review
Fresh code-level pass at `8cda124` after the five hardening commits (#133–#138) landed following the
OSS-reuse review above. Re-baselines what closed, then surfaces new small findings by reading the actual
code: one real server race, one un-pinned trust channel, one quota-burning Android retry, one over-broad
backup credential, plus a long tail of perf/consistency/trim fixes. Reinforces "use what you already
depend on" (HexFormat, `@Validated`, existing `name_norm` column, OkHttp `CertificatePinner`,
`Path.startsWith`) over adding new libraries. All findings **DONE** except as noted:

- **SRV-P1-3** (`applyApprovedNew` check-then-insert race poisons the whole apply tx) → **DONE** #137.
- **SRV-P1-5** (`match_decision.import_run_id` unindexed) → **DONE** #137 (V17 index).
- **SRV-P2-2** (`@Size` caps dead, controller not `@Validated`) → **DONE** #137.
- **SRV-P2-3** (`findIdByNormalizedName` ignores indexed `name_norm`) → **DONE** #137.
- **SRV-P2-4** (`ReviewService.pending()` N+1) → **DONE** (27c080f, batched fetch).
- **SRV-P2-5** (`match_candidate.match_tier` missing CHECK) → **DONE** #137 (V17).
- **SRV-P2-6** (`claimContext` `orElse(null)` degrades provenance) → **DONE** #137 (`orElseThrow`).
- **AND-P1-6** (update manifest fetched over an un-pinned connection) → **RESOLVED** #144 (routed to
  GitHub/Obtainium instead of a TLS pin).
- **AND-P1-7** (`resumePending()` re-fires `/resolve` budget token every board-open) → **DONE** #142.
- **AND-P2-3** (verified APK not deleted on install throw/cancel) → **DONE** #142 (try/finally).
- **AND-P2-4** (backup size constants inconsistent) → **DONE** #142 (documented as intentionally
  independent).
- **OPS-P1-5** (backup SA `storage.admin` at folder scope) → **CODE DONE** #143 (`storage.uploader`
  scoped); `tofu apply` verification still **OWNER**.
- **OPS-P2-2** (Dependabot Docker scope missing `/infra/deploy`) → **DONE** #138.
- **OPS-P2-3** (backup prune has no keep-last-N floor) → **DONE** #138.
- **OCR-P2-3** (`description_correct.py` double-parses candidates) → **DONE** #138.
- Trim list (HexFormat, dup normalizers, dup 404 handlers, dead `CREATED` state, `Sha256`→Okio,
  canonical-path delete) → **MOSTLY DONE** (27c080f/57de04e/14b02eb/8070f83); a few low-value items left
  deliberately.

---

## 2026-07-06 — Usage-quality audit & improvement plan (UX-, round 1)

Full usability/user-friendliness audit of the app as a real user would experience it (`ui/`,
`viewmodel/`, `data/`, plus server surfaces the client feels), at `7c1705f`. Five parallel read passes.
Verdict: app was in good UX shape overall (prior polish passes had already closed the classic gaps) —
what remained was 2 data-integrity P0s, a band of silent-failure P1s, a search gate discarding server
capability for CJK, and a short list of user-expected features. Batches landed in PRs #191–#195.

### P0 — data integrity

| ID | Finding | Status |
|---|---|:--:|
| UX-P0-1 | Save double-tap creates duplicate teas (no in-flight guard; dedup check+insert not one tx) | **DONE** (Batch 1) — `_isSaving` guard + repo `addTeaLock` Mutex |
| UX-P0-2 | One DB read error kills every board screen forever (no `.catch` on app-scope flow) | **DONE** (Batch 1) — `.catch` logs + emits empty |

### P1 — silent failures & major friction

| ID | Finding | Status |
|---|---|:--:|
| UX-P1-1 | Photo add silently no-ops for oversized (>8MB) images / disk-full | **DONE** (Batch 2) — sealed `PhotoCopyResult`/`AddPhotoResult` reasons + preflight space check |
| UX-P1-2 | Restore proceeds and claims success even if the pre-restore safety snapshot failed | **DONE** (Batch 2) — 3-state outcome + `undoUnavailable` flag |
| UX-P1-3 | Process death wipes an in-progress Add form (no `SavedStateHandle`) | **OPEN** |
| UX-P1-4 | Single-CJK-char search never searches (`MIN_CATALOG_QUERY_LEN=2` floor) | **DONE** (Batch 3) — length floor bypassed for single Han-script char |
| UX-P1-5 | Enrichment poll budget too short (~12s) and never retries after a stuck QUEUED | **DONE** (Batch 3) — poll doubles to ~112s budget; `resumePending` gets a 5-min cooldown instead of once-per-process |
| UX-P1-6 | Server RFC-7807 error detail thrown away; 404/429/503 collapse to one generic message | **DONE** (Batch 2), scoped down — added `RateLimited` (429) variant, status-code-driven not raw-passthrough |
| UX-P1-7 | "Check for updates" offline looks identical to "you're up to date" | **DONE** (Batch 2) — new `UpdateAvailability.CheckFailed` distinct from genuine `None` |
| UX-P1-8 | My Teas re-runs a full multi-table join on every unrelated write | **OPEN** |

### Feature gaps

| ID | Feature | Status |
|---|---|:--:|
| UX-F-1 | Wire the `/teas/facets` endpoint (zero call sites) | **DONE** (Batch 5), scoped down — type-filter chips on Browse Catalog; skipped the separate stats-card idea |
| UX-F-2 | My Teas sorting (only alphabetical existed) | **DONE** (Batch 5), scoped down — added NAME/TYPE sort; tier/date/rating not delivered (no schema field) |
| UX-F-3 | Fuzzy search of own teas (plain `.contains()`) | **DONE** (Batch 5) — hand-rolled Levenshtein fallback, 3+ char query |
| UX-F-4 | "Showing top N matches" truncation hint | **DONE** (Batch 3) |
| UX-F-5 | Re-openable "what are tiers?" help | **OPEN** |
| UX-F-6 | Brewing/steep log (no entity exists) | **DECIDE** — needs owner scoping |

### P2 — polish (compact; most DONE in Batch 4, a few reviewed/no-change)

UX-P2-1 a11y contentDescription — **DONE**. UX-P2-2 photo move-left/right buttons — **DONE**. UX-P2-3
tier-editor swatch touch target 48dp — **DONE**. UX-P2-4 text overflow/`maxLines` on purchase/board/tier
labels — **DONE**, one deliberate skip (notes field, natural-flow scroll). UX-P2-5 ru Collator sort for
My Teas — **DONE**. UX-P2-6 differentiated backup failure strings — **DONE**, scoped down (update-failure
string was already correctly dead). UX-P2-7 English-locale enum comment — **REVIEWED, no change** (would
regress `appLanguageOf()`). UX-P2-8 auto-expand-on-data no longer fights a manual collapse — **DONE**.
UX-P2-9 8s "this can take a while" hint on slow archive write — **DONE**. UX-P2-10 tier-color-swatch edit
badge — **partially DONE**, coach-mark skipped as bigger-than-polish. UX-P2-11 image `aspectRatio`
instead of fixed height — **DONE**, scoped down (pinned-CTA restructure skipped as risky). UX-P2-12
`movePlacement` unserialized per-board writes — **OPEN**. UX-P2-13 `submit()` no longer over-catches
post-insert failures as a whole-save failure — **DONE** (Batch 1). UX-P2-14 Undo snapshots load full
tables — **OPEN**. UX-P2-15 diagnostics delivery has zero visibility — **OPEN**. UX-P2-16
`OnboardingState.isSeeded()` side-effect split into a pure read + named consumer — **DONE**.

Known deferred (not re-litigated): geopoints/maps (#20, M6), tier-image sharing (#27), en/zh UI
translations (#48/#94, M5), locale-aware `displayName`, cloud auto-sync, durable WorkManager enrichment
(#70.6/AND-P1-4), v7 `publicId` UUID ref key (AND-P0-2), in-app updater (dormant per #144), staged
rollout, CJK handwriting OCR, enrichment field-ownership (AND-P1-3), bounded image reads (AND-P1-5).

---

## 2026-07-07 — Usage-quality audit round 2 (UX2-)

Round 2, re-reading the entire app plus server surfaces the client feels, specifically hunting for
issues round 1 didn't touch. Six parallel read-only passes, deduped and severity-checked against actual
code (one sub-agent finding — a claimed missing snackbar — was dropped as a false positive after
verification). Verdict: still no architectural problems; round 1's "silent failure" class recurred in two
new **lost-update races on the shared tea row** (P0), plus dead-end UI flows, a second wave of the
error-swallowing pattern, two missing feature entry points (camera capture, multi-select gallery), and a
long P2 tail.

### P0 — data integrity (lost updates on the shared tea row)

| ID | Finding | Status |
|---|---|:--:|
| UX2-P0-1 | Editing a tea unconditionally overwrites every scalar column, racing a concurrent enrichment patch (or vice versa) | **DONE** (Batch 1) — `updateTea` takes an `original` snapshot; the diff-vs-`original` merge covers the six `TeaMergeFields` columns enrichment can touch (nameRu/nameZh/pinyin/nameEn/type/origin), so only a genuinely-changed one wins; notes/vendor/product/harvestYear/batch/grade write through unconditionally (safe — enrichment never writes them). Blank-out of a merged field verified by `TeaDaoUpdateTeaMergeTest` (UX3-P2-12). |
| UX2-P0-2 | "Use reference as my rating" round-trips the *entire* tea through the same unguarded whole-row path from a stale StateFlow snapshot | **DONE** (Batch 1) — new flavor-only `updateFlavor`/`updateFlavors` DAO path |

### P1 — silent failures & major friction

| ID | Finding | Status |
|---|---|:--:|
| UX2-P1-1 | Same-tier reorder is drag-only, zero fallback (the one gap round 1's own fallback pattern missed) | **DONE** (Batch 3) — move-left/right a11y action + menu item |
| UX2-P1-2 | "Add to board" picker has no "create new board" option once any board exists | **DONE** (Batch 3) — confirm button always offers "create a board" |
| UX2-P1-3 | Backup import/restore has no VM-level re-entrancy guard on a destructive full-table replace | **DONE** (Batch 4) — shared `guardedBusy` across all four ops |
| UX2-P1-4 | ~~Deleting a tier with ranked teas has no Undo~~ | **DONE (Batch 3) — no fix needed**, false positive (Undo already exists) |
| UX2-P1-5 | `/search`, `/browse`, `/resolve` still collapse 429/503 into a generic error (round 1 fixed only `/teas/{id}`) | **DONE** (Batch 2) — `RateLimited` case added to all three result types |
| UX2-P1-6 | OCR client timeout (15s) shorter than server's realistic worst case (~40s) → false "no network" message | **DONE** (Batch 2) — per-call 60s OkHttp interceptor for `/teas/ocr` |
| UX2-P1-7 | Malformed/no-text OCR response collapses into the same "try later" as a hard outage | **DONE** (Batch 2), scoped to actual root cause — sidecar 422s on undecodable image; new `OcrUnreadableImageException`, distinct client message |
| UX2-P1-8 | A pending catalog-pick Undo snackbar can restore a stale form onto a *different* reused Add-Tea session | **DONE** (Batch 4) — captured `lastEntryToken`, only restores if still bound |
| UX2-P1-9 | Update-failure dialog collapses download/verify/install failures into one generic message | **DONE** (Batch 2), re-scoped — the one live `Failed` state (`reason=="check"`) no longer double-fires the generic modal on top of its own correct inline row |
| UX2-P1-10 | Seeded sample data has no marker or one-tap clear; indistinguishable from real data forever | **DONE** (Batch 4), scoped to clear action — bulk "clear sample data" row added; per-item badge skipped |
| UX2-P1-11 | OCR source-chooser dialog overloads confirm/dismiss for two equal choices, breaking the file's own convention | **DONE** (Batch 4) — camera/gallery as two equal rows; dismiss is now a real "Cancel" |
| UX2-P1-12 | Deleting the last remaining tier was unguarded (dead-end, not data loss) | **DONE** (Batch 3) — delete button disabled when it's the only tier |

### Feature gaps

| ID | Feature | Status |
|---|---|:--:|
| UX2-F-1 | No camera-capture entry point for tea photos (only gallery pick) | **DONE** (Batch 5) — "+" tile opens camera-or-gallery chooser; captured file now kept |
| UX2-F-2 | Gallery photo picking is single-select only | **DONE** (Batch 5) — switched to `PickMultipleVisualMedia` |
| UX2-F-3 | Catalog search rows never show a distinct `nameEn` when it differs | **OPEN** |
| UX2-F-4 | Attributions screen covers only the 4 data sources, not OSS license disclosure | **DECIDE** — needs owner call |

Verified absent, no action needed: `task.md`'s "integrate all available tea databases" and "own Python
flavor-backfill model" — both literal spec lines with zero code trace, neither felt as a real gap today.

### P2 — polish (compact)

Batch 6 triaged all 27 into mechanical fixes (done) vs. items needing a bigger design call (left
OPEN/REVIEWED with reason). **DONE**: UX2-P2-1 (result-list height cap), UX2-P2-2 (detail-sheet error
scroll), UX2-P2-4 (notes field `heightIn` instead of fixed), UX2-P2-6 (marketplace-URL placeholder),
UX2-P2-7 (IME-focus-clear + `imePadding` before opening detail sheet), UX2-P2-12 (dead `cd_liquor` string
removed), UX2-P2-14 (source-text counter turns error-colored at cap + overflow snackbar instead of silent
truncation), UX2-P2-15 (redundant-retry feedback), UX2-P2-16 (My Teas search debounced), UX2-P2-17
(facets retry after reconnect), UX2-P2-18 (per-VM resume gate removed, manager cooldown is now the single
throttle), UX2-P2-19 (harvestYear plausible-range + marketplace-URL shape check), UX2-P2-20 (`check()`
re-entrancy guard; Diagnostics half was a false premise), UX2-P2-22 (Coil-fallback half: `PhotoBadge`
broken-image glyph), UX2-P2-23 (Ackpine failure reason now logged). **REVIEWED, no change**: UX2-P2-5
(40dp photo touch target — documented deliberate trade-off), UX2-P2-13 (Browse Catalog sort / rename
asymmetry — inherent trade-offs, not oversights). **OPEN** (needs bigger scope than mechanical polish):
UX2-P2-3 (FlavorRadar label overlap, needs Canvas + device verification), UX2-P2-8 (`error_generic` reused
across ~16 sites), UX2-P2-9/UX2-P2-11 (`/resolve` `UNRESOLVED` conflates 3 causes; LLM daily budget has no
client-visible signal — both need a new wire-contract status value), UX2-P2-10 (shared-NAT rate-limit
collisions — no action intended per the finding's own text), UX2-P2-21 (reference-detail fetch has no
retry affordance), UX2-P2-22 (`reconcile()` DB-heal half, needs a DAO query + cleanup UI), UX2-P2-24
(narrow edge-case error differentiation — no action intended), UX2-P2-25/UX2-P2-26 (**VERIFY**-only:
pre-v7 backup format acceptance; language-switch Activity-recreate warning — both need an owner call
before scheduling), UX2-P2-27 (scroll-position on description collapse — deferred, low value).

Doc hygiene: `context/plan.md` §11 still frames backup merge-vs-replace as "open" though decision #49
resolved it — stale-doc trap, zero user impact.

Carried over from round 1, still OPEN, not re-litigated: `UX-P1-3` (process-death draft loss),
`UX-P1-8` (My Teas join perf), `UX-P2-12` (unserialized drag writes), `UX-P2-14` (Undo loads full
tables), `UX-P2-15` (diagnostics delivery visibility — independently re-confirmed open), `UX-F-5`
(re-openable tier help), `UX-F-6` (brewing log, owner-scoping needed).

---

## 2026-07-10 — Usage-quality audit round 3 (UX3-)

Round 3 at `fac7a30`, after rounds 1–2 landed as PRs #191–#206. Six parallel read-only passes seeded
with the known-open list (journeys, per-screen states, regression review of #191–#206, feature gaps,
server-felt behavior, a11y/copy); every P0/P1 candidate adversarially verified by 10 independent
skeptics (8 confirmed, 2 downgraded to P2, 1 refuted — the journey pass's "drafts survive process
death" claim; UX-P1-3 stays OPEN). Verdict: rounds 1–2 genuinely cleaned house; round 3 found one old
product-decision violation still live (silent name-match merge, decision #132), a P1 band around
enrichment/scan session state, one complete assistive-tech block (tier color picker), and — the
interaction-bug backlog being nearly dry — the first set of product-level feature gaps as the biggest
remaining lever.

**Implementation complete same day** (PRs #207–#216, per-batch plan → implement → adversarial review).
A cross-batch final review (5 reviewers over the whole round-3 diff) then caught a P0 no per-batch
review could see — Save during the new edit-form loading spinner called `requestFocus()` on a
spinner-hidden field → crash; fixed with four cross-batch P2s in #216. Lesson recorded: always run one
final review over the combined diff of a multi-PR effort.

### P0 — data integrity

| ID | Finding | Status |
|---|---|:--:|
| UX3-P0-1 | Adding a tea whose typed name matches an existing tea silently discarded everything typed (name-match resolve set `teaToInsert = null`; only placement+photos persisted, no message) — violated locked decision #132 / v7 finding #17 | **DONE** (#207) — manual add always creates a new sample; name match is a non-blocking reuse-or-new prompt; `forceNew` extended to non-catalog teas |

### P1 — dead ends, silent failures, major friction (all skeptic-confirmed)

| ID | Finding | Status |
|---|---|:--:|
| UX3-P1-1 | Orphaned tea ("Не в подборках") had no deliberate path back onto any board | **DONE** (#210) — explicit add-to-board on Detail/My Teas |
| UX3-P1-2 | QUEUED/RATE_LIMITED enrichment had no user-drivable retry (menu gated to FAILED only; 5-min cooldown dead zone) | **DONE** (#208) — retry menu covers QUEUED/RATE_LIMITED |
| UX3-P1-3 | `/teas/resolve` client timeout (15s) shorter than server's Wikidata worst case (~22s) → false "Нет сети" label | **DONE** (#208) — interceptor exemption extended |
| UX3-P1-4 | Tea detail + My Teas rendered zero enrichment state for any state (status/retry existed only on board cards) | **DONE** (#208) |
| UX3-P1-5 | Stale OCR scan state leaked across Add/Edit sessions (`bind()` never reset `_scan`; no cancellable Job) — tea A's review dialog could merge into tea B's form | **DONE** (#209) — tracked Job, reset on bind |
| UX3-P1-6 | No cancel during an in-flight scan (up to ~60s; only exit was system back, which didn't cancel) | **DONE** (#209) — Recognizing state is an active Cancel |
| UX3-P1-7 | Tier color picker a complete TalkBack block (bare `Box.clickable` swatches, no label/role/state) | **DONE** (#211) |

### Feature gaps (verified absent; all owner DECIDE, none built)

UX3-F-1 collection stats/overview (high demand) · UX3-F-2 price/spend tracking — structurally
impossible, no price field anywhere (high) · UX3-F-3 consumption status wishlist/drinking/finished
(high) · UX3-F-4 dated journal notes instead of one overwritable string (med-high) · UX3-F-5 board
duplicate/archive/seed-from-collection (med) · UX3-F-6 text-only board share — decision #27's deferral
covers only the image form (med) · UX3-F-7 bulk operations on My Teas (low-med) · UX3-F-8 board
comparison / auto "all teas" board (low).

### P2 — polish (compact; final statuses)

**DONE**: UX3-P2-1 BoardScreen blank-scaffold spinner (#212) · P2-3 My Teas premature empty state
(#212) · P2-4 edit-form loading gate (#212) · P2-5 `flavorsExpanded` rememberSaveable (#212) · P2-6
boards-flow one-shot `.catch` → consecutive-failure retry (#212; distinct error-vs-empty UI remains a
deliberate deferral) · P2-8 board-picker create-board hint always shown (#210) · P2-9 "clear sample
data" copy names ranking loss (#210) · P2-11 multi-photo pick capped at 8 (#213) · P2-12 merge-scope
doc fix + blank-out test (#215) · P2-13 `StandardTestDispatcher` re-entrancy test (#215) · P2-14
facets self-heal from `applyMore` (#213) · P2-16 `resumePending` once per launch in
`MainActivity.onCreate` (#208) · P2-17 post-import `resumePending(force=true)` (#208) · P2-18
non-zip import → `backup_invalid_file` copy (#213) · P2-19 unique per-share backup filenames (#213) ·
P2-21 OCR slow hint after 8s (#209) · P2-23 IME `ImeAction.Next` chaining on the add form (#214) ·
P2-24 live region on enrichment status, detail screen (#211) · P2-25 settings switches `toggleable`
(#211) · P2-26 `Role.Button` on 5 list-item cards (#211) · P2-28 «Доски»→«Подборки» in privacy
copy (#213).

**Still open / deferred**: UX3-P2-2 tier rows compose placements eagerly (`Row`+`horizontalScroll`,
not `LazyRow`) — OPEN, drag-layout dependency · UX3-P2-15 retried/resumed enrichment drops the user's
`sourceText` grounding (`retry()`/`resumePending()` hard-code null) — DEFERRED, own follow-up ·
UX3-P2-20 diagnostics "никогда не включает пути" not enforced on ACRA traces — DECIDE (owner; no
actual user-data leak found) · UX3-P2-22 update check never fires automatically — deliberately
TRACKED into REL-P0-2 (wire WITH the Ed25519 manifest, not before) · UX3-P2-27 FlavorRadar fixed
`.height(240-260.dp)` vs font-scaled Canvas labels — DEFERRED, needs device-visual verification ·
UX3-P2-10 camera temp-file sweep — SKIPPED (already bounded to one file per dir) · UX3-P2-29 delete
from Detail/Edit has no Undo — REVIEWED, confirm dialog is the deliberate safety net (#210) ·
UX3-P2-7 enrichment copy overpromise — folded into P1-2's fix.

Doc hygiene: `AddTeaScreen.kt:109-110` comment falsely claims process-death survival (caused a
refuted round-3 finding) — fix when touching the file; UX-P1-3 re-confirmed OPEN (no
`SavedStateHandle` anywhere).

### Verified clean in round 3 (don't re-audit soon)

Fix batches #191–#206 (per-batch regression verdicts); search/browse paging + stale-response
cancellation; rate-limit budgets vs client call patterns; backup export/import validation +
safety-snapshot + FileProvider hygiene; privacy disclosures vs actual egress (OCR re-encode strips
EXIF/GPS beyond the stated promise); onboarding/sample-clear; drag fallbacks + tier-editor guards;
OCR failure-state taxonomy; dark theme; ru copy register.

Carried over from rounds 1–2, still OPEN after round 3: UX-P1-3, UX-P1-8, UX-P2-12, UX-P2-14,
UX-P2-15, UX-F-5, UX-F-6, UX2-F-3, UX2-F-4, UX2-P2-3, UX2-P2-8, UX2-P2-9/11, UX2-P2-21, UX2-P2-22,
UX2-P2-25/26 (VERIFY items).
