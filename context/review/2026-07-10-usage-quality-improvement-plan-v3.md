# 2026-07-10 — Usage-quality audit round 3 (UX3-)

Round 3 of the usage-quality/UX audit, at `fac7a30` (after rounds 1–2 landed as PRs #191–#206; both
prior plans now condensed into `OLD-REVIEWS.md`). Six parallel read-only passes over the whole app +
server-felt surfaces, each seeded with the round-1/2 known-open list so they hunt only NEW findings:
(1) end-to-end journey walkthroughs, (2) per-screen state completeness / visual consistency,
(3) regression review of the fix batches themselves (#191–#206), (4) feature gaps vs real
tea-collector expectations, (5) server-felt behavior traced through both sides of every wire call,
(6) accessibility + ru copy quality. Every P0/P1 candidate was then **adversarially verified by an
independent skeptic pass** (10 verifiers instructed to refute): 8 confirmed, 1 downgraded to P2
(auto update check — wiring it before REL-P0-2's Ed25519 manifest would be a net harm), 1 copy
finding downgraded to P2, and one finder claim **refuted** (the journey pass's "drafts survive
process death" — see the UX-P1-3 note at the bottom).

**Verdict:** rounds 1–2 genuinely cleaned house — the fix batches were verified regression-clean
except the findings below, and whole areas (search paging/cancellation, rate-limit feel, backup
integrity, privacy honesty, drag fallbacks, offline degradation) came up clean. What round 3 found
is different in kind: **one old product-decision violation still live in shipped code** (the silent
name-match merge, decision #132 / v7 design finding #17), a band of P1s clustered around
**enrichment/scan session state** (the app's async machinery is correct but under-surfaced and
leaky across sessions), one **complete assistive-tech block** (tier color picker), and — for the
first time — a set of **product-level feature gaps** (stats, price tracking, consumption status)
that are now the biggest lever on real-user value, since the interaction-bug backlog is nearly dry.

Status legend: `OPEN` · `DONE` · `REVIEWED` (deliberate no-change) · `DECIDE` (owner call needed) ·
`TRACKED` (already in `OLD-REVIEWS.md`/`INDEX.md` under another ID).

## P0 — data integrity

| ID | Finding | Where | Status |
|---|---|---|:--:|
| UX3-P0-1 | **DONE ([#207](https://github.com/MacsiaProduction/TeaTiers/pull/207)).** Adding a tea whose typed name matches an existing tea silently discards everything the user just typed. `addTea` resolves a name match (case-insensitive nameRu/pinyin/nameZh, against ALL teas globally) and sets `teaToInsert = null` — only a placement + photos persist; flavor scores, notes, vendor/product/harvestYear/batch/grade, origin, purchases and `sourceText` are dropped (enrichment is skipped too since `created=false`). The UI pops back with **no message at all**; the board card shows the OLD tea's data. The `forceNew` escape ("Добавить ещё образец") is gated on `catalogTeaId != null` — its own comment claims "a custom add already always creates a new sample," which the code contradicts. **Violates locked decision #132** ("custom samples never auto-merged; name-match dedup is a non-blocking suggestion") and is v7 design finding #17, documented but never implemented. Reachability is high: vendor/batch/grade fields exist precisely so a user records a *second* sample of a same-named tea. | `data/repository/TeaBoardRepository.kt:306,319,561-599`, `data/db/TeaDao.kt:499`, `viewmodel/AddTeaViewModel.kt:493-516`, `ui/board/BoardScreen.kt:753-763`; intent: `context/decisions.md` #132, `context/design/tea-sample-split-v7.md:278` | **DONE** |

Fix (per the already-locked design): manual add **always creates a new sample**; the name match
becomes a non-blocking "looks like a tea you already have — reuse it or add as new?" prompt (the
fuzzy matcher built for My Teas search can power the near-miss case too); extend the
"add another sample" affordance to non-catalog teas; when a reuse *is* chosen, say so. Regression
test: add-with-matching-name persists the typed fields.

## P1 — dead ends, silent failures, major friction (all skeptic-confirmed)

| ID | Finding | Where | Status |
|---|---|---|:--:|
| UX3-P1-1 | **An orphaned tea ("Не в подборках") has no deliberate path back onto any board.** My Teas rows only open detail; the detail overflow has only Edit/Delete; edit mode explicitly hides the tier picker. Deleting a board strands every tea that lived only there. The only ways back are two *silent* resolve-or-create side effects (retype the exact name — which is UX3-P0-1's data-loss path — or re-pick the same catalog entry), neither presented as "restore to board." | `ui/board/MyTeasScreen.kt:188,209-256`, `ui/board/TeaDetailScreen.kt:100-127`, `ui/board/AddTeaScreen.kt:463-465` | **DONE (#210)** |
| UX3-P1-2 | **QUEUED/RATE_LIMITED enrichment has no user-drivable retry — a dead zone of up to 5 minutes.** The per-card retry menu renders only for FAILED; the sole recovery for the other two retryable states is `resumePending()`, gated by a process-wide singleton 5-min cooldown that any board open primes. `retry()` itself bypasses the cooldown (verified), so the fix is nearly free: extend the menu gate to `QUEUED`/`RATE_LIMITED`. The card copy "уточним при открытии" (strings.xml:174-175) overpromises per-open refresh — fixing the gate makes the copy true enough. Found independently by two passes. | `ui/board/BoardScreen.kt:764-772`, `data/repository/TeaEnrichmentManager.kt:64,82-104`, `data/db/TeaDao.kt:281-283` | **DONE (#208)** |
| UX3-P1-3 | **`/teas/resolve` client timeout (15s) is shorter than the server's synchronous Wikidata worst case (~22s)** — the exact bug class fixed for OCR in round 2 (UX2-P1-6), one endpoint over. On a cache-miss resolve (any brand-new/long-tail tea name) with slow-but-live Wikidata, the client times out first → `ResolveResult.Offline` → card says "Нет сети — уточним при открытии" while the network is fine. Self-heals as QUEUED (not FAILED), but the label lies for the whole UX3-P1-2 dead-zone window. | `di/NetworkModule.kt:56-66` (interceptor exempts only `/teas/ocr`), server `client/WikidataProperties.kt:14-15` + `WikidataSparqlClient.kt:76-101,142`, `data/repository/CatalogRepository.kt:281-282` | **DONE (#208)** |
| UX3-P1-4 | **Tea detail screen and My Teas render zero enrichment state for ANY state.** Both surfaces never touch `enrichmentState` (grep-clean; `MyTeaRow` is bespoke, not `TeaCard`); status + retry exist only on the board card. A user who opens a FAILED/QUEUED tea from detail or My Teas sees nothing in-flight, nothing wrong, and no retry. | `ui/board/TeaDetailScreen.kt` + `viewmodel/TeaDetailViewModel.kt` (no references), `ui/board/MyTeasScreen.kt:209-256`; working reference: `ui/components/TeaCard.kt:139,160-191` | **DONE (#208)** |
| UX3-P1-5 | **Stale OCR scan state leaks across Add/Edit sessions.** `bind()` resets the form/photos/query but never `_scan`, and `scanLabel`'s coroutine keeps no cancellable Job. Back out mid-scan, open Add Tea for a different tea: the new form shows the old scan's Recognizing spinner (scan button already disabled), and when the response lands, tea A's Review dialog pops over tea B's form — one tap merges tea A's packaging text into tea B's `sourceText` (which also feeds enrichment). Same family as the round-2 stale-Undo stomp (UX2-P1-8); untested in `AddTeaViewModelTest`. | `viewmodel/AddTeaViewModel.kt:86-87,204-211,265-293` | **DONE (#209)** |
| UX3-P1-6 | **No cancel during an in-flight scan.** While Recognizing (up to ~60s post round-2 timeout fix) the scan button is merely disabled; `cancelScan` is reachable only once Review appears. The only exit is system back, which doesn't cancel the request (see UX3-P1-5). Fix shares UX3-P1-5's tracked-Job work: make the Recognizing state an active Cancel. | `ui/board/AddTeaScreen.kt:495-509,609-641` | **DONE (#209)** |
| UX3-P1-7 | **The tier color picker is a complete TalkBack block.** The preset swatch grid is bare `Box.clickable` — no label, no role, no selection state (only a visual 3dp border); the palette is a nameless `List<Long>`. The same file's *row* swatch correctly uses `onClickLabel` (a11y_tier_color), proving the pattern was known and omitted here. The dialog's only labeled control ("Default") clears the color — an AT user cannot pick a specific one at all. | `ui/board/TierEditorScreen.kt:315-330` vs `:226-252`, `ui/theme/TierColorPalette.kt:12` | **DONE (#211)** |

## Feature gaps — the new frontier (verified absent; ranked by real-user demand)

The interaction-bug backlog is nearly dry; these are now the biggest lever on user value. All need
owner scoping — none are mechanical.

| ID | Feature | Demand | Notes | Status |
|---|---|:--:|---|:--:|
| UX3-F-1 | **Collection stats/overview** — zero place shows counts by type, tiers across boards, or growth. All data is local; a read-only "at a glance" card/screen is cheap. Round 1 (UX-F-1) explicitly skipped this as out of that finding's scope — promoting it now to its own decision. | high | `ui/nav/Destination.kt:16-35` (no such destination); repo-wide grep clean | DECIDE |
| UX3-F-2 | **Price/spend tracking is structurally impossible** — no price/currency/amount field exists on `Tea` or `PurchaseLocation`. "How much have I spent" / "price per gram" can't be answered. Schema + migration + two form fields. Never previously considered (grep decisions/plan clean). | high | `domain/model/PurchaseLocation.kt:8-20`, `domain/model/Tea.kt:10-31` | DECIDE |
| UX3-F-3 | **No consumption status** (wishlist / drinking / finished) — every tea looks alive forever. A status enum + My Teas filter chip (pattern exists) + form picker. Distinct from and much cheaper than the deferred brewing log (UX-F-6). | high | `domain/model/Tea.kt` (no field), `ui/board/MyTeasScreen.kt:148-169` (chip row to extend) | DECIDE |
| UX3-F-4 | **Notes is one overwritable string, not a dated journal** — tasting the same tea a year later means destroying the old impression. Journal-lite: `(text, timestamp)` child table, append-only list on detail. Explicitly NOT the brewing log (no session structure). | med-high | `domain/model/Tea.kt:20`, `ui/board/AddTeaScreen.kt:483-490` | DECIDE |
| UX3-F-5 | **No board duplicate / archive / seed-from-collection** — can't try a second ranking of the same teas, or retire a season's board without deleting it. Repository already snapshots whole boards for Undo (clone machinery exists). | med | `viewmodel/BoardsViewModel.kt` (4 ops only), `ui/board/BoardsScreen.kt:284-310` | DECIDE |
| UX3-F-6 | **No text-only board share** — the only export is the whole-DB backup zip. "Tier S: …, Tier A: …" via a share intent is data-in-memory, no bitmap needed. Decision #27's deferral literally covers only the *image* form, so all board-sharing is currently blocked by an unrelated deferral. | med | `viewmodel/BackupViewModel.kt:71-76` (zip only); grep `ACTION_SEND`/CSV clean | DECIDE |
| UX3-F-7 | **No bulk operations on My Teas** — pruning 50 teas is one-at-a-time through the detail confirm dialog. Long-press selection mode + bulk delete/move; no schema change. | low-med | `ui/board/MyTeasScreen.kt:180-192` | DECIDE |
| UX3-F-8 | **No board comparison / auto "all teas" board.** Partially mitigated by My Teas as a flat list; lowest priority of the set. | low | `viewmodel/BoardsViewModel.kt` | DECIDE |

(The feature pass's "duplicate near-miss prompt" folds into UX3-P0-1's fix — same root.)

## P2 — polish, consistency, hygiene

Loading/visual states (from the per-screen pass):
- UX3-P2-1 — BoardScreen renders a fully blank scaffold while `state == null` — the only screen with no spinner (Boards/Detail/Browse all have one). `ui/board/BoardScreen.kt:232-235`. **DONE (#212)**
- UX3-P2-2 — Tier rows + unranked tray compose every placement eagerly (`Row` + `horizontalScroll`, not `LazyRow`) — 200 cards in one group all measure at once. Convert or accept with a decision note. `ui/board/BoardScreen.kt:454-482,542-586`. OPEN
- UX3-P2-3 — My Teas shows the full "У вас пока нет чаёв" empty state before Room's first emission (defaults `collectionEmpty=true`, no loading flag) — indistinguishable from a genuinely empty collection. `ui/board/MyTeasScreen.kt:171-193`, `viewmodel/MyTeasModels.kt:31`. **DONE (#212)**
- UX3-P2-4 — Edit mode briefly renders a blank default form before `bind()` loads the tea (no loading gate on the form body). `viewmodel/AddTeaViewModel.kt:69`, `ui/board/AddTeaScreen.kt:112-114`. **DONE (#212)**
- UX3-P2-5 — `flavorsExpanded` is plain `remember` while its sibling `sampleExpanded` two lines down is deliberately `rememberSaveable` — rotation collapses the extended-flavor section mid-rating (values survive; the expansion doesn't). Verified. `ui/board/AddTeaScreen.kt:130-135`. **DONE (#212)**

Regression-pass residuals (batches otherwise verified clean):
- UX3-P2-6 — The boards-flow `.catch` is a one-shot dead end: one transient Room error freezes the StateFlow at `emptyList()` for the process lifetime — 10 boards render as the empty home state (reads as data loss). Documented ponytail trade-off, but round 1's own fix direction was an error state, not error-as-empty. `data/repository/TeaBoardRepository.kt:149-162`. **DONE (#212)** — consecutive-failure retry (self-heals transient errors); a distinct error-vs-empty UI state remains a deliberate deferral.
- UX3-P2-7 — Enrichment status copy overpromise — folds into UX3-P1-2's fix. `res/values/strings.xml:174-175`. TRACKED (UX3-P1-2)
- UX3-P2-8 — The always-visible "create a board" escape in the catalog board-picker silently drops the picked tea when boards already exist (explanatory string is gated on the empty case). Show the hint in both cases or carry the pick through. `ui/board/BrowseCatalogScreen.kt:178-193` (commit `cd616d9`). **DONE (#210)** — hint shown in every case.
- UX3-P2-9 — "Clear sample data" cascade-deletes the user's own placements on sample boards; the confirm copy doesn't say rankings are lost (tea rows survive; Undo exists). `viewmodel/BoardsViewModel.kt:98-115`. **DONE (#210)** — copy reworded (eviction, not just demotion).
- UX3-P2-10 — Camera-capture temp files linger (swept only on the *next* capture); delete `pendingCameraUri` in the `TakePicture` callback — both photo and OCR flows. `ui/board/PhotoStrip.kt:205-208`. SKIPPED — `captureUri` already sweeps its subdir per capture (bounded to one temp file per dir).
- UX3-P2-11 — Multi-photo pick has no count cap, and the `UiEvent` channel (capacity 8, `trySend`) silently drops failure snackbars #9+ in a mass-add. Cap via `PickMultipleVisualMedia(maxItems)` and/or aggregate. `viewmodel/UiEvent.kt:35`, `ui/board/PhotoStrip.kt:93-95`. OPEN
- UX3-P2-12 — UX2-P0-1's doc claims a 9-column merge; `TeaMergeFields` covers 6 (the other 3 write through — functionally fine since enrichment never touches them), and the merge test never covers deliberate blank-out of a shared field. Fix doc + add the test. `data/db/TeaDao.kt:27-34`, `TeaDaoUpdateTeaMergeTest`. OPEN
- UX3-P2-13 — `AppUpdateViewModel.check()` re-entrancy fix is unfalsifiable in CI (test uses `UnconfinedTestDispatcher`, which masks the exact fixed bug — a revert would pass). Add a `StandardTestDispatcher` double-check test. `AppUpdateViewModelTest.kt:42,83`. OPEN
- UX3-P2-14 — Facets self-heal fires from first-page/search loads but not `applyMore` — pagination-only healing won't restore the type chips. One call. `viewmodel/BrowseCatalogViewModel.kt:252-273`. OPEN

Server-felt residuals:
- UX3-P2-15 — Retried/resumed enrichment silently drops the user's `sourceText` grounding (`retry()`/`resumePending()` hard-code `sourceText = null`; the sample row never persisted it) — an offline-added tea's eventual LLM profile is invisibly ungrounded. Persist sourceText with the pending state. `data/repository/TeaEnrichmentManager.kt:85,101`. DEFERRED (own follow-up — touches the enrichment state-machine core for a quality-only gain)
- UX3-P2-16 — `resumePending()` fires only from board(s) screens; a session restored onto Detail/AddTea leaves QUEUED teas inert (and invisible there, per UX3-P1-4). One opportunistic call at app start; the cooldown already makes it safe. `viewmodel/BoardsViewModel.kt:43`, `viewmodel/BoardViewModel.kt:43`. **DONE (#208)** — `MainActivity.onCreate` resumes once per launch.
- UX3-P2-17 — Backup import round-trips `enrichmentState` faithfully but never re-dispatches — imported QUEUED teas wait for the next board open + cooldown. Call `resumePending()` (cooldown-reset) post-import. `data/backup/BackupManager.kt:165-237`. **DONE (#208)** — `resumePending(force=true)` after a successful import/restore.
- UX3-P2-18 — Picking a non-zip file for import lands on generic "restore failed" instead of the existing `backup_invalid_file` copy (raw `ZipException` falls through the generic catch). `data/backup/BackupArchive.kt:75-76`, `BackupManager.kt:175,226-228`. OPEN
- UX3-P2-19 — Double-tap Share can hand the chooser a deleted content URI (`createShareUri()` clears the whole cache dir per call; busy-guard covers only the write). Unique per-share filenames. `data/backup/BackupManager.kt:108-119,297-300`. OPEN
- UX3-P2-20 — Diagnostics copy's absolute "никогда не включает… пути" isn't enforced on ACRA stack traces (exception *messages* can embed paths/URIs). Low confidence — a dedicated trace found no exception built with user data; scrub message lines or soften the absolute claim. `data/diagnostics/DiagnosticsReportSender.kt:19-23`, `strings.xml:416`. DECIDE
- UX3-P2-21 — OCR wait is one bare 18dp spinner for a 10–60s operation — no upload-vs-inference phase, no elapsed hint (backup's 8s-hint pattern exists). `ui/board/AddTeaScreen.kt:495-509`. **DONE (#209)** — slow hint after 8s.
- UX3-P2-22 — Update check never fires automatically; `Forced`/`minSupportedVersionCode` is unreachable without a manual Settings visit. **Deliberately deferred by the skeptic pass:** wiring a startup check before REL-P0-2's Ed25519 manifest lands would let an unpinned manifest gate app usage; Obtainium/GitHub is the sanctioned channel today. Sequencing note: wire it WITH REL-P0-2, not before. `data/update/AppUpdateChecker.kt:33-37`. TRACKED (fold into REL-P0-2) 

A11y / copy (beyond UX3-P1-7):
- UX3-P2-23 — Zero `ImeAction.Next` anywhere: the ~14-field add form makes keyboard users dismiss and re-target per field. Add Next-chaining to sequential single-line fields. `ui/board/AddTeaScreen.kt`. OPEN
- UX3-P2-24 — Zero live regions: async outcomes (enrichment status flips, busy overlays) are never announced unless focus happens to sit on them. Start with `EnrichmentStatus`. `ui/components/TeaCard.kt:159-191`. **DONE (#211)** — opt-in live region on the detail screen.
- UX3-P2-25 — Settings switches use `selectable(role = Role.Switch)` — role/state mismatch makes TalkBack say "выбрано" instead of "включено". Swap to `toggleable`. `ui/board/SettingsScreen.kt:593`. **DONE (#211)**
- UX3-P2-26 — Missing `Role.Button` on list-item cards (TeaCard, BoardSummaryCard, MyTeaRow, CatalogResultRow, PurchaseRow) — pattern applied correctly elsewhere in the same files; one sweep. **DONE (#211)** — 5 cards.
- UX3-P2-27 — FlavorRadar containers are fixed `.height(240-260.dp)` while its Canvas labels scale with font size — magnification-mode clipping (TalkBack info is fine via the existing contentDescription). Related to but distinct from tracked UX2-P2-3 (label overlap). `ui/board/BoardScreen.kt:372`, `CatalogDetailSheet.kt:239`, `TeaDetailScreen.kt:354`. **DEFERRED** — Canvas-geometry change needing device-visual verification (finding's own note); TalkBack info is unaffected.
- UX3-P2-28 — `settings_about_privacy` says «Доски» — the app's one use of the term (35 lines say «Подборки»); a leftover literal translation of the `Board` class name, sitting in the most trust-sensitive string. One-word fix (skeptic downgraded from P1: the sentence's trust claim stays intact). `res/values/strings.xml:117`. OPEN
- UX3-P2-29 — Deleting a tea from Detail/Edit has no Undo and no confirmation feedback, while the identical action from a board card gets an Undo snackbar. Detail's omission is documented (snackbar host pops with the screen); AddTea's identical omission has no comment. Cheapest: thread the deleted snapshot through `onDeleted` so the destination hosts the Undo. `viewmodel/TeaDetailViewModel.kt:133-148`, `viewmodel/AddTeaViewModel.kt:576-583`. **REVIEWED (#210)** — confirm dialog is the deliberate safety net (parity detail↔edit); documented, no behavior change.

Doc/comment hygiene:
- `ui/board/AddTeaScreen.kt:109-110`'s comment claims the entry token preserves the form across
  "rotation/process-death" — the process-death half is false (the VM-side comment at
  `AddTeaViewModel.kt:195-200` is accurate) and it directly caused a false "fixed" claim in this
  round's journey pass. Fix the comment when touching the file; **UX-P1-3 stays OPEN** (no
  `SavedStateHandle` anywhere in the app — re-verified this round).

## Verified clean this round (don't re-audit soon)

Fix batches #191–#206 (regression pass, per-batch verdicts); search/browse paging + stale-response
cancellation (`flatMapLatest`/generation guards); rate-limit budgets vs client call patterns
(polling can't 429 — `/teas/{id}` has no limiter); backup export/import validation + safety-snapshot
+ FileProvider hygiene; privacy disclosures vs actual egress (traced DTO-by-DTO; OCR re-encode even
strips EXIF/GPS beyond the stated promise); onboarding/sample-clear; drag fallbacks + tier-editor
guards; OCR failure-state taxonomy; dark theme (no hardcoded colors outside the deliberate
photo-viewer chrome); ru copy register (no ты/вы mixing, plurals via `<plurals>`, destructive
buttons name their consequence).

## Carried over from rounds 1–2 — still OPEN, tracked in `OLD-REVIEWS.md`, not re-litigated

`UX-P1-3` (process-death draft loss — **re-confirmed open this round**, see doc-hygiene note),
`UX-P1-8` (My Teas join perf), `UX-P2-12` (unserialized drag writes), `UX-P2-14` (Undo loads full
tables), `UX-P2-15` (diagnostics delivery visibility), `UX-F-5` (re-openable tier help), `UX-F-6`
(brewing log — owner scoping), `UX2-F-3` (distinct `nameEn` in rows), `UX2-F-4` (OSS license
disclosure), `UX2-P2-3` (FlavorRadar label overlap), `UX2-P2-8` (`error_generic` ~16 sites),
`UX2-P2-9`/`UX2-P2-11` (UNRESOLVED cause + LLM budget wire-contract), `UX2-P2-21` (reference-detail
retry), `UX2-P2-22` (reconcile DB-heal half), `UX2-P2-25`/`UX2-P2-26` (VERIFY items).

## Suggested batching

1. **PR "kill the silent merge" (P0)** — UX3-P0-1: always-create for manual adds + non-blocking
   duplicate prompt (v7 finding #17 as designed), extend forceNew to non-catalog teas, surface
   reuse when chosen; regression tests for the match path.
2. **PR "enrichment you can see and drive"** — UX3-P1-2 (retry gate + copy), UX3-P1-4
   (status+retry on Detail/My Teas), UX3-P1-3 (resolve-timeout interceptor entry), UX3-P2-15
   (persist sourceText for retry), UX3-P2-16/17 (resume at app start + post-import).
3. **PR "scan session hygiene"** — UX3-P1-5 + UX3-P1-6 (tracked Job: reset on bind, cancel while
   Recognizing), UX3-P2-21 (progress hint), UX3-P2-10 (temp-file cleanup).
4. **PR "orphans & board flows"** — UX3-P1-1 (explicit "add to board" on Detail/My Teas),
   UX3-P2-8 (carry the picked tea / show the hint), UX3-P2-9 (dialog copy), UX3-P2-29 (delete
   feedback parity).
5. **PR "a11y round 3"** — UX3-P1-7 (named+stateful color swatches), UX3-P2-23/24/25/26.
6. **PR "loading-state parity + polish"** — UX3-P2-1..5, P2-6 (error≠empty), P2-11, P2-14, P2-18,
   P2-19, P2-27, P2-28.
7. **PR "tests & docs"** — UX3-P2-12, UX3-P2-13, the AddTeaScreen comment fix.
8. **Owner decisions** — UX3-F-1..F-8 scoping (stats, price, status, journal-lite, board
   copy/archive, text share, bulk ops, compare), UX3-P2-20 (scrub vs soften), UX3-P2-22 timing
   (with REL-P0-2).
