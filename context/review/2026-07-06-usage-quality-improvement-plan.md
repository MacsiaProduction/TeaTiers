# 2026-07-06 — Usage-quality audit & improvement plan (UX-)

Full usability/user-friendliness audit of the app as a real user would experience it: every
`ui/`, `viewmodel/`, and `data/` file in `app/`, plus the server surfaces the client feels
(search, errors, update manifest). Five parallel read passes: UI screens, ViewModel/data
behavior, feature completeness vs specs, localization/a11y/copy, server-felt behavior.

**Verdict:** the app is in good UX shape — prior polish passes already closed the classic gaps
(empty states, loading, delete-confirm + Undo, discard guards, TalkBack alternatives for drag,
plurals, WCAG tier colors). What remains: **2 data-integrity P0s**, a band of **silent-failure
P1s** (things fail and the user sees nothing or a lie), a **search gate that discards server
capability for CJK**, and a short list of user-expected features. No architectural changes needed.

Status legend: `OPEN` · `DONE` · `TRACKED` (already in INDEX.md under another ID — don't duplicate).

## P0 — data integrity

| ID | Finding | Where | Status |
|---|---|---|:--:|
| UX-P0-1 | **Save double-tap creates duplicate teas.** `submit()` has no in-flight guard, and `addTea` does check-then-insert dedup as separate suspend calls, not one transaction — two rapid taps race past the dedup. | `viewmodel/AddTeaViewModel.kt:431`, `data/repository/TeaBoardRepository.kt:262` | **DONE (Batch 1)** — `_isSaving` re-entrancy guard + Save disabled while in flight; repo `addTeaLock` Mutex serializes the resolve→check→insert critical section; guard unit-tested. |
| UX-P0-2 | **One DB read error kills every board screen forever.** `repository.boards` is a Room flow collected on the app scope with no `.catch` and no scope exception handler — a query failure either crashes the app or silently freezes all board-dependent screens with no error or retry. | `data/repository/TeaBoardRepository.kt:125` | **DONE (Batch 1)** — `.catch` logs + emits empty so a read error can't crash the app-scope collector or freeze the flow. |

Fixes: `isSaving` guard + disable Save while in flight; wrap dedup-check+insert in one `@Transaction`
DAO method; `.catch` on the boards pipeline exposing an error/retry state.

## P1 — silent failures & major friction

| ID | Finding | Where | Status |
|---|---|---|:--:|
| UX-P1-1 | **Photo add silently does nothing** for oversized (>8MB) images (`copyIn` returns null) and for disk-full (generic IOException swallowed). No "too large" / "out of storage" message. | `data/photos/AndroidPhotoStore.kt:53,74` | **DONE (Batch 2)** — `copyIn`/`addPhoto` return a sealed reason (`PhotoCopyResult`/`AddPhotoResult`: TooLarge/OutOfSpace/Failed); preflight `usableSpace` check added; edit-mode add + submit-time draft materialization both surface the specific message. |
| UX-P1-2 | **Restore lies about its safety net.** If the pre-restore safety snapshot fails to write (disk full/IO), the destructive restore proceeds anyway and shows the same "Imported" success — user thinks Undo exists when it doesn't. | `data/backup/BackupManager.kt:238` | **DONE (Batch 2)** — `stageSafetyBackup()` returns a 3-state outcome (Skipped/Staged/Failed); `BackupResult.Imported.undoUnavailable` flag surfaces a distinct "restored, but no undo" message. |
| UX-P1-3 | **Process death wipes an in-progress Add form.** After process death the restored `entryToken` no longer matches the fresh VM's null, so typed input is silently reset. Persist a minimal draft via `SavedStateHandle`. | `viewmodel/AddTeaViewModel.kt:195` | OPEN |
| UX-P1-4 | **Single-CJK-char search never searches.** `MIN_CATALOG_QUERY_LEN = 2` gates both search VMs, so `茶` falls back to unfiltered browse — while the server's `strpos` path was *specifically built* for short/CJK queries. Special-case CJK codepoints (len ≥ 1). | `viewmodel/CatalogSearchModels.kt:52`, cf. `server/.../TeaSearchRepositoryImpl.kt:87-93` | OPEN |
| UX-P1-5 | **Enrichment gives up too early and never retries.** Poll budget ≈12s (8×1.5s) marks slower jobs FAILED permanently; `resumePending` runs once per process, so a tea stuck QUEUED from an offline add is never retried until full app restart. Longer/backoff poll ceiling + re-arm on reconnect. (Durable WorkManager version stays TRACKED as AND-P1-4/#70.6.) | `data/repository/TeaEnrichmentManager.kt:75-82,119-152` | OPEN |
| UX-P1-6 | **Server error detail thrown away.** The server sends RFC-7807 `problem+json` with human-readable `detail` on every error path, but the app parses the body only for 410 — 404/429/503 all collapse to one generic error string. Parse `ProblemDetail` generally and surface it. | `data/repository/CatalogRepository.kt:205` (cf. `:96-107` OCR 429/503) | **DONE (Batch 2), scoped down** — raw server `detail` text is English-only and would violate the ru-first localization rule if echoed verbatim, so the fix is status-code-driven, not raw-passthrough: `CatalogDetailResult` gained a `RateLimited` (429) variant distinct from generic `Error`, mirroring the OCR path's existing granularity; wired through the detail sheet + enrichment poller (treated as "retry next tick", not a failure). Search/browse/OCR were already adequately granular per the audit — left as-is. |
| UX-P1-7 | **"Check for updates" offline looks like "you're up to date."** `check()` catches all exceptions as `UpdateAvailability.None` — indistinguishable from current. `UpdateUiState.Failed` exists in the UI but may be unreachable via this path. | `data/update/AppUpdateChecker.kt:46-49` | **DONE (Batch 2)** — confirmed `Failed` was unreachable via `check()`; added `UpdateAvailability.CheckFailed` (network/parse/non-2xx) distinct from the genuine `None` (204/up-to-date/ineligible-OS); the existing but previously-dead inline "check failed / retry" row in Settings now actually fires. |
| UX-P1-8 | **My Teas re-runs a full multi-table join on every unrelated write** (any flavor/photo/purchase edit anywhere re-aggregates all teas) — visible jank as the collection grows. Narrow invalidation or paginate. | `data/db/TeaDao.kt:66` | OPEN |

## Feature gaps worth building (user-expected, verified absent)

| ID | Feature | Impact | Notes | Status |
|---|---|:--:|---|:--:|
| UX-F-1 | **Wire the facets endpoint.** `GET /api/v1/teas/facets` exists on server and in `CatalogApi.kt:45` but has zero call sites — free win for type-filter counts in Browse and/or a small "your collection" stats card (counts per type/tier). | med | server done, client 0% | OPEN |
| UX-F-2 | **My Teas sorting** (tier / date added / rating) — only a type filter + hardcoded alphabetical exists. Collection becomes hard to scan as it grows. | med | `viewmodel/MyTeasModels.kt:50` | OPEN |
| UX-F-3 | **Fuzzy search of own teas.** Catalog search is trigram-fuzzy but personal-collection search is plain `.contains()` — a typo silently returns nothing. Client-side normalized/fuzzy match is enough at personal scale. | med | `viewmodel/MyTeasViewModel.kt:41-55` | OPEN |
| UX-F-4 | **"Showing top N matches" hint** for typed search — fuzzy results are capped at one page by design (no cursor), but the UI gives no signal the list was truncated. One string + one condition. | low | `viewmodel/BrowseCatalogViewModel.kt:139-146` | OPEN |
| UX-F-5 | **Re-openable help.** The one-time S–F tier intro card is gone forever once dismissed — add a "What are tiers?" entry in Settings. | low | `ui/board/BoardsScreen.kt:88` | OPEN |
| UX-F-6 | **Brewing/steep log** (per-tea infusion counter / session notes) — core hobbyist ritual, no entity exists. Biggest genuinely-new feature candidate; needs owner scoping decision before any design. | med | no code | DECIDE |

## P2 — polish

Accessibility / layout:
- UX-P2-1 — Interactive expand/collapse chevron has `contentDescription = null`; TalkBack announces nothing for the only control opening the Sample section. `ui/board/AddTeaScreen.kt:338-341`.
- UX-P2-2 — Photo reorder has TalkBack custom actions but **no visible non-drag alternative** (move-left/right callbacks only exist inside semantics); dead string `a11y_photo_drag_handle` (strings.xml:200) never referenced. Add visible move buttons (mirror BoardScreen's dropdown pattern) or wire the handle. `ui/board/PhotoStrip.kt:189-252`.
- UX-P2-3 — Color-preset dots are 44dp, under the 48dp minimum touch target. `ui/board/TierEditorScreen.kt:295-309`.
- UX-P2-4 — 5 user-generated-text `Text()` calls missing `maxLines`/overflow: notes + purchase row (`TeaDetailScreen.kt:310,391`), board name (`BoardsScreen.kt:227`), blurb (`BoardScreen.kt:356`), tier label in fixed 46dp box (`BoardScreen.kt:427`; also `.take(2)` in `TierEditorScreen.kt:236` truncates by code unit — CJK risk).
- UX-P2-5 — No `Collator` anywhere: mixed Cyrillic/Latin/CJK lists sort by raw codepoint. `viewmodel/MyTeasModels.kt:50`.

Copy / errors:
- UX-P2-6 — **DONE (Batch 2), scoped down** — checked `viewmodel/AppUpdateViewModel.kt:88`: the raw `verify:sha256 mismatch` string was already never rendered (the update-failed dialog shows one fixed generic message; `reason` is internal-only) — no fix needed there. Did differentiate the backup catch-all: `backup_failed` was one string for export/import/share; replaced with `backup_export_failed`/`backup_import_failed`/`backup_share_failed` per operation (`BackupViewModel.kt`), and removed the now-dead `backup_failed` string.
- UX-P2-7 — Remove the dormant `AppLanguage.ENGLISH` enum branch (only a one-line `.filter` at `SettingsScreen.kt:183` hides it; no `values-en` exists) until en translations ship (M5).

Behavior nits:
- UX-P2-8 — Sample section force-reopens after user collapsed it (`LaunchedEffect(hasSampleData)` re-fires). `ui/board/AddTeaScreen.kt:128`.
- UX-P2-9 — Backup busy dialog has no cancel and no "taking a while" fallback — a hung SAF write traps the user. `ui/board/SettingsScreen.kt:355`.
- UX-P2-10 — Drag-to-rank and color-swatch-opens-picker have no affordance (menu alternative exists but must itself be discovered): one-time coach mark / drag glyph (`BoardScreen.kt:628`), edit icon on swatch (`TierEditorScreen.kt:225`).
- UX-P2-11 — CatalogDetailSheet: hardcoded 200dp image height; primary "Use this tea" CTA not pinned, buried under scroll on short screens. `ui/board/CatalogDetailSheet.kt:152`.
- UX-P2-12 — `movePlacement` fire-and-forget per drag, no per-board serialization — rapid drags can order against a stale snapshot (self-healing, transient). `viewmodel/BoardViewModel.kt:73`.
- UX-P2-13 — **DONE (Batch 1)** — `submit()` no longer wraps insert + photo copy + enrichment in one `runCatching`; only the insert is guarded for total failure, so a post-insert photo/enrichment error no longer reports the whole save as failed (which drove retry→duplicate). `viewmodel/AddTeaViewModel.kt`.
- UX-P2-14 — Undo snapshots for deleteBoard/deleteTea load full tables; `tea()` linear-scans all placements first. Scope DAO queries by id. `TeaBoardRepository.kt:181-186,240-246,345-353`.
- UX-P2-15 — Diagnostics opt-in has zero delivery visibility (failures silently dropped); add a "last report failed" local status. `data/diagnostics/DiagnosticsWire.kt:66`.
- UX-P2-16 — `OnboardingState.consumeReseedPending()` mutates on read — split read from consume. `data/repository/OnboardingState.kt:29-36`.

## Known deferred — do NOT re-litigate here

Geopoints/maps (#20, M6) · tier-image sharing (#27; current text share is a documented stand-in) ·
en/zh UI translations (#48/#94, M5 — `values/` is deliberately ru-first per #12) · locale-aware
`displayName` (documented deferred in `Tea.kt:44-47`) · cloud auto-sync of exports · durable
WorkManager enrichment (#70.6 / AND-P1-4) · v7 `publicId` UUID ref key (AND-P0-2) · in-app
updater deliberately dormant (#144 routes to GitHub/Obtainium; machinery retained) · staged
rollout (never specced) · CJK handwriting OCR · enrichment field-ownership (AND-P1-3) ·
bounded image reads (AND-P1-5 — covers the `ImageReader.kt:44` full-buffer note).

## Suggested batching

1. **PR "data integrity"** — UX-P0-1, UX-P0-2, UX-P2-13 (same code path), + unit tests for the submit guard and transactional dedup.
2. **PR "stop lying to the user"** — UX-P1-1, UX-P1-2, UX-P1-6, UX-P1-7, UX-P2-6 (all are "surface the real failure"; shares new strings).
3. **PR "search & enrichment"** — UX-P1-4, UX-P1-5, UX-F-4.
4. **PR "polish batch"** — UX-P2-1..5, 7..11, 16 (mechanical, low-risk; one pass like the earlier polish plan).
5. **PR "collection ergonomics"** — UX-F-1 (facets), UX-F-2 (sort), UX-F-3 (fuzzy own-search).
6. Later / owner decisions: UX-P1-3 (draft persistence — small but touches VM lifecycle), UX-P1-8 (measure first at ~500 teas before optimizing), UX-P2-12, UX-P2-14, UX-P2-15, UX-F-6 (brewing log — scope with owner).
