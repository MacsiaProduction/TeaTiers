# 2026-07-10 — Usage-quality audit round 4 (R4-)

Round 4 of the usage-quality/UX audit, at `66eafad` (immediately after round 3 landed as PRs
#207–#216 and was condensed into `OLD-REVIEWS.md`). Six parallel read-only passes, each seeded with
the full known-open/tracked list so they hunt only NEW findings: (1) regression review of the
round-3 fix batches themselves, (2) cold-start first-week journey, (3) 6-month power-user at scale
(150+ teas, 300+ photos), (4) feature gaps vs real tea-collector expectations round 2, (5) visual &
interaction design quality, (6) localization/input/edge-content. Every P1 candidate plus three
borderline calls were then **adversarially verified by independent skeptic passes** (6 verifiers
instructed to refute): 1 P1 confirmed (and strengthened), 2 P1s downgraded to P2 with corrected fix
directions, 3 P2s confirmed, and several sub-claims killed (sourceText caps actually agree; the
"spinning cards" backlog framing was false; `Eagerly` on the boards flow is a documented decision
#34/#39, not an oversight).

**Verdict:** the app is in genuinely good interaction shape — three audit rounds have drained the
bug backlog, and this round's passes returned long "verified clean" lists (theme/design system is
coherent and deliberate, Coil handles image decode correctly, drag interactions are well-crafted,
localization posture is formally locked ru-only per decisions #12/#94, plurals/collation/trimming
all clean). What's left is different in kind: **one storage-growth P1 nobody ever decided on**
(photos stored byte-for-byte at original size, invisible to the user, ballooning backups toward the
GB range), **one cheap schema gap that blocks a whole feature class** (no `createdAt` anywhere —
silently dropped in the v7 split, it forecloses "recently added", date sort, and the stats feature
UX3-F-1 waits on), and a tail of consistency P2s where one screen missed a pattern its siblings
already have. The biggest real-user lever remains the round-3 feature-gap list (UX3-F-1..F-8, all
still owner-DECIDE) — round 4 adds a second tier of gaps but found nothing that outranks stats,
price tracking, and consumption status.

Status legend: `OPEN` · `DONE` · `REVIEWED` (deliberate no-change) · `DECIDE` (owner call needed) ·
`TRACKED` (already tracked under another ID).

## P1 — the one confirmed heavyweight

| ID | Finding | Where | Status |
|---|---|:--:|:--:|
| R4-PWR-3 | **Tea photos are stored byte-for-byte at original size, invisibly and unboundedly.** `copyIn()` enforces only the 8 MB ceiling then raw-copies into permanent app storage; camera capture requests full sensor resolution; backup zips carry the same bytes (DEFLATE won't shrink JPEG). 300 photos ⇒ realistically 600 MB–2.4 GB of app-private storage the user cannot see (no storage line anywhere in Settings) and a share-backup file with no size forewarning. Skeptic-confirmed: **no "keep originals" decision exists** (decision #43 is about storage *location* only; #97 explicitly notes "export (own data) was left unbounded"), and the codebase already ships the exact EXIF-safe downsample+re-encode needed (`ImageReader.kt` — used only for OCR upload). Bonus gap found in verification: a >8 MB camera capture is **silently rejected** by the cap instead of resized. No stored photo is ever re-read for OCR, so downsampling loses nothing. | `data/photos/AndroidPhotoStore.kt:21,46-90`, `ui/board/PhotoStrip.kt:170-216`, `data/backup/BackupArchive.kt:48-59`, working reference: `data/photos/ImageReader.kt:35-104` | OPEN |

Fix: route `copyIn()` (and the restore-side `importInto()` for new photos) through the
`ImageReader`-style orient-scale-re-encode step (long edge ~2000–2400 px, JPEG ~85); resize instead
of rejecting oversized captures; add a "storage used" line (photo count + approximate size) to
Settings. Existing photos migrate lazily or not at all (owner call — a one-shot recompress pass is
cheap but touches user data).

## The schema quick win — unlocks a feature class

| ID | Finding | Where | Status |
|---|---|:--:|:--:|
| R4-F-1 | **No `createdAt` timestamp on teas or boards** — original plan.md schema had it; silently dropped in the v7 split (drift, not decision). Blocks "recently added" sort (asked for since round 1, UX-F-2 shipped scoped-down for exactly this reason), any activity/growth view, and is a prerequisite for the stats feature (UX3-F-1). `PhotoEntity.createdAtEpochMs` is the in-repo precedent; `MyTeasSortOption` is the UI to extend. Additive migration, nullable column, stamped at insert. | `data/db/Entities.kt:22-27,82-100`, `viewmodel/MyTeasModels.kt:12-20`, precedent `Entities.kt:247` | OPEN |

(The power-user pass found this independently as R4-PWR-4; merged here.)

## P2 — confirmed by skeptic passes (verified, corrected fix directions)

| ID | Finding | Where | Status |
|---|---|:--:|:--:|
| R4-PWR-1 | `resumePending()` processes the enrichment backlog strictly sequentially — each tea can hold the queue for its full ~112s poll budget. Downgraded from P1: only one card spins at a time (others show honest "queued" copy) and the round-3 per-tea retry already bypasses the queue. **Corrected fix:** fan out with an explicit concurrency cap (semaphore ~3–5) — NOT bare per-tea `launch`; a backup restore can hand `resumePending` dozens of teas and uncapped fan-out would trip the 20/min `/resolve` limiter. | `data/repository/TeaEnrichmentManager.kt:97-111,153-191` | OPEN |
| R4-REG-2 | After 3 consecutive Room read failures the `boards` flow terminally completes at `emptyList()`; the new #212 spinner then spins **forever** on BoardScreen (home degrades to an actionable empty state; the per-board screen just lies "loading"). Back-nav still escapes. Fix: sealed `Loading/NotFound/Loaded` mirroring the existing `TeaDetailUiState` pattern, keyed off the already-existing `boardsLoaded`. | `data/repository/TeaBoardRepository.kt:162-186`, `ui/board/BoardScreen.kt:233-242`, pattern `viewmodel/TeaDetailViewModel.kt:30-33` | OPEN |
| R4-LOC-1 | Name fields have no client-side length cap while the server rejects >200 chars on `/resolve` — an overlong pasted name saves locally but leaves enrichment permanently FAILED with a retry that re-sends the same string forever. Recoverable (shorten the name, retry) but nothing ever hints at the cause. Fix: `.take(200)` guard on the four name fields via a shared constant matching `ResolveRequestDto`, same pattern as the existing `SourceTextMaxLength`. (Verifier confirmed sourceText caps already agree client↔server — that half refuted.) | `ui/board/AddTeaScreen.kt:312-479`, `server/.../dto/ResolveDtos.kt:14,22`, `data/repository/TeaEnrichmentManager.kt:143` | OPEN |
| R4-JRN-2 | Browse Catalog — the dedicated exploration screen — offers no preview at all: tapping a row immediately opens the board picker. The Info-button → `CatalogDetailSheet` pattern exists one screen over and `CatalogDetailUiState` is already a shared type; the fix is pure wiring (~20 VM lines + one IconButton). P1 upgrade rejected by the skeptic (1-tap cancel, core task never blocked). | `ui/board/BrowseCatalogScreen.kt:242-283`, `viewmodel/BrowseCatalogViewModel.kt`, reference `ui/board/AddTeaScreen.kt:299,728-729,1009`, `viewmodel/CatalogSearchModels.kt:34-55` | OPEN |

## P2 — REVIEWED (deliberate design confirmed; accept + document)

- R4-PWR-2 — the app-scope `Eagerly` boards StateFlow re-materializes every board's full nested
  tree on any write to 7 tables. Skeptic verdict: real but bounded background-CPU inefficiency,
  **never touches the main thread**, recomposition is double-gated (lifecycle + data-class
  structural equality), the hot flow is a three-times-documented design (#34/#39 + repo doc
  comment) that dozens of synchronous `boards.value` readers depend on, and `WhileSubscribed`
  would break both those readers and the #212 retry invariant. Action: none; add one sentence to
  the repo doc comment noting the accepted recompute cost. `data/repository/TeaBoardRepository.kt:104-107,162-186`. REVIEWED

## P2 — polish & consistency (from finder passes, not skeptic-verified individually)

Round-3 fix-batch residuals (batches otherwise verified regression-clean):
- R4-REG-1 — the #214 IME-Next sweep missed the purchase-editor fields (`field_purchase_label`,
  `field_purchase_url`) in the same form; keyboard dismisses per field, repeats per purchase row.
  `ui/board/AddTeaScreen.kt:763,804,818`. OPEN

First-session journey:
- R4-JRN-1 — icon-only top-bar nav with an overloaded glyph: `List` means "catalog" on Boards and
  "sort" on My Teas; `Search` means "My Teas". No tooltips anywhere (`TooltipBox` unused). Fix:
  distinct icons or `PlainTooltip` wrappers. `ui/board/BoardsScreen.kt:99-110`,
  `ui/board/MyTeasScreen.kt:101-109`. OPEN
- R4-JRN-3 — a catalog pick marks the add form dirty without any user keystroke: backing out
  immediately warns "Введённые данные не сохранятся" about data the user never entered. Fix: sync
  `pristineForm = _form.value` after auto-fill. `viewmodel/AddTeaViewModel.kt:89,242-243,423-453`. OPEN

Input & edge content:
- R4-LOC-2 — no `autoCorrectEnabled = false` / capitalization tuning on any proper-noun field
  (worst for `pinyin`, easily "corrected" into English words). `ui/board/AddTeaScreen.kt` keyboard
  options sweep. OPEN
- R4-LOC-3 — TeaDetail's body header renders `displayName` with no `maxLines` — the one name site
  in the app without ellipsis (its own top bar caps at 1). `ui/board/TeaDetailScreen.kt:233-253`,
  optionally `CatalogDetailSheet.kt:188`. OPEN
- R4-LOC-4 — tier-swatch `label.take(2)` splits surrogate pairs/emoji mid-sequence (UTF-16 units,
  not graphemes) — a flag-emoji tier label renders as a broken glyph. `ui/board/TierEditorScreen.kt:241`. OPEN

Visual & interaction (design system itself verified coherent and deliberate):
- R4-VIS-1 — zero animated transitions between destinations: the manual back stack hard-cuts the
  whole tree while every in-screen interaction is spring-animated. Cheapest: `AnimatedContent`
  around the `when`. `ui/TeaTiersApp.kt:36-111`. OPEN
- R4-VIS-2 — no max-width constraint on any screen: tablet/foldable landscape stretches cards
  edge-to-edge. One `widthIn(max = 640.dp)` wrapper pattern. OPEN
- R4-VIS-3 — Save on the longest form in the app lives only in the top-bar corner; every other
  commit surface uses a FAB or full-width bottom button. Add a bottom Save mirroring
  `CatalogDetailSheet`. `ui/board/AddTeaScreen.kt:204-235`. OPEN
- R4-VIS-4 — photo drag has no haptic pickup cue while the board drag (same gesture, documented as
  the pattern source) does. One line. `ui/board/PhotoStrip.kt:256-269` vs `BoardScreen.kt:677`. OPEN
- R4-VIS-5 — no pull-to-refresh on the one remote-fed list (Browse Catalog); only exit/re-enter
  refreshes page one. `ui/board/BrowseCatalogScreen.kt:197-240`. OPEN
- R4-VIS-6 — full-screen photo-zoom dialog has zero inset handling (close button can sit under the
  status bar/cutout); the sibling `ModalBottomSheet` handles the same problem explicitly.
  `ui/board/PhotoGallery.kt:146-190`. OPEN

## Feature gaps round 2 (verified absent + prior-consideration-checked; owner DECIDE)

Ranked by demand for this app's audience. R4-F-1 (createdAt) promoted to its own section above.

| ID | Feature | Demand | Schema | Status |
|---|---|:--:|:--:|:--:|
| R4-F-2 | Search within a board (the two existing search boxes are My Teas + Catalog; boards with 200 cards have none) — client-side filter, copy the My Teas pattern | med-high | none | DECIDE |
| R4-F-3 | Filter by flavor profile ("sweet ≥3, astringent ≤1") — 11 scored axes are persisted but unfilterable everywhere | med | none | DECIDE |
| R4-F-4 | CSV export of the collection (backup zip is restore-only, not portability) — flattened writer beside the existing share flow | med | none | DECIDE |
| R4-F-5 | Tags / free-form labels beyond the fixed schema — `tea_tags` child table + chip input | med | new table | DECIDE |
| R4-F-6 | Remaining-quantity field (cake weight/grams left, low-stock) — distinct axis from UX3-F-2 price; guard against UX3-F-2 being scoped as price-only | med | new column | DECIDE |
| R4-F-7 | Share-target import: share a marketplace URL into the app → pre-filled tea draft (`PurchaseLocation.Marketplace` is the exact receiving slot; manifest has no ACTION_SEND filter) | med | none | DECIDE |
| R4-F-8 | Puerh/aged-tea fields: storage location, pressing-vs-purchase year (cake-weight half folds into R4-F-6) | low-med | new columns | DECIDE |
| R4-F-9 | Boards list reorder/sort — `BoardEntity.position` exists but is append-only; the boards list is the app's only fixed-order list | low-med | none | DECIDE |
| R4-F-10 | Static app shortcuts (Add tea / Scan / My Teas) — effectively free, `minSdk 26` | low | none | DECIDE |

Checked and rejected as not credible for this audience (one line each, so they aren't re-proposed):
home-screen widget (cost ≫ demand for a solo local-first app), Wear OS, Quick Settings tile,
theming choices (already exist: ThemeMode + dynamic color), Browse sort controls (relevance order
is correct), "what to drink now" recommender (the tier list is the answer), steep counters/brewing
params (inside UX-F-6 owner scope), rating-history (inside UX3-F-1/F-4 scope).

## Verified clean this round (don't re-audit soon)

Round-3 batches #207–#216 (deep regression pass: no stale callers, tests consistent, a11y sweep
holds, no dead strings — full per-batch verdicts in the pass output); theme/design system identity
(custom palette/typography/shapes, correctly-gated dynamic color, consistent spacing scale);
Coil-based photo decode (no hand-rolled bitmap paths on card/gallery); board drag interaction
(haptics, springs, TalkBack fallbacks); dialog-vs-sheet conventions; ripple consistency; adaptive
app icon; rotation safety; localization posture (ru-only is locked #12/#94, en/zh timing tracked
as #70.5); no hardcoded UI literals; zh font fallback documented; ru Collator sort; plurals;
whitespace trimming; keyboard types on year/URL fields; backup streaming (no whole-corpus memory
spikes); browse pagination; fuzzy-search debounce; photo-store orphan reconcile.

## Carried over — still OPEN, tracked elsewhere, not re-litigated

Rounds 1–3 in `OLD-REVIEWS.md`: UX-P1-3 (process-death draft loss), UX-P1-8 (My Teas join),
UX-P2-12/14/15, UX-F-5/F-6, UX2-F-3/F-4, UX2-P2-3/8/9/11/21/22/25/26, UX3-P2-2 (LazyRow),
UX3-P2-15 (sourceText on retry), UX3-P2-20 (DECIDE), UX3-P2-27 (FlavorRadar height), UX3-P2-22
(fold into REL-P0-2), and the whole UX3-F-1..F-8 feature slate — **which remains the single
biggest user-value decision in front of the owner**; round 4 found nothing that outranks
stats/price/consumption-status. Architecture-track items live in `INDEX.md` (REL-P0-2 full path,
AND-P1-3/4/5, OPS/PRIV items).

## Suggested batching

1. **PR "photo pipeline"** (the P1) — R4-PWR-3: downsample+re-encode in `copyIn` reusing
   `ImageReader`'s orient/scale/compress, resize-not-reject oversized captures, storage-used line
   in Settings. Owner decides whether to lazily recompress existing photos.
2. **PR "createdAt"** — R4-F-1: additive migration (`tea_samples`/`boards` `createdAtEpochMs`),
   stamp on insert, "recently added" sort option in My Teas. Unblocks UX3-F-1 stats later.
3. **PR "robustness trio"** — R4-REG-2 (sealed board UI state), R4-PWR-1 (capped-concurrency
   resume, semaphore ~3), R4-LOC-1 (shared 200-char name cap).
4. **PR "browse preview + journey polish"** — R4-JRN-2 (Info → CatalogDetailSheet wiring),
   R4-JRN-1 (distinct icons/tooltips), R4-JRN-3 (pristineForm sync after auto-fill).
5. **PR "form & input polish"** — R4-REG-1 (purchase IME-Next), R4-LOC-2 (autocorrect off),
   R4-LOC-3 (header maxLines), R4-LOC-4 (grapheme-safe truncation).
6. **PR "visual polish"** — R4-VIS-1 (screen transitions), R4-VIS-3 (bottom Save), R4-VIS-4
   (photo-drag haptic), R4-VIS-6 (zoom-dialog insets); R4-VIS-2 (width cap) and R4-VIS-5
   (pull-to-refresh) at owner discretion.
7. **Docs** — R4-PWR-2 one-sentence doc note (REVIEWED).
8. **Owner decisions** — the UX3-F-1..F-8 slate (unchanged, highest value), then R4-F-2..F-10.
