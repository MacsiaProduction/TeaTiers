# A — shared user-tea collection across boards

> Reopening of `decisions.md` #34, driven by `context/brainstorming.md`:
> *"teas collection is shared across all tier lists, tea may not be on each tier list,
> but user should be able to not declare same tea several times."*

## Status quo (what #34 locked, and why)

The current schema is **board-scoped**: each board owns its own copy of every tea it
contains. When a user adds Da Hong Pao to *Любимые чаи* and to *Утренние пуэры*, the DB
holds two rows in `teas`, with row ids `"favorites-da-hong-pao"` and
`"oolongs-da-hong-pao"`. The on-disk shape today (`app/.../data/db/Entities.kt`):

```
boards(id, name, position)
tiers(id, boardId, label, position, colorArgb)
teas(id, boardId, tierId?, position, nameRu, nameZh, pinyin, nameEn, type, origin,
     shortBlurb, notes)              -- one row per (board, tea) PAIR
tea_flavors(teaId, dimension, intensity, position)
purchase_locations(id, teaId, position, kind, label, value)
```

Why we did this in M1 (`decisions.md` #34):
- Fastest path to a working board UI; mirrors the in-memory aggregate the screens already consumed.
- `Tea.id` could stay opaque from the UI's perspective.
- A "shared catalog" (multiple users sharing canonical descriptions) was always planned
  for **M2/M4 with the backend**, not the local store.

Brainstorm point B already shipped and validated that the user wants to add boards
deliberately. Point A says: when the user adds the same tea to *another* board, they want
to **link**, not re-declare. That's the architectural change captured here.

## What we agreed up-front (from the AskQuestion round on 2026-06-15)

1. **Per-tea everything**: notes, user flavor, user photos (if/when we add them), and
   purchase locations all live on the user-tea, NOT per-placement. Same tea on N boards =
   same notes / flavor / photos / purchases on every board. (Justification: the brainstorm
   says "shared collection" and "don't declare twice" — splitting any of these per-board
   would re-introduce duplication, just at a finer grain. If the user wants a board-specific
   note later, we can add a per-placement comment box without changing the rest.)
2. **Auto-link on name match**: when the user types a name in the add flow and it matches
   an existing user-tea (case- and whitespace-insensitive on `nameRu`, plus a fuzzy hit on
   `nameZh` / `pinyin`), silently link the placement to the existing user-tea instead of
   creating a new row. No "use existing?" prompt. The user *should not be able* to declare
   the same tea twice via normal flow.

## Target schema

A new `placements` table replaces the `(boardId, tierId, position)` columns currently on
`teas`. The `teas` table loses those columns and becomes the user-global tea pool.

```
boards(id, name, position)               -- unchanged
tiers(id, boardId, label, position, colorArgb)   -- unchanged
teas(id, nameRu, nameZh, pinyin, nameEn, type, origin, shortBlurb, notes)
                                         -- one row per USER-TEA (was: per board+tea)
tea_flavors(teaId, dimension, intensity, position)
                                         -- unchanged shape; FK now points to the pool
purchase_locations(id, teaId, position, kind, label, value)
                                         -- unchanged shape; FK now points to the pool
placements(id, boardId, teaId, tierId?, position)
                                         -- NEW. The "this tea sits on this board in
                                         -- this tier at this slot" relation.
                                         -- UNIQUE(boardId, teaId): a tea can be on
                                         -- a board at most once.
```

Indices: `placements(boardId)`, `placements(tierId)`, `placements(teaId)`. FK cascades:
deleting a board cascades its placements; deleting a user-tea cascades its placements (and
its flavors / purchases as today). Deleting a tier still does NOT cascade — we already
reassign tier-orphan placements to the tray (`tierId = null`) in `removeTier` (#39).

### Why a `placements` table and not just `teas(boardId, tierId, position)` with
### `teas.id` reused as the "user tea" id?

Because the latter forces a separate logical "tea pool" view derived from the placement
rows (collapse by name? by some pool id?), and the dedup invariant ("never the same tea
twice") only holds via a UNIQUE constraint that depends on a stable user-tea identity.
Splitting placements out gives us:
- A real, queryable "my teas" pool (`SELECT * FROM teas`) — the M5 cross-board view
  becomes free.
- A clean unique constraint (`UNIQUE(boardId, teaId)`).
- One place to put per-board state (tier + position) so a tea's pool data never shifts
  when it's reordered.

## Domain-model impact

Today (`domain/model/Tea.kt`) a `Tea` carries everything: identity, names, type, flavor,
notes, purchases. After A, **a `Tea` is the user-tea pool entry**; per-board state moves
to a small placement type that carries the tea + tier id + position. The `Board` aggregate
the UI consumes already groups by tier; that stays the same shape, only its leaves are
"placement of a Tea" instead of "Tea" directly.

Concretely:

```kotlin
data class Tea(             // unchanged, but its identity is now user-global
    val id: String, val nameRu: String, val nameZh: String?, ...
    val flavor: List<FlavorScore>, val notes: String?,
    val purchaseLocations: List<PurchaseLocation>,
)

data class Placement(       // new
    val placementId: String, val tea: Tea, val position: Int,
)

data class Board(           // shape preserved: tiers + per-tier placements + tray
    val id: String, val name: String,
    val tiers: List<Tier>,
    val placements: Map<String, List<Placement>>,
    val unranked: List<Placement>,
)
```

The UI keeps consuming `Board.placements[tier.id]` and `Board.unranked` as today; nothing
about drag-to-rank or the tier editor changes shape. Drag-to-rank now operates on
`placementId` instead of `teaId`. The detail screen receives a `placementId` (or
`teaId` — see the open questions) and resolves the underlying `Tea` from the pool.

## Behaviour changes (per surface)

| Surface | Change |
|---|---|
| **Drag-to-rank (#38)** | `moveTea(boardId, placementId, targetTierId, targetIndex)`. The pure `computeMovePlacements` reorders placements within `(boardId, tierId)` groups; the algorithm is the same, the keys are different. |
| **Tier editor (#39)** | No surface change. `removeTier`'s tray reassignment now updates placement rows in place (their `tierId` becomes null) instead of rewriting tea rows. |
| **Add tea (#40 form)** | The flow gains a name-resolution step: on save, look up an existing user-tea by `(nameRu trimmed-lowercased)` first, fall back to `nameZh` / `pinyin`. If hit → create a new placement linking to the existing tea (no duplicate row). If miss → create both the user-tea and a placement. The form's tier picker still chooses initial placement; everything else writes to the user-tea. |
| **Edit tea (#40)** | Edits write the user-tea, so the change ripples to every board that has it. **This is a behaviour change worth surfacing**: editing notes on board A also changes notes on board B. Acceptable given the "everything per-tea" decision, but the screen needs a subtle hint ("Изменения видны во всех подборках" or similar) so the user knows. |
| **Delete from board** | New action: "Убрать из этой подборки" deletes only the placement (the tea stays in the pool). A separate path "Удалить чай навсегда" deletes the user-tea + cascades all placements. Today there is no "remove tea" UI at all (the only way teas leave a board is when a tier is deleted), so this is fresh ground. |
| **Cross-board "my teas" view (#27, M5)** | Becomes a flat `SELECT * FROM teas` instead of a deduplicated union over boards. Free win. |

## Migration

Local DB is currently `exportSchema = false` — no Flyway-equivalent on-device migration
plan yet. Two options:

1. **Wipe and reseed on the next install.** Bumping the Room DB version + setting
   `fallbackToDestructiveMigration()` drops the user's data on app upgrade. Acceptable
   since this is pre-launch with no real users (the only "users" are us); we have the
   sample data to repopulate.
2. **Write a real Room migration.** `Migration(1, 2)` SQL that:
   - Creates the new `placements` table.
   - Inserts one placement row per existing tea row using its `(boardId, tierId, position)`.
   - Collapses duplicate tea rows by `(nameRu lowercased + nameZh)` to a single user-tea
     row (keeping the *first* tea's `id` as the canonical id, repointing other duplicates'
     placements to it, and merging notes/flavors/purchases — last-write-wins is fine for
     a pre-launch product).
   - Drops `teas.boardId`, `teas.tierId`, `teas.position`.

   This is non-trivial SQL but doable; it preserves any test data on existing devices.

Recommendation: **option 1 (destructive)** for this turn, since we are pre-launch and the
sample data is the only non-trivial state on real devices. Option 2 becomes mandatory the
moment we ship to a real user. Document the choice in `decisions.md` so it's clear when
to upgrade.

## Repository / DAO surface

```kotlin
// TeaDao
@Insert abstract suspend fun insertPlacements(rows: List<PlacementEntity>)
@Query("UPDATE placements SET tierId = :tierId, position = :position WHERE id = :placementId")
abstract suspend fun updatePlacement(placementId: String, tierId: String?, position: Int)
@Query("DELETE FROM placements WHERE id = :placementId") abstract suspend fun deletePlacement(placementId: String)
@Query("SELECT id FROM teas WHERE LOWER(TRIM(nameRu)) = :keyRu LIMIT 1")
abstract suspend fun findUserTeaIdByNameRu(keyRu: String): String?
// addTea now resolves-or-creates the user-tea, then inserts a placement.
// updatePlacements (multi-row) replaces applyPlacements as the drag-to-rank write.
```

Repository:
```kotlin
suspend fun addTea(boardId: String, tea: Tea, tierId: String?): String     // returns placementId
suspend fun updateTea(teaId: String, tea: Tea)                              // user-tea wide write
suspend fun moveTea(boardId: String, placementId: String, targetTierId: String?, targetIndex: Int)
suspend fun removePlacement(placementId: String)                            // remove from one board
suspend fun deleteTea(teaId: String)                                        // remove tea + cascade
```

Note `updateTea` no longer takes `boardId` — it's user-tea-wide. Existing callers need an
audit.

## Test plan (pure JVM only, mirrors decisions #37)

- `MovePlacementTest` adapted to placements (signature change, same algorithm).
- `TeaBoardRepositoryTest`:
  - `addTea` with a name match links to the existing user-tea (one tea row, two placement
    rows, identical notes/flavor/purchases on both boards).
  - `addTea` with a name miss creates both rows.
  - `updateTea` ripples to all boards holding that tea.
  - `removePlacement` removes from one board, keeps the user-tea + the other placement.
  - `deleteTea` cascades and clears placements on every board.
  - `removeTier` still moves placements to the tray (existing test, adapted).
- `BoardViewModelTest` / `AddTeaViewModelTest` updated to the new signatures.
- `MigrationTest` — only if we go with option 2. With option 1 we can skip it.

## Effort

Best case (destructive migration, no per-placement comment): a focused day's work — schema
+ mappers + DAO + repo + VM + UI signature audit + tests. Worst case (option-2 migration,
per-placement comment for notes): closer to two days because of the migration SQL and the
extra UI surface.

## Open questions to confirm before coding

1. **Migration approach** — option 1 (destructive, fast) or option 2 (real Room migration,
   safer if we ship to anyone)? My take: option 1 is fine.
2. **"Remove from board" vs "Delete tea"** — do we want both UI paths in this turn, or just
   the per-placement remove (the more common flow)? My take: only "remove from this board"
   in A; delete-tea is a separate small follow-up because it overlaps with the M5 "my teas"
   screen.
3. **Edit-tea ripple notice** — show a subtle "changes are visible on all boards" line on
   the edit screen, or trust the user to figure it out? My take: a one-line caption on the
   edit screen avoids the inevitable "why did my notes for tea X on board Y change when I
   edited it on board Z" support thread.
4. **Detail / edit nav id: `placementId` or `teaId`?** Today `Destination.TeaDetail`
   carries `(boardId, teaId)` because the row id encodes both. After A:
   - **`teaId`** is cleaner (the detail screen renders the user-tea, not a placement).
   - **`placementId`** is what's already at hand on the board (drag-to-rank knows
     placements, not user-tea ids).
   My take: route by `teaId` (we already know `placementId.teaId` at click time;
   resolving is trivial); the URL stays semantically about "the tea", not "the placement".
5. **Name match key** — case-insensitive trim on `nameRu` is the obvious primary key, but
   should `nameZh` and `pinyin` also be matched, or only `nameRu`? My take: match on the
   first non-blank of `(nameRu trimmed lowercased, nameZh trimmed, pinyin trimmed
   lowercased)` against the same field on existing teas; the user's fix-up flow can
   intervene later if a real collision shows up. Lower priority than the others.

I'll send these as a single `AskQuestion` round after the user reads this doc; nothing
gets written until the answers are in.

## Out of scope for A

- The cross-board **"my teas"** screen (#27) — A makes it trivial, but we'll ship it as
  M5 separately. A only makes sure the data model supports it.
- Photo lists (brainstorm point C). C explicitly depends on A's schema; once A lands, C
  becomes "add a photos table referencing user-tea + a gallery composable".
- Backend-side shared catalog (M2/M4 #15/#16). The user-tea pool here is local-only; the
  server's canonical catalog is a separate concept and merges into the local pool only
  when the enrichment flow lands.
