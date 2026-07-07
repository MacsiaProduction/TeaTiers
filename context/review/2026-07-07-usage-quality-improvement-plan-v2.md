# 2026-07-07 — Usage-quality audit round 2 (UX2-)

Round 2 of the usage-quality/UX audit (round 1: `2026-07-06-usage-quality-improvement-plan.md`, batches
landed in PRs #191–#195 — data integrity, error-surfacing, search & enrichment, polish, collection
ergonomics). Round 1 fixed the classic gaps; this pass re-read the **entire** app (`ui/`, `viewmodel/`,
`data/`) and the server surfaces the client feels (search/resolve/OCR errors, rate limits, update
manifest), specifically hunting for issues round 1 *didn't* touch. Six parallel read-only passes (UI
screens & navigation, ViewModel/repository behavior, feature-completeness vs `task.md`/`plan.md`,
copy/localization/a11y, server-felt client behavior, system surfaces — backup/photos/update/settings) by
independent agents, each instructed not to re-report round-1 fixes; findings below are synthesized,
deduped, and severity-checked against the actual code (the two P0s were personally re-verified against
`TeaBoardRepository.kt`/`TeaDao.kt`/`TeaDetailViewModel.kt` before being included — one sub-agent finding
that didn't hold up under verification, a claimed missing snackbar on "use reference as my rating," was
dropped: the snackbar already exists).

**Verdict:** still no architectural problems. Round 1's "silent failure" class of bug recurs in two new,
concrete spots — both genuine **lost-update races on the shared tea row** (P0). Beyond that: a handful of
**dead-end/major-friction UI flows** (drag-only reorder with no fallback, a board-picker with no
"create new" escape, an OCR dialog that hijacks confirm/dismiss semantics), a **second wave of the same
"error swallowed into a generic message" pattern** round 1 fixed for 410/429/facets but not yet for
502/503 on search/resolve or for OCR's timeout/format-error paths, two genuinely-missing **feature
entry points** (camera capture for tea photos, multi-select gallery pick), and a long tail of P2 polish.

Status legend: `OPEN` · `VERIFY` (plausible, needs one more read to confirm before scheduling a fix) ·
`DONE` · `TRACKED` (already in `INDEX.md`/round-1 doc under another ID — don't duplicate).

## P0 — data integrity (lost updates on the shared tea row)

| ID | Finding | Where | Status |
|---|---|---|:--:|
| UX2-P0-1 | **Editing a tea can silently blast away a just-landed enrichment patch, or vice versa.** `TeaBoardRepository.updateTea` → `TeaDao.updateTeaFields` unconditionally overwrites every scalar column (name/type/origin/notes/vendor/product/harvestYear/batch/grade) from whatever `Tea` snapshot the caller holds — no merge, no CAS, no transaction shared with the enrichment path. `applyEnrichmentPatch` (`TeaDao.kt:284-314`) is deliberately hardened with a blank-only read-merge-write *from the enrichment side* (2026-06-19 review comment) precisely because this class of race was already known — but that hardening only protects enrichment from clobbering a user edit, not the reverse. A user who opens Edit right after adding a tea (enrichment in flight), types something, and hits Save while enrichment is still resolving: whichever write lands last wins outright, silently discarding the other's fields. | `data/repository/TeaBoardRepository.kt:505-531`, `data/db/TeaDao.kt:220-243,284-314` | OPEN |
| UX2-P0-2 | **"Use reference as my rating" round-trips the whole tea through the same unguarded path, from a stale StateFlow snapshot.** `TeaDetailViewModel.useReferenceAsMyRating` reads `tea.value` (a StateFlow snapshot) and calls `repository.updateTea(id, current.copy(flavor = reference))` — i.e. the *entire* row, not just `flavor`, through the exact overwrite path in UX2-P0-1. Since "shared teas ripple everywhere" (decisions.md #42 — the same tea can sit on multiple boards, and its detail screen + edit screen both observe the same id), a concurrent edit landing between this snapshot read and its write is silently discarded — not just flavor, but name/notes/purchases too. Reachable via ordinary navigation (detail screen open in one place, edit in another, or back-then-forward while a save is in flight), not a contrived scenario. | `viewmodel/TeaDetailViewModel.kt:120-129` | OPEN |

Fixes: give `updateTea` a diff-against-current or field-list-scoped write (mirroring `applyEnrichmentPatch`'s
pattern) instead of an unconditional full-row overwrite; scope `useReferenceAsMyRating` to a flavor-only
DAO call instead of round-tripping the whole `Tea`.

## P1 — silent failures & major friction

| ID | Finding | Where | Status |
|---|---|---|:--:|
| UX2-P1-1 | **Reordering two teas within the same tier is drag-only, with zero fallback** — the one gap round 1's own fallback pattern (photo move-left/right, tier move-up/down) missed. The board's overflow menu and TalkBack custom actions both offer "move to tier X" but explicitly filter out same-tier moves (`groupKey(it.tierId) != currentKey`), leaving no non-drag path to swap the rank of two teas already in the same tier — the single most central and most-practiced interaction in the app. | `ui/board/BoardScreen.kt:607-653,668-725` | OPEN |
| UX2-P1-2 | **The "add to board" picker has no "create a new board" option once any board exists.** `onGoCreateBoard` only renders when the board list is empty. A user browsing the catalog who wants to add a found tea to a *brand-new* board (a very plausible flow) must cancel, go create a board from Boards, then re-navigate to Browse Catalog and re-search from scratch — a full flow restart for a one-tap-away need. | `ui/board/BrowseCatalogScreen.kt:286-339` | OPEN |
| UX2-P1-3 | **Backup import/restore has no ViewModel-level re-entrancy guard on a destructive full-table replace** — the same bug class as round 1's fixed AddTea double-submit (UX-P0-1), but here on the wipe-and-reinsert path. `exportTo`/`importFrom`/`restoreSafetyBackup`/`share` each set `_busy` unconditionally with no check against a call already in flight; the only real guard today is the blocking dialog rendering in time. A recomposition race or fast double-tap could interleave two destructive `replaceAll` calls. | `viewmodel/BackupViewModel.kt`, `data/db/TeaDao.kt:401-416` (`replaceAll`) | OPEN |
| UX2-P1-4 | **Deleting a tier with ranked teas in it has no Undo**, inconsistent with every other destructive action in the app (tea/board delete both got Undo snackbars in round 1). Only a plain confirm dialog stands between a misclick and the tier's teas losing their placement. | `ui/board/TierEditorScreen.kt:167-193` | OPEN |
| UX2-P1-5 | **`/search`, `/browse`, and `/resolve` still collapse 429/503 into the same generic error as a real server fault** — round 1 fixed this narrowly for `/teas/{id}` detail (`CatalogDetailResult.RateLimited`) but not for the other three call sites. `EdgeOverloadException` (503, explicitly documented server-side as transient/not-the-user's-fault) and the search rate limiter's 429 both funnel into a flat `Error` state, so a legitimate burst (adding several teas back-to-back, a few flatmates on shared wifi) reads identically to "the backend is broken." | `data/repository/CatalogRepository.kt` (search/browse/resolve paths), `server/.../controller/TeaController.kt:69-70,111` | OPEN |
| UX2-P1-6 | **OCR's client-side timeout (15s, shared OkHttp default) is shorter than the server's own realistic worst case (~40s: 20s read timeout × up to 2 attempts).** A live-but-slow sidecar times out client-side first and gets mapped to `OcrResult.Offline` → "Нет сети — распознавание недоступно," which is simply false — the user has network, the OCR service was just still working. No "still recognizing…" hint appears before the socket gives up either. | `di/NetworkModule.kt:51`, `server/.../client/OcrClient.kt` (`OcrProperties.kt:34`, `MAX_ATTEMPTS=2`), `data/repository/CatalogRepository.kt:282-283` | OPEN |
| UX2-P1-7 | **A malformed/no-text OCR response and a hard sidecar outage collapse into the same "try later" message**, which is actively bad advice for the far more common bad-photo case. A 200 response missing `text` is not retried and throws the same `OcrFailedException`→502 path as a genuine outage, landing on `ocr_unavailable` ("try again later") when the actually useful guidance is "no legible text found — try a clearer photo" (a distinct string, `ocr_no_text`, already exists but isn't reached here). | `server/.../client/OcrClient.kt:56-60`, `server/.../service/OcrService.kt`, `data/repository/CatalogRepository.kt:288` | OPEN |
| UX2-P1-8 | **A pending "undo" snackbar from picking a catalog tea can restore a stale form onto a *different* Add-Tea session.** `pickCatalogTea`'s Undo action closes over the pre-pick form snapshot; the ViewModel instance is explicitly reused across navigations (comment at `AddTeaViewModel.kt:181-183`). If the user picks a tea, navigates away before the snackbar times out, and `bind()` re-runs for a different entry, a still-alive Undo tap stomps the newly-bound form with the old tea's stale snapshot. | `viewmodel/AddTeaViewModel.kt:181-183,352-371` | OPEN |
| UX2-P1-9 | **The update-failure dialog collapses download/verify/install failures into one generic message.** `UpdateUiState.Failed(reason, ...)` already carries a specific cause (`"download"` / `"verify:sha256 mismatch"` / `"install:<Ackpine failure>"`), but Settings never reads `reason` and always shows the same "something went wrong, download manually from GitHub" text — no differentiation between a dropped download, a rejected/tampered APK, or a missing install-unknown-apps permission. | `viewmodel/AppUpdateViewModel.kt:34,62,80,91,99`, `ui/board/SettingsScreen.kt:467-482` | OPEN |
| UX2-P1-10 | **Seeded sample data has no persistent marker or one-tap "clear samples," confirmed independently by two separate read passes.** After the one-time dismissible intro card is closed, a first-run sample tea (e.g. "Да Хун Пао" with a first-person note) is visually and structurally identical to the user's own data forever after — no badge, tag, or bulk-clear action, only tedious per-item delete. Real risk of a new user either mistaking samples for their own collection or not realizing they should prune them. | `data/sample/SampleBoardProvider.kt`, `ui/board/BoardsScreen.kt` (`IntroCard`) | OPEN |
| UX2-P1-11 | **The OCR "scan packaging" source-chooser dialog overloads confirm/dismiss for two equal, non-hierarchical choices with no real Cancel** — `confirmButton` triggers camera, `dismissButton` triggers gallery, breaking the button-role convention every *other* dialog in this exact file follows (dismiss = cancel). The only true cancel is tapping the scrim, unlabeled and non-obvious the first time. | `ui/board/AddTeaScreen.kt:574-595` | OPEN |
| UX2-P1-12 | **VERIFY — deleting the last remaining tier is unguarded** and may strand existing teas with nowhere ranked and no obvious path back. `onDeleteClick` has no `enabled = tiers.size > 1` guard (unlike move-up/down, which do gate on position); a zero-tier board could plausibly render as a false "fully empty, add a tea" state via `isFullyEmpty`'s vacuous-true check on an empty tier list. **Not yet confirmed against `BoardViewModel.removeTier`'s actual handling** — read that before scheduling a fix. | `ui/board/TierEditorScreen.kt:281-287`, `ui/board/BoardScreen.kt:236-243` | VERIFY |

## Feature gaps worth building (user-expected, verified absent)

| ID | Feature | Impact | Notes | Status |
|---|---|:--:|---|---|
| UX2-F-1 | **No camera-capture entry point for tea photos.** The photo "+" tile only launches a gallery picker (`PickVisualMedia`); the app's only camera launcher feeds the OCR scan flow, whose image bytes are explicitly discarded afterward ("never persisted locally"). A user wanting to photograph their tea tin/leaves directly has to go through the OS camera app and re-select from gallery. | med | `ui/board/PhotoStrip.kt:90-92,138-144`, `viewmodel/AddTeaViewModel.kt:263-292` | OPEN |
| UX2-F-2 | **Gallery photo picking is single-select only** (`PickVisualMedia`, not `PickMultipleVisualMedia` — zero call sites for the latter anywhere in the repo). Adding several photos of one tea means repeating pick→wait→pick per image instead of one multi-select. | low-med | `ui/board/PhotoStrip.kt:90-92,138-144` | OPEN |
| UX2-F-3 | **Catalog search result rows never show a distinct `nameEn`** when it differs from the displayed name — it's only used internally as a fallback tier, never rendered as its own line. Minor in the ru-first MVP but a real gap for disambiguating similar ru/zh names. | low | `ui/board/AddTeaScreen.kt:881-895` | OPEN |
| UX2-F-4 | **Attributions screen covers only the 4 external data sources (Wikidata/Wikipedia/Wikimedia/OFF), not third-party library/OSS licenses.** May be fine (the screen does its stated job) — flagging only because "attributions" often implies OSS license disclosure too; needs an owner call on whether that's actually required (e.g. distribution-channel compliance). | low | `ui/board/AttributionsScreen.kt:129-158` | DECIDE |

**Verified absent, no action needed:** `task.md`'s "integrate with all available tea databases... or
download them locally" (only one source, artoftea.ru, is wired) and "write our own model in python to
backfill flavors" (no Python model exists outside `ocr-sidecar/`) are both literal spec lines with zero
code trace — but neither is a felt user gap today: the single-source catalog works fine, and LLM-based
enrichment already meets the flavor-backfill need a different way. Not tracked as F items; revisit only if
catalog breadth becomes a real complaint (see `research/16-catalog-breadth/`, already the operative
answer for that question).

## P2 — polish

Accessibility / layout:
- UX2-P2-1 — Add-Tea's inline catalog-search result list has no height cap, unlike the equivalent dialog list in Browse Catalog (`heightIn(max = 320.dp)`) — a long result set can push the required name field arbitrarily far down. `ui/board/AddTeaScreen.kt:787-798` vs `ui/board/BrowseCatalogScreen.kt:303-306`.
- UX2-P2-2 — `CatalogDetailSheet`'s error/status blocks use a fixed non-scrolling `heightIn(min=180-200dp)` — a long ru error string at large font scale risks clipping the retry button. `ui/board/CatalogDetailSheet.kt:103-139`.
- UX2-P2-3 — `FlavorRadar` labels have no width/overlap guard; up to 11 flavor axes with long ru dimension names could overlap around the circle. `ui/components/FlavorRadar.kt:92-99`.
- UX2-P2-4 — The notes field uses a fixed `.height(112.dp)` instead of `heightIn(min=...)`, unlike other multi-line fields in the same screen — clips at large font scale instead of growing. `ui/board/AddTeaScreen.kt:481`.
- UX2-P2-5 — Photo-thumbnail remove/move `IconButton`s are 40dp (a documented, deliberate trade-off comment — "capped at 40 to fit the 96dp thumb corner"), below the 48dp bar round 1 set for `TierEditorScreen`. Confirm as an accepted exception or grow the touch target beyond the visual bounds. `ui/board/PhotoStrip.kt:233-239,285`.
- UX2-P2-6 — Zero Compose `placeholder=` usage anywhere in the app (systemic, confirmed by repo-wide grep) — Material's floating label alone doesn't convey expected format (e.g. a marketplace URL field gives no example).
- UX2-P2-7 — Tapping "Info" on a catalog search result opens the detail bottom sheet while the search field's IME is still up, and the sheet has no `imePadding()` — can visually crowd/hide sheet content on small screens. `ui/board/AddTeaScreen.kt:898`, `ui/board/CatalogDetailSheet.kt:148-155`.

Copy / errors:
- UX2-P2-8 — `error_generic` is reused across ~16 unrelated board/tea/photo mutation failure sites (rename, reorder, photo save, purchase save…) — round 1 split this specifically for backup export/import/share but didn't extend the pattern here. Not misleading, just undifferentiated. `viewmodel/BoardsViewModel.kt`, `BoardViewModel.kt`, `AddTeaViewModel.kt`, `TeaDetailViewModel.kt`.
- UX2-P2-9 — `/resolve`'s `UNRESOLVED` status conflates three causes (genuine no-match / LLM tier disabled / daily budget exhausted) with no client-visible distinction; a user-triggered retry blocked by budget exhaustion also looks identical to before the tap. `server/.../service/ResolveService.kt:60-64,86-93`.
- UX2-P2-10 — Shared-NAT rate-limit collisions (several devices behind one home router sharing the per-IP bucket) get the same generic "too many requests" copy as actual abuse. `server/.../service/RateLimiterConfig.kt`.
- UX2-P2-11 — The daily LLM enrichment budget is global across the whole userbase with no client-visible signal when it's the reason a tea comes back unenriched on a high-traffic day. `server/.../service/DailyBudget.kt`.
- UX2-P2-12 — `cd_liquor` string is defined but has zero references anywhere in the codebase — dead resource. `res/values/strings.xml:145`.

Behavior nits / consistency:
- UX2-P2-13 — My Teas has a sort menu (name/type); Browse Catalog has none — asymmetric capability on visually similar list screens. Separately, renaming uses a blocking dialog on Boards but inline live-save on Tier Editor for the same conceptual action — a user has to relearn "how do I rename here" per screen. `ui/board/MyTeasScreen.kt` vs `BrowseCatalogScreen.kt`; `BoardsScreen.kt:190-199` vs `TierEditorScreen.kt:253-266`.
- UX2-P2-14 — The `sourceText` (pasted vendor description) character counter never changes color/warns near its 4000-char cap, and both the field's own input and a merged OCR-scanned addition silently stop accepting/get truncated at the limit with no notice. `ui/board/AddTeaScreen.kt:502-519`, `viewmodel/AddTeaViewModel.kt:303-312`.
- UX2-P2-15 — Rapid double-tap on "retry enrichment" silently no-ops on the second tap (already-in-flight guard exists, but gives zero feedback that the tap did nothing). `viewmodel/BoardViewModel.kt:48-50`, `data/repository/TeaEnrichmentManager.kt:109-135`.
- UX2-P2-16 — My Teas recomputes a full per-tea Levenshtein search pass on every keystroke with no debounce (unlike the catalog search box, which is explicitly debounced) — a jank/perf issue related in spirit to the still-open UX-P1-8 (My Teas join perf, tracked in the round-1 doc, not duplicated here). `viewmodel/MyTeasViewModel.kt:47-63`.
- UX2-P2-17 — `/teas/facets` is fetched exactly once in `BrowseCatalogViewModel.init` with no retry — a cold-offline start permanently hides the type-filter chips for that session even after the user reconnects and successfully browses. `viewmodel/BrowseCatalogViewModel.kt:109-111`.
- UX2-P2-18 — `BoardViewModel`'s enrichment-resume gate is per-VM-instance (narrower variant of the round-1-fixed manager-level cooldown) — reopening the same board within the VM's lifetime and the 5-minute cooldown window won't re-trigger a stuck-QUEUED resume. `viewmodel/BoardViewModel.kt:37-45`.
- UX2-P2-19 — `harvestYear` and the marketplace purchase-location URL both accept anything with zero validation — bad/overflowing input is silently dropped or accepted-as-is with no error shown. `viewmodel/AddTeaModels.kt:99,114-122,149`.
- UX2-P2-20 — `AppUpdateViewModel.check()` and `DiagnosticsViewModel.setEnabled` both lack the re-entrancy/error-handling guard most other mutating calls have (rapid re-tap races stale results onto shared state; a DataStore write failure leaves the UI toggle and persisted opt-in silently inconsistent). `viewmodel/AppUpdateViewModel.kt:55-65`, `viewmodel/DiagnosticsViewModel.kt:22-31`.
- UX2-P2-21 — `TeaDetailViewModel`'s reference-detail fetch collapses every failure mode (offline/rate-limited/error/retracted) to `null` with a "fail-closed" comment and no retry — a transient network hiccup permanently hides the reference-flavor/CC-image block for that VM's lifetime with zero indication anything failed. `viewmodel/TeaDetailViewModel.kt:88-100`.
- UX2-P2-22 — Broken/missing photo references are handled inconsistently: `TeaCard`'s board-card thumbnail has no Coil `error`/`placeholder` fallback (silent blank square) where `PhotoStrip`'s equivalent shows a broken-image glyph for the same condition; separately, `PhotoStore.reconcile()` only sweeps orphan *files*, never detects/heals a DB row pointing at a since-deleted file, so a lost photo renders broken forever with no offered cleanup. `ui/components/TeaCard.kt:40-55` vs `ui/board/PhotoStrip.kt:316-333`; `data/photos/PhotoStore.kt:46`, `AndroidPhotoStore.kt:120-143`.
- UX2-P2-23 — Long-running system operations have inconsistent progress signaling: backup import shows only a static "please wait" + a fixed 8s hint despite streaming photos one at a time (no count/percentage), and the update "Working" dialog has no slow-hint at all (unlike backup's) plus Ackpine's raw install-failure reason is never logged for debugging. `viewmodel/BackupViewModel.kt`, `data/backup/BackupManager.kt:205-210`, `ui/board/SettingsScreen.kt:356-386,455-465`, `data/update/AppInstaller.kt:37-48`.
- UX2-P2-24 — A revoked photo-URI permission grant (`SecurityException`) collapses into the same generic "copy failed" as a disk I/O error, and the backup size-cap rejection doesn't distinguish "genuinely too large" from "corrupt/zip-bomb-shaped" — both narrow edge cases, same fix theme (surface the distinct cause even if the displayed text stays similar for now). `data/photos/AndroidPhotoStore.kt:81-84`, `data/backup/BackupManager.kt:39-45`.
- UX2-P2-25 — VERIFY: a genuine pre-v7 (`formatVersion=1`) backup is silently accepted with v7-era fields defaulted away and no notice — likely theoretical since v1 predates the app's real user data (per code comment), but unconfirmed whether any real user holds one. `data/backup/BackupModels.kt:27`, `BackupManager.kt:186`.
- UX2-P2-26 — VERIFY, low confidence: switching language in Settings recreates the Activity immediately with no warning that unsaved in-memory state elsewhere in the back stack could be lost. `ui/board/SettingsScreen.kt:184-189`.
- UX2-P2-27 — Collapsing an expanded catalog description leaves scroll position disoriented (no scroll-to-position on toggle). `ui/board/CatalogDetailSheet.kt:250-277`.

Docs hygiene (not a user-facing gap, fix opportunistically):
- `context/plan.md` §11 still frames the backup import merge-vs-replace question as "open," though `decisions.md` #49 resolved it and the shipped UI is unambiguous (explicit "will be replaced" confirm copy). Stale-doc trap for a future auditor, zero real-user impact. `context/plan.md:667-670`.

## Carried over from round 1 — still OPEN, not re-litigated here

`UX-P1-3` (process-death wipes in-progress Add form), `UX-P1-8` (My Teas full-table-join perf — same
theme as UX2-P2-16 above), `UX-P2-12` (unserialized drag writes), `UX-P2-14` (Undo snapshots load full
tables), `UX-P2-15` (diagnostics delivery has zero visibility — independently re-confirmed still open by
this round's system-surfaces pass, see UX2-P1-10's neighbor findings), `UX-F-5` (re-openable tier-intro
help), `UX-F-6` (brewing/steep log, needs an owner scoping decision) — see
`2026-07-06-usage-quality-improvement-plan.md` for details; don't duplicate fixes here.

## Suggested batching

1. **PR "lost-update guards"** — UX2-P0-1, UX2-P0-2 (same root cause: `updateTea`'s unconditional
   full-row overwrite), + a regression test for a concurrent edit-vs-enrichment race.
2. **PR "second wave: stop swallowing errors"** — UX2-P1-5 (503/429 on search/resolve/browse),
   UX2-P1-6 + UX2-P1-7 (OCR timeout + malformed-response messaging), UX2-P1-9 (update-failure
   differentiation) — same family as round 1's "stop lying to the user" batch, different call sites.
3. **PR "board & tier interaction gaps"** — UX2-P1-1 (within-tier reorder fallback), UX2-P1-2
   (create-board shortcut in the picker), UX2-P1-4 (tier-delete Undo), UX2-P1-12 (verify + guard
   last-tier deletion).
4. **PR "dialog & VM correctness"** — UX2-P1-3 (backup re-entrancy guard), UX2-P1-8 (stale-Undo form
   stomp), UX2-P1-11 (OCR chooser dialog semantics), UX2-P1-10 (sample-data marker/clear action).
5. **PR "photo capture parity"** — UX2-F-1 (camera entry point), UX2-F-2 (multi-select gallery pick).
6. **PR "polish batch 2"** — the P2 list above, mechanical/low-risk, one pass like round 1's polish PR.
7. Later / owner decisions: UX2-F-4 (OSS license disclosure — confirm requirement first), UX2-P2-25 /
   UX2-P2-26 (verify-only items — resolve the open question before deciding whether to schedule a fix).
