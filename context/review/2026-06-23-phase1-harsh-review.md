# Phase-1 harsh whole-project review — 2026-06-23

Status: adversarial review of the merged scrape→catalog foundation on `main` (`090cb47`: PRs #119–#127, i.e.
decision #141 PR-1/2/3) **plus** the three open Phase-1 PRs — **#128** (review-decision CAS + apply-run-id),
**#129** (ranked-candidate matcher + `match_candidate` + global authoritative-alias invariant), **#130**
(Bucket4j rate limiting). Method: the `code-review` skill run as a 10-angle finder → per-candidate verify →
gap-sweep workflow (55 candidates → verified), plus a `ponytail-review` over-engineering pass. Grounded in
`context/decisions.md` #136–#141, the review history, and the sibling `../researches/projects/TeaTiers`.

The 15 surviving rows **cluster into 6 distinct issues**: two are NEW (the per-PR adversarial reviews missed
them); four re-surface known/documented residuals at higher severity. All findings are against the OPEN PRs
(nothing here is on merged `main` beyond what those PRs change).

## Findings

### H1 (MAJOR, new) — a BLOCKED producing run's facts can be published via re-proposal (#128)
`CanonicalUpsertService.requireApplyAllowed`. The apply-run-id change gates the **applying** run on full
lifecycle state but the **producing** run on `dryRun` only. A run aborted by `blockRun` (the robots/ToS/host/
SSRF *compliance* abort) leaves its staged revision + evidence + decision intact; an operator starts a clean
run, `proposeFor` re-points the decision, approves, `applyRun` → the revision publishes. Pre-PR-4,
`requireApplyAllowed(revision)` resolved the producing run and rejected any non-`reviewed/applying` state
(including `blocked`/`failed`). PR-4 moved the state check to the applying run and the dry-run-laundering
hatch was tested+closed — the **blocked** hatch was not. This defeats the most safety-critical gate
(compliance abort) and lets prohibited-source content reach the public catalog.
**Fix:** in `requireApplyAllowed`, also reject when the producing run's status is `BLOCKED` (the compliance
abort). Keep `FAILED` allowed so the legitimate deferred-review-from-a-failed-run case (PR-4's test) still
works — `blockRun` = "a robots/ToS/host gate tripped" (taint), `failRun` = "runner/operator error"
(operational). Add a negative IT: producing run `blockRun`’d → re-propose into a clean run → `applyRun`
throws `CanonicalApplyForbiddenException`.

### H2 (MAJOR, new) — active-only matcher + status-blind `dedup_key` → operator deadlock (#129)
`IdentityMatchService.bestProposal` (active-only) + `CanonicalUpsertService.applyApprovedNew`
(`findByDedupKey`, status-blind, backed by the status-blind `tea_dedup_key` unique). PR-5 made the matcher
skip retracted teas, so it proposes `create_new` for a name whose only match is a **retracted** tea. At apply,
the status-blind dedup check finds that retracted tea → `CanonicalUpsertConflictException("merge instead")` —
but merging is impossible (`InactiveMergeTargetException`, retracted target). The identity can be **neither
created nor merged**: a permanent deadlock for re-creating any identity whose `dedup_key` matches a retracted
tea (the DB unique is global, so even bypassing the app check, the INSERT would violate it).
**Fix:** make `tea_dedup_key` a partial unique over active teas (`WHERE status = 'active'`) and make
`findByDedupKey` active-scoped, so a retracted tea no longer blocks re-creating its identity. Mirrors the
alias active-scoping problem (H4) — the same retracted-owner-permanently-blocks pattern. (Tombstone handling:
a retracted tea keeping its `dedup_key` is fine once the unique is active-scoped.)

### H3 (MAJOR→operability, known) — one alias collision rolls back the WHOLE run (#129)
`ReviewService.applyRun` loops over all approved decisions in one `@Transactional` with **no per-decision
try/catch**; `CanonicalUpsertService.writeNamesAndAliases` calls `addAuthoritative` per name, which throws
`DuplicateAuthoritativeAliasException` on a cross-tea collision. So a single poison decision (e.g. a 2nd
same-locale name colliding with another active tea's authoritative alias — invisible to the matcher, which
checks only the first name per locale) aborts the **entire** run's apply; the operator is stuck with no
per-decision attribution. PR-5's review noted this as MINOR/fail-closed-correct; the whole-run-rollback impact
escalates it.
**Fix:** per-decision try/catch in `applyRun` — quarantine the colliding decision (leave it for operator
resolution), apply the rest, and report the collisions in the result. (Optionally also pre-flight the full
`facts.names` set at decide time so the conflict surfaces before sealing.)

### H4 (MAJOR→concurrency, known/deferred) — global alias invariant is service-layer-only (TOCTOU) (#129)
`IdentityAliasService.addAuthoritative` enforces "one active owner per (locale, alias_norm)" with a
read-then-write (`findOtherActiveAuthoritativeOwners(...).firstOrNull()?.let { throw }`) and **no backing DB
unique/partial index** (V9's `tea_identity_alias_uk` is per-tea). Two concurrent cross-run applies can both
pass the check and both insert — the exact duplicate the invariant forbids. PR-5 deferred the DB index
because a naive global unique over-restricts a retracted owner (can't reference `tea.status` cross-table); the
gap is real under concurrent operators (the sibling CAS PR exists to enable concurrency).
**Fix:** add a partial unique index on `tea_identity_alias (locale, alias_norm) WHERE verified AND origin IN
('curated','human_confirmed')` over ALL rows, **plus** demote a tea's authoritative aliases to
`verified=false` when it transitions to `retracted`/`merged` (so the index doesn't permanently block
re-creating a withdrawn identity). Keep the service guard as the friendly pre-check.

### H5 (MINOR→concurrency, narrow) — `proposeFor` re-points a decision without the lock (#128/#129)
`IdentityMatchService.proposeFor` reuses+rewrites an existing pending decision via an unlocked
`findBySourceRecordId` + `save` (no `findByIdForUpdate`, no `@Version`). The PR-4 CAS guards decide-vs-decide,
but a concurrent re-propose can clobber a just-committed approval (lost update: approve sets `approved_new`,
a racing `proposeFor` read it as `pending` and re-points it back to `pending`). Narrow (matcher vs operator on
the same decision concurrently), but the CAS only closed one side.
**Fix:** take `findByIdForUpdate` on the reused decision in `proposeFor` (or `@Version` on `MatchDecision`),
matching the approve path; assert the row is still re-pointable.

### H6 (MINOR, smell — judged NOT a publish bug) — completeness vs apply scope key mismatch (#128)
`ReviewService.markReviewed` keys completeness on `source_record.import_run_id`
(`countUnreviewedRecords(runId)`) while `applyRun` scopes on `match_decision.import_run_id`; the cross-run
re-propose re-points only the decision. So a later run applies a decision whose record was never
completeness-checked under that run. Judged a consistency smell rather than a correctness bug: the applied
decisions are individually `approved_*` (reviewed), and `markReviewed(runB)` still blocks runB's OWN unreviewed
records. **Not scheduled for fix**; noted for awareness (revisit if the two scopes ever need to agree).

## Over-engineering (ponytail pass)
- **`#129 match_candidate` table + `MatchCandidate` entity + repo + `persistCandidates` + DTO wiring (~110 lines):**
  `yagni` — a persisted ranked-candidate set with one reader (the review queue) and **no live consumer**
  (no scraper runs, no UI renders it). The FND-P1-1 *safety* fix is the active-only filter + the `conflict`
  kind + `candidateTeaId`; surfacing runners-up is all the table buys. *Caveat:* FND-P1-1 explicitly asks to
  "return ranked candidates with per-hit evidence", so this is "built ahead of the consumer", not dead code —
  owner’s call whether to defer until a reviewer surface reads it.
- **`#129 findDuplicateActiveAuthoritativeAliases` repair report + projection (~20 lines):** `yagni` — a
  report for duplicates that cannot exist yet (fresh catalog, the `addAuthoritative` guard prevents new ones).
  Add it the day a real DB has dups. *(But it pairs with H4’s DB index as the migration-time pre-check, so
  keep it if H4 lands.)*
- **`#130 EdgeOverloadException` + handler (~12 lines):** `yagni`-borderline — a 2nd exception for the edge
  ceiling; could reuse `RateLimitException` (429) unless the 503-vs-429 split is consumed by a client/alert.
  Keep if the distinct "service shedding load" 503 is wanted in telemetry.
- **`#128`:** lean.

net: ~-140 lines possible (dominated by `match_candidate`), if the owner chooses to defer the surfacing
infrastructure until a consumer exists.

## Fix sequencing (the entanglement)
The five fixes (H1–H5) touch files all three open PRs modify — `CanonicalUpsertService`
(`requireApplyAllowed` for H1 + your uncommitted cross-site WIP; `applyApprovedNew` for H2),
`ReviewService.applyRun` (#128 + #129; H3), `IdentityMatchService` + `MatchDecisionRepository.findByIdForUpdate`
(#128's lock + #129's matcher; H5), plus two new migrations (H2 dedup partial-unique, H4 alias partial-unique).
**H1 collides directly with the uncommitted #128 WIP on `requireApplyAllowed`.** They cannot be applied cleanly
to the separate open branches. Plan: **merge #128/#129/#130** (CI-green, reviewed) → apply H1–H5 as one
coherent follow-up PR ("phase-1 harsh-review fixes") on the unified `main`, with the negative tests named
above. The ponytail trims are a separate, optional cleanup PR.
