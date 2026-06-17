# TeaTiers architecture review — second pass after PR merges

Date: 2026-06-17

Refresh status:
- Waited one hour, then ran `git pull --ff-only`.
- Local `main` was already up to date.
- New merged commits reviewed: #36 through #39.
- This pass builds on `context/review/2026-06-17-architecture-review.md` and
  `context/review/2026-06-17-disposition.md`.

## What changed since the first review

The merged PRs addressed several first-pass findings:

- Release gate/disposition: `context/review/2026-06-17-disposition.md` and plan §7.1
  now track the review items explicitly.
- Backup drift: `BackupTea` now carries `catalogTeaId` and `enrichmentState`, so v5
  enrichment state survives export/import.
- `/resolve` async contract: server now returns `ENRICHING`, persists
  `tea.enrichment_state`, and uses `sourceText` in the LLM path.
- Cost protection: `LlmDailyBudget` adds a global daily cap in addition to per-IP rate
  limiting.
- Local dedup: add-tea now matches existing user teas by `catalogTeaId` before falling
  back to name matching.
- Resume hardening: `resumePending()` now runs from the home screen and has an in-flight
  de-dup set.

## Updated disposition

| First-pass item | Second-pass status |
|---|---|
| Typo-tolerant catalog search | Still open. Research 09 is still the right gate before code. |
| Backup v5 fields | Resolved. |
| Destructive Room migrations | Still open; release blocker until public-schema cutover is chosen. |
| Durable queued enrichment | Improved for app-open; true process/reboot durability via WorkManager remains open. |
| `/resolve` contract drift | Mostly resolved on server; one bug-level edge case remains below. |
| Catalog image list | Still open product decision. |
| `catalogTeaId`-first local dedup | Implemented in repository flow; DB-level uniqueness is still worth adding before public schema. |
| Release gate | Resolved in docs. |
| Global daily LLM cap | Resolved. |
| GHCR migration | Still planned, not implemented. |
| Off-box DB backup | Still open ops decision. |
| Curated seed expansion | Still open content decision. |
| en/zh UI timing | Still open release decision. |

## New finding: FAILED resolve can be hidden as DONE locally

Severity: P0 before M4 release.

Path reviewed:
- `server/src/main/kotlin/com/macsia/teatiers/service/ResolveService.kt`
- `app/src/main/kotlin/com/macsia/teatiers/data/repository/CatalogRepository.kt`
- `app/src/main/kotlin/com/macsia/teatiers/data/repository/TeaEnrichmentManager.kt`

Scenario:

1. Catalog has an LLM stub with `enrichmentState = FAILED`.
2. Client retries enrichment for the local tea.
3. Server finds the existing row by normalized name.
4. If the LLM tier is disabled, or if `LlmDailyBudget.tryAcquire()` returns false, the
   server does not re-arm the row.
5. `ResolveService.cacheHit()` then returns `ResolveStatus.MATCHED` with a detail DTO whose
   `enrichmentState` is still `FAILED`.
6. The app maps `MATCHED` to `ResolveResult.Matched`.
7. `TeaEnrichmentManager.runEnrichment()` calls `applyPatch()`, and `applyPatch()` always
   writes local `EnrichmentState.DONE`.

Impact: a failed enrichment can become locally hidden as "done" without any successful
enrichment. The user loses the retry affordance even though the catalog row is still failed.

Recommended fix:

- Either add an explicit resolve status for failed stubs, or handle this on the client:
  when `ResolveResult.Matched.detail.enrichmentState == FAILED`, set the local row to
  `FAILED` and do not call `applyPatch()`.
- Also consider `PENDING` in a `MATCHED` response: keep it polling or mark local `PENDING`,
  not `DONE`.
- Add tests for:
  - failed server stub + daily budget exhausted keeps local `FAILED`;
  - failed server stub + LLM disabled keeps local `FAILED`;
  - done/null server state still patches and sets local `DONE`.

This is a small contract edge, not a reason to revert the async design.

## New finding: catalogTeaId dedup is not DB-enforced

Severity: P1 before public release.

The repository now checks `catalogTeaId` first via `TeaDao.findTeaIdByCatalogId()`, which is
the right normal-flow behavior. The Room schema still has no unique index on non-null
`teas.catalogTeaId`.

Impact: duplicate catalog-linked user teas remain possible through concurrent add paths,
backup import, or future code that bypasses the repository lookup. Low probability in the
current UI, but this is an identity invariant and should be represented in the schema once
the public schema line is drawn.

Recommendation:

- Add an index on `catalogTeaId`.
- Prefer a unique index for non-null values if Room/SQLite setup supports it cleanly.
- If partial unique index support is awkward in Room annotations, add it in a real migration
  when public-schema cutover happens.

## Remaining architecture choices

These remain the highest-leverage decisions after the merged PRs:

1. Public Room schema cutover: choose the current app DB version as the first public schema
   and stop destructive migrations afterward.
2. Search: run/judge `research/09-typo-search`, then implement Postgres `pg_trgm` first
   unless the gold set fails.
3. WorkManager: decide whether resume-on-app-open is honest enough for MVP or whether true
   durable queued enrichment is required.
4. Off-box DB backups: now more important because `/resolve` produces non-seed catalog rows.
5. Catalog photos: decide single catalog image vs `tea_image` list.
6. GHCR: verify VM pull reachability and move image publishing if still desired.

## Current recommendation

Do not add another search engine yet. Finish the small correctness fixes first:

1. Fix the `FAILED` resolve response/client handling edge.
2. Decide public Room schema cutover.
3. Run research 09 and implement in-Postgres typo search.
4. Decide WorkManager vs explicit "retry on next app open" MVP copy.

That keeps the project aligned with the current architecture: local-first app, small
catalog backend, one VM, minimal external dependencies, and explicit legal/data boundaries.
