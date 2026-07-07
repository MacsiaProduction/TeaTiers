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
| UX-P0-1 | **Save double-tap creates duplicate teas.** `submit()` has no in-flight guard, and `addTea` does check-then-insert dedup as separate suspend calls, not one transaction — two rapid taps race past the dedup. | `viewmodel/AddTeaViewModel.kt:431`, `data/repository/TeaBoardRepository.kt:262` | OPEN |
| UX-P0-2 | **One DB read error kills every board screen forever.** `repository.boards` is a Room flow collected on the app scope with no `.catch` and no scope exception handler — a query failure either crashes the app or silently freezes all board-dependent screens with no error or retry. | `data/repository/TeaBoardRepository.kt:125` | OPEN |

Fixes: `isSaving` guard + disable Save while in flight; wrap dedup-check+insert in one `@Transaction`
DAO method; `.catch` on the boards pipeline exposing an error/retry state.

## P1 — silent failures & major friction

| ID | Finding | Where | Status |
|---|---|---|:--:|
| UX-P1-1 | **Photo add silently does nothing** for oversized (>8MB) images (`copyIn` returns null) and for disk-full (generic IOException swallowed). No "too large" / "out of storage" message. | `data/photos/AndroidPhotoStore.kt:53,74` | OPEN |
| UX-P1-2 | **Restore lies about its safety net.** If the pre-restore safety snapshot fails to write (disk full/IO), the destructive restore proceeds anyway and shows the same "Imported" success — user thinks Undo exists when it doesn't. | `data/backup/BackupManager.kt:238` | OPEN |
| UX-P1-3 | **Process death wipes an in-progress Add form.** After process death the restored `entryToken` no longer matches the fresh VM's null, so typed input is silently reset. Persist a minimal draft via `SavedStateHandle`. | `viewmodel/AddTeaViewModel.kt:195` | OPEN |
| UX-P1-4 | **Single-CJK-char search never searches.** `MIN_CATALOG_QUERY_LEN = 2` gates both search VMs, so `茶` falls back to unfiltered browse — while the server's `strpos` path was *specifically built* for short/CJK queries. Special-case CJK codepoints (len ≥ 1). | `viewmodel/CatalogSearchModels.kt:52`, cf. `server/.../TeaSearchRepositoryImpl.kt:87-93` | OPEN |
| UX-P1-5 | **Enrichment gives up too early and never retries.** Poll budget ≈12s (8×1.5s) marks slower jobs FAILED permanently; `resumePending` runs once per process, so a tea stuck QUEUED from an offline add is never retried until full app restart. Longer/backoff poll ceiling + re-arm on reconnect. (Durable WorkManager version stays TRACKED as AND-P1-4/#70.6.) | `data/repository/TeaEnrichmentManager.kt:75-82,119-152` | OPEN |
| UX-P1-6 | **Server error detail thrown away.** The server sends RFC-7807 `problem+json` with human-readable `detail` on every error path, but the app parses the body only for 410 — 404/429/503 all collapse to one generic error string. Parse `ProblemDetail` generally and surface it. | `data/repository/CatalogRepository.kt:205` (cf. `:96-107` OCR 429/503) | OPEN |
| UX-P1-7 | **"Check for updates" offline looks like "you're up to date."** `check()` catches all exceptions as `UpdateAvailability.None` — indistinguishable from current. `UpdateUiState.Failed` exists in the UI but may be unreachable via this path. | `data/update/AppUpdateChecker.kt:46-49` | OPEN |
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
- UX-P2-1 — **DONE (Batch 4)** — the expand/collapse row now carries a state-aware `contentDescription` ("Показать/Скрыть данные образца"); the icon itself stays decorative. `ui/board/AddTeaScreen.kt`.
- UX-P2-2 — **DONE (Batch 4)** — added visible move-left/move-right corner buttons on each photo thumbnail (mirroring the existing remove-button style), wiring up the previously-dead `onMoveLeft`/`onMoveRight` callbacks and `a11y_photo_move_left/right` strings for sighted users who can't or won't long-press-drag. `ui/board/PhotoStrip.kt`.
- UX-P2-3 — **DONE (Batch 4)** — bumped to 48dp (Material's minimum). `ui/board/TierEditorScreen.kt`.
- UX-P2-4 — **DONE (Batch 4), one deliberate skip** — added `maxLines`/overflow to the purchase-row title, board name, featured-tea blurb, and the fixed-width tier-label box (the sharpest case: a genuinely fixed 46dp box that could clip/overlap). Skipped `tea.notes` on the full detail screen — that's a natural-flow scrollable section where full, untruncated notes are the correct behavior, not a layout-breaking risk; truncating it would be a regression. Left the `.take(2)` CJK code-unit note as informational (tier labels are documented as staying terse; not a correctness bug worth a grapheme-safe rewrite for a 2-char preview).
- UX-P2-5 — **DONE (Batch 4)** — `filterMyTeas` now sorts via `Collator.getInstance(Locale("ru"))` (SECONDARY strength, ru-first per decisions.md #12) instead of `.lowercase()` + raw compareTo.

Copy / errors:
- UX-P2-6 — Generic catch-alls: `error_generic` ("Что-то пошло не так"), `backup_failed` ("Не удалось. Попробуйте ещё раз") — differentiate by operation; export failures collapse causes (`BackupManager.kt:92`); APK verify failure surfaces raw `verify:sha256 mismatch` (`viewmodel/AppUpdateViewModel.kt:88`).
- UX-P2-7 — **Reviewed (Batch 4), no change** — the code already documents exactly this tradeoff at the filter site ("The enum keeps ENGLISH so a previously-persisted 'en' still resolves; we just don't present it"). Removing the enum value would make `appLanguageOf()` misdetect a genuinely-set English per-app locale — a real regression — to guard against a hypothetical future accidental `.filter` removal. Not worth that trade for a P2; the existing comment already does the job.

Behavior nits:
- UX-P2-8 — **DONE (Batch 4)** — added a `sampleManuallyToggled` flag: once the user taps the chevron (expand or collapse), the auto-expand-on-data effect is permanently suppressed, so it can no longer silently re-open a section the user just closed. `ui/board/AddTeaScreen.kt`.
- UX-P2-9 — **DONE (Batch 4)** — no safe cancel exists (an in-flight archive write can't be interrupted without risking a half-written file), so instead a delayed "this can take a while" hint appears after 8s, telling the user the app hasn't hung. `ui/board/SettingsScreen.kt`.
- UX-P2-10 — **Partially done (Batch 4)** — added a small edit-glyph badge on the tier-color swatch (`TierEditorScreen.kt`) signaling it opens a picker. Skipped the drag-to-rank coach-mark: the existing overflow "move to tier" menu already mitigates discoverability, and a one-time coach-mark system (persisted dismiss state, overlay UI, timing) is a bigger feature than this polish batch's scope.
- UX-P2-11 — **DONE (Batch 4), scoped down** — image height is now `aspectRatio(16f/9f)` instead of a fixed 200dp, so it scales with the sheet's width. Skipped pinning the CTA: restructuring `ModalBottomSheet`'s content into a scrollable-body + pinned-footer layout risks breaking its drag-to-dismiss/nested-scroll interaction — too risky for a P2 without dedicated device testing.
- UX-P2-12 — `movePlacement` fire-and-forget per drag, no per-board serialization — rapid drags can order against a stale snapshot (self-healing, transient). `viewmodel/BoardViewModel.kt:73`.
- UX-P2-13 — `submit()` wraps insert + photo copy + enrichment in one `runCatching`: a post-insert failure reports as total failure though the tea saved → user retries → duplicate. Narrow to the insert. `viewmodel/AddTeaViewModel.kt:466`.
- UX-P2-14 — Undo snapshots for deleteBoard/deleteTea load full tables; `tea()` linear-scans all placements first. Scope DAO queries by id. `TeaBoardRepository.kt:181-186,240-246,345-353`.
- UX-P2-15 — Diagnostics opt-in has zero delivery visibility (failures silently dropped); add a "last report failed" local status. `data/diagnostics/DiagnosticsWire.kt:66`.
- UX-P2-16 — **DONE (Batch 4)** — `OnboardingState.isSeeded()` is now a pure read; the reseed-pending consumption moved to a new, separately-named `consumeReseedPending()` called only at the one call site that decides whether to reseed (`TeaBoardRepository.init`). `data/repository/OnboardingState.kt`.

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
