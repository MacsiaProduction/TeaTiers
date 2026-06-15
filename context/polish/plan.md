# Plan — UX polish pass over B + A + C

After shipping B (#8 tier templates), A (#9 shared user-tea collection), and C (#10
multi-photo per tea) we're sitting on three feature stripes that work end-to-end but
read as "alpha" in motion, error handling, and empty states. This pass tightens
those rough edges in **one squashed PR** so the feature surface presents as a single
polished increment, not three back-to-back additions.

Locked answers from the user (2026-06-15):

- **Scope:** all four lanes (motion → errors → empties → a11y).
- **Branching:** single `polish` branch, all four lanes squashed into one PR.
- **Strings:** add **only** the strings the polish work directly demands. No
  speculative copy.

## Lane 1 — Motion (highest visible payoff, no architectural risk)

Goal: drag/drop and list mutations feel intentional, not jump-cut. Keep the
existing hand-rolled drag pattern (`BoardDragState`, `PhotoStripDragState`)
intact — we add **read-side animations** only. No state-flow rewrites.

- **Tea-card drag (board):** the floating ghost in `BoardScreen.BoardContent`
  currently snaps to the finger via `IntOffset(o.x.roundToInt(), o.y.roundToInt())`.
  Add `animateFloatAsState(targetValue = scaleWhenDragging)` for the lift
  (`scale 1.0 → 1.06` on `dragState.dragged != null`) and an `animateDpAsState`
  for `tonalElevation`. Keep the offset itself unanimated — it must follow the
  finger 1:1, but the lift transition can be spring-driven.
- **Sibling shifts (board):** when a placement is moved between tiers, the
  destination row's existing children re-flow with no transition. Wrap each
  `DraggableTeaCard` inside a `Modifier.animateItemPlacement()` analogue
  (LazyRow doesn't apply here — these are plain `Row`s with `horizontalScroll`,
  so wrap the content in `AnimatedContent` or, simpler, give each card an
  `animateContentSize()` and rely on LazyColumn's tier rows handling vertical.).
  Decision: use `animateContentSize()` on the per-tier `Row` that holds the
  cards — cheaper than per-card placement animations, and the visual shift the
  user perceives is "the tier row breathes when something lands in it".
- **Hover highlight pulse:** `dragState.hoverKey` already drives a
  `drawRoundRect` highlight. Wrap its alpha in `animateFloatAsState` (50ms
  spring) so the highlight fades in/out instead of flicker-toggling.
- **Photo-strip drag:** `PhotoStripDragState.dragOffsetXPx` reads as a raw
  `Float` via `graphicsLayer.translationX`. When the drag ends without a swap
  the thumb teleports back. Add a 120 ms `animateFloatAsState(target = 0f)` on
  `dragOffsetXPx` when `draggedId == null` so the release glides instead of
  snaps.
- **Photo-strip add/remove:** wrap each `PhotoThumbnail` in
  `AnimatedVisibility` with `fadeIn() + scaleIn(initialScale = 0.92f)` and
  matching `fadeOut() + scaleOut()`. Removal currently does the AlertDialog
  confirm → repository delete → recompose with the row gone (instant
  disappearance). The animation runs over the recompose so the user sees the
  thumb shrink out.
- **Pager dot indicator:** `PhotoGallery` renders dots as
  `if (on) primary else outlineVariant`. Replace with `animateColorAsState`
  on the dot color and `animateDpAsState` on the dot width
  (`8.dp → 18.dp` for the active dot) so the indicator slides instead of
  step-toggling. No new strings; pure visual.
- **Zoom dialog:** keep `Dialog` but inside the dialog body wrap the pager in
  `AnimatedVisibility(visible = true)` with `fadeIn() + scaleIn(0.96f)` driven
  by a `LaunchedEffect(Unit) { visible.value = true }`. Exit is governed by the
  Dialog dismiss path, which already plays Android's default exit transition —
  acceptable.
- **Tier editor row in/out:** `TierEditorScreen` lists tiers in a `LazyColumn`
  with `itemsIndexed`. Add `Modifier.animateItemPlacement()` so reorder via
  the up/down arrows slides the row instead of jump-cutting. Add a tier flows
  with `AnimatedVisibility` enter/exit on the row card — but only for the
  **just-added** row (use a remembered `addedIds` set), so the editor doesn't
  replay the enter animation on every recomposition.

Risk: `animateItemPlacement` is a ColumnScope receiver and only available
inside `LazyListScope` lambdas — it's already in scope inside `itemsIndexed`,
so the wiring is local. No API changes.

## Lane 2 — Error surfaces

Goal: a failed copy / save / delete does not vanish silently. Each top-level
screen gets a single `SnackbarHost` and the responsible `ViewModel` exposes a
one-shot event channel.

- **Add a per-screen event channel.** New file
  `viewmodel/UiEvent.kt`:

  ```kotlin
  sealed interface UiEvent {
      data class ShowSnackbar(@StringRes val message: Int) : UiEvent
  }
  ```

  Each VM that owns mutating actions gains a
  `private val _events = Channel<UiEvent>(Channel.BUFFERED)` exposed as
  `val events: Flow<UiEvent> = _events.receiveAsFlow()`. The screen consumes
  it via a `LaunchedEffect(Unit) { vm.events.collect { ... } }`.
- **Wire failures.**
  - `TeaBoardRepository.addPhoto` returns null on copy failure (already does);
    `AddTeaViewModel.onAddPhoto` now sends `ShowSnackbar(R.string.error_photo_copy_failed)`
    when the result is null (in both add-mode draft branch and edit-mode
    repository branch).
  - `TeaBoardRepository.{addTea, updateTea, addPhoto, removePhoto, reorderPhotos,
    deleteTea, removePlacement, movePlacement}` are wrapped in a small
    `runCatching { … }` at the VM-call site (the repo doesn't change shape).
    On failure, send a generic `R.string.error_generic`.
- **Add a SnackbarHost to** `BoardScreen`, `TeaDetailScreen`, `AddTeaScreen`,
  `BoardsScreen`. Re-use the Scaffold's built-in slot.
- **Inline validation on add-tea.** `form.isValid` is currently boolean-only;
  the `Save` button is disabled when invalid (`nameRu.isBlank()`). Today this
  is silent — the user taps a non-disabled-looking action and nothing happens
  (the `TextButton` is `enabled = false`, but on touch it's hard to perceive).
  - Keep the disabled state, but add a focus-on-error: when the user taps Save
    and `nameRu.isBlank()`, push a `ShowSnackbar(R.string.add_tea_error_name_required)`
    and request focus on the nameRu field. Implementing focus-on-error needs a
    `FocusRequester` on the OutlinedTextField; small but necessary.
- **New strings (Russian-first):**
  - `error_photo_copy_failed` — "Не удалось добавить фото. Попробуйте ещё раз."
  - `error_generic` — "Что-то пошло не так. Изменения не сохранены."
  - `add_tea_error_name_required` — "Укажите название чая (русское поле обязательно)."

## Lane 3 — Empty states

Goal: a board with no teas, a tier with no teas, and a tea with no children
all look intentional. Today they read as "the screen is broken or unfinished".

- **Boards list:** already has `EmptyBoards` with a CTA. Done — just add a
  fade-in via `AnimatedVisibility` on the empty content so the state isn't a
  hard cut on first launch (motion lane crossover, no new copy).
- **Empty board (no placements at all):** `BoardScreen.BoardContent` currently
  shows a featured-tea null guard (no featured), then renders the tier rows
  and unranked section — both empty. The user sees a stack of "Пока пусто"
  rows. Replace this case with a centered welcome card: "Добавьте первый чай"
  + an inline tip pointing at the FAB ("Нажмите «Добавить чай» внизу"). Only
  shown when `state.tiers.all { it.placements.isEmpty() } && state.unranked.isEmpty()`.
- **Empty tier (single row):** keep the current "Пока пусто" caption but make
  it a real drop hint: "Перетащите чай сюда" — clearer to a first-time user
  than the generic placeholder.
- **Empty unranked (with non-empty tiers):** keep the row but hide the entire
  unranked section if it's empty AND there's at least one ranked tier. The
  user has fully ranked everything — celebrating the empty tray is noise.
- **Tea detail with no flavor / no notes / no photos:** the screen already
  hides the Flavor/Notes sections when empty (good). The Purchase section
  shows "Место покупки не указано" (good). No change here — the existing
  structure is correct.
- **New strings:**
  - `board_empty_title` — "В этой подборке пока нет чаёв"
  - `board_empty_hint` — "Нажмите «Добавить чай», чтобы начать раскладку."
  - `tier_empty_drop_hint` — "Перетащите чай сюда"
  - Keep existing `tier_empty` ("Пока пусто") for the unranked-empty case if
    we end up needing it. Drop otherwise.

## Lane 4 — A11y / hit targets

Goal: TalkBack stays useful and color contrast meets WCAG AA.

- **contentDescription audit.** Today's code already has good coverage on
  IconButtons (`a11y_back`, `a11y_card_menu`, `a11y_remove_photo`, etc.).
  Specific gaps:
  - `BoardSummaryCard` in `BoardsScreen`: the whole card is a `Surface(onClick)`
    with a name + tea count + signature swatches. Wrap the whole thing in
    `Modifier.semantics(mergeDescendants = true) { contentDescription = "..." }`
    so TalkBack reads the card as one unit instead of three nodes.
  - `LiquorSwatch` in the signature row: contentDescription is via the parent
    Surface — but each swatch in a row has its own implicit semantics. Mark
    them `.clearAndSetSemantics { }` so they don't add noise to the parent's
    label.
  - `PhotoThumbnail.semantics` already sets `a11y_photo_thumbnail`. Add
    `customActions = listOf(CustomAccessibilityAction(stringResource(R.string.a11y_remove_photo)) { onRemove(); true })`
    so TalkBack users have a non-tap path for removal (the "x" badge is a
    24dp tap target, on the small side for switch users).
- **Tier color contrast.** `BoardScreen.TierRow` already does
  `if (rampColor.luminance() > 0.55f) InkOnLight else Color.White`. The 0.55
  threshold passes WCAG AA for the default ramp, but doesn't account for
  user-picked tier colors from `TierEditorScreen` (12 presets). Add a unit
  test in `TeaCardContrastTest` (no new dep — pure JVM): assert each preset
  paired with the chosen on-color produces a contrast ratio ≥ 4.5:1. If any
  preset fails, swap its on-color choice or tweak its luminance threshold.
- **Drag handle on cards.** Long-press starts the drag while tap opens the
  detail (resolved through `detectDragGesturesAfterLongPress` running after
  the press timeout — gesture conflict is mostly avoided). Don't change the
  gesture; add a `Modifier.semantics { stateDescription = "Долгое нажатие — перетащить" }`
  on the card so TalkBack announces the secondary gesture explicitly.
- **Tap targets.** Audit IconButtons that are visually <48dp:
  - `PhotoThumbnail`'s "x" badge is 24dp visually but the IconButton
    auto-pads to 48dp under the hood — pass.
  - The `MoreVert` overflow on each card is a default IconButton — pass.
- **New strings:**
  - `a11y_board_summary` — "Подборка %1$s, %2$d %3$s" (name, count, plural). Wired
    via `pluralStringResource` for the count word.
  - Reuse existing `a11y_photo_drag_handle` on the photo thumbnail customAction
    label.

## Branching, verification, sequence

1. Cut branch `polish` from `main` (post-#10 squash). One commit per lane to
   keep diffs reviewable locally; final PR uses GitHub's squash-merge so `main`
   gets one commit.
2. Lane order: motion → errors → empties → a11y. Each lane builds on the prior
   without rewriting it.
3. After each lane: `cd app && ./gradlew testDebugUnitTest` runs the existing
   suite (no test breakage allowed). New tests only where the lane introduces
   new pure logic — primarily Lane 4 (`TeaCardContrastTest`).
4. Final verify before PR: `cd app && ./gradlew check assembleDebug`. The
   detekt / android-lint task runs as part of `check` and should stay green.
5. Open PR `polish: motion + error + empty + a11y pass`. Reviewer notes
   include: no schema change, no migration, no public API surface diff on the
   repository / VM layer (only additive `events: Flow<UiEvent>`).

## Out of scope (carry-overs)

- **D — AI web search for descriptions / flavor profile.** Lane is unchanged
  — research first under `research/08-ai-web-search/`, no code in this PR.
- **M2 — backend catalog & external tea DB integration.** Out of this pass.
- **i18n English / Chinese resource bundles.** Strings stay ru-only.
- **Pager-as-shared-state in the zoom dialog.** Decision #43 explicitly chose
  separate pager state for the dialog; revisit only if user reports confusion.
- **Compose Material 3 motion specs / shared element transitions.** Future
  pass once `androidx.compose.animation:animation-graphics` stabilizes our
  current Compose BOM.

## Decisions delta

No new decisions in `context/decisions.md`. Polish does not change locked
architecture; it implements existing decisions more carefully. If Lane 4
discovers a tier color preset that fails WCAG AA, the swap is a code-level
fix — no decision needed.
