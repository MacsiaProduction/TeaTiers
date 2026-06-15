# Plan — C: photo list per tea (reopens decisions #24)

Brainstorm L6 (`context/brainstorming.md`): "the tea pages shouls include a photo list if we
have it (from db or search) with ability to add user photo." Locked decision #24 only specs
**one** user photo per tea + **one** optional CC catalog image and bans arbitrary web fetches
on licensing grounds. This plan reopens #24 to support a *list* of photos while keeping #24's
licensing line intact (no arbitrary web fetch).

Locked answers from the user (2026-06-15):

- **Scope:** many user photos per tea in MVP, no catalog/web yet; schema is shaped so M4
  can plug in catalog/CC photos without another migration.
- **Storage:** copy bytes into app-private storage (`filesDir/tea_photos/<uuid>.jpg`), DB
  stores the absolute path. Fragile `content://` URIs from the picker are a non-starter for a
  local-first export-import app (#1, #26).
- **Image lib:** Coil 3.5.0 (`io.coil-kt.coil3:coil-compose`), local-only `ImageLoader`
  (no network artefact), exact Kotlin-2.4.0 match.
- **Reorder:** long-press drag-to-reorder ships in this PR — mirrors the tier-list drag
  pattern (#38, hand-rolled, no dep) so users can promote a "hero" shot.
- **Viewer:** detail screen has a horizontal pager + pinch-zoom dialog (Compose Foundation
  pager + transformable).

## Domain & schema

- **`TeaPhoto(id, uri, position, source: PhotoSource, license?, sourceUrl?)`** — id is a stable
  UUID-like string; `uri` is an absolute file path (`/data/data/.../files/tea_photos/<uuid>.jpg`)
  for `USER`, or an external URL/`content://` for a future `CATALOG` photo. `position` is a
  per-tea contiguous int (0..n).
- **`PhotoSource` enum: `USER`, `CATALOG`.** Only `USER` is wired in MVP; `CATALOG` is a future
  hook (M4) so the table stops growing schema later.
- **`Tea.photos: List<TeaPhoto>`** — sorted by `position` on read; default empty.
- **Schema (Room v3, destructive migration again — same rationale as #42's bump: pre-launch,
  only sample seed is durable; the next migration after we ship to a real user must be a real
  `Migration(2, 3)`):**
  - `tea_photos(id PK TEXT, teaId TEXT NOT NULL FK→teas.id ON DELETE CASCADE, uri TEXT NOT
    NULL, position INTEGER NOT NULL, source TEXT NOT NULL, license TEXT, sourceUrl TEXT,
    createdAtEpochMs INTEGER NOT NULL)`
  - Index on `teaId` for the per-tea query (Room: `indices = [Index("teaId")]`).
  - No FK to `tiers`/`boards` — photos are user-tea-scoped (consistent with #42: notes,
    flavor, purchases also live on user-tea, not placement).

`exportSchema = false` stays for now (#34). When the schema first changes against a shipped
build, flip on `room.schemaLocation` and start writing real `Migration` instances.

## Storage layer

- **`PhotoStore` interface** (`data/photos/PhotoStore.kt`): `suspend fun copyIn(source: Uri):
  String` returns the absolute path; `suspend fun delete(path: String): Boolean` removes the
  file (best-effort; missing file is treated as success). Pure-Kotlin contract so the test
  fake can be a `Map<String, ByteArray>`.
- **Android impl** (`AndroidPhotoStore`, Hilt-bound `@Singleton`): copies bytes via
  `ContentResolver.openInputStream` into `context.filesDir.resolve("tea_photos")`, generates a
  UUID-named jpg/png based on the source MIME, and `fsync`s. Errors surface as
  `IOException` and the repo treats them as a no-op (no DB row written).
- **Cleanup:** `deleteTea` already cascades the row via FK; the repo also enumerates the tea's
  photo paths *before* the DB delete and asks `PhotoStore.delete(...)` for each. `removePhoto`
  does the same for one row. Best-effort: a missing file does not fail the DB write.
- **Backup hook (#26):** the M-export feature (later milestone) bundles `tea_photos/`
  alongside the JSON manifest; document the dir name + manifest field, but do not implement
  export here.

## Repository surface

- `suspend fun addPhoto(teaId: String, source: Uri): String?` — copies bytes, inserts row at
  the next position, returns the new photo id (or null on copy failure).
- `suspend fun removePhoto(photoId: String)` — finds path, deletes row in a `@Transaction`,
  best-effort file delete. Renumbers the surviving photos so `position` stays contiguous.
- `suspend fun reorderPhotos(teaId: String, orderedIds: List<String>)` — pure
  `computePhotoPositions` + `dao.applyPhotoPositions` in one transaction (mirrors #38's
  `applyPlacements`). Pure logic + tests.
- `tea(teaId)` already returns the user-tea; the loader now also reads `photos` (sorted by
  position) and exposes them through the `Tea` domain. Rest of the surface (boards Flow,
  addTea, updateTea, etc.) is unchanged shape — the boards Flow keeps emitting because the
  Room relation includes the photos child.

## DAO + mappers + relations

- New `PhotoEntity` mirroring the schema; new `Photo`-shaped entry on `TeaWithChildren`
  (`@Relation(parentColumn = "id", entityColumn = "teaId")`). `loadTea(teaId)` and the boards
  query both eagerly include photos.
- New DAO methods: `loadPhotos(teaId)`, `nextPhotoPosition(teaId)`, `insertPhoto`,
  `deletePhoto`, `applyPhotoPositions(rows: List<PhotoPositionRow>)`.
- `Tea.toEntities` returns `(tea, flavors, purchases, photos)`; `SeedEntities` grows a
  `photos: List<PhotoEntity>` slot. Sample seed teas keep `photos = emptyList()` — no
  bundled jpg blobs, no licensing friction.
- `BoardWithChildren.toDomain` and `PlacementWithTea.toDomain` thread the photos list into
  the in-memory `Tea`.

## ViewModel additions

- `AddTeaViewModel`:
  - `photos: StateFlow<List<TeaPhoto>>` mirrored from the bound tea (empty for add-mode).
  - `onAddPhoto(uri: Uri)`, `onRemovePhoto(photoId)`, `onReorderPhotos(orderedIds)` —
    each delegates to the repository. **Edit mode only** (add mode stages photos in a draft
    list and only commits after the tea row exists).
  - **Add-mode draft:** photos picked before save go into `_draftPhotos`. `submit()` first
    creates the tea + placement, then loops `addPhoto(newTeaId, uri)` for each draft entry
    in the order they were picked. Failure of one photo logs and continues; the tea is still
    saved (consistent with #21's optimistic posture).
- `TeaDetailViewModel`: `photos` derived from `tea.photos` (no new state flow needed).

## UI

- **Edit screen (`AddTeaScreen` edit mode + add mode):**
  - New `PhotoStripField` Compose: a horizontally scrollable strip of square thumbnails plus
    a final "Add" tile. Long-press a thumbnail to start a hand-rolled drag-reorder (small
    `PhotoStripDragState`, mirrors `BoardDragState`'s drop-by-center-x logic, swap on
    drag). Tap the "x" badge on a thumbnail to remove (with a tiny confirm via undoable
    snackbar — same simple-confirmation pattern as drag-to-rank, consistent with
    minimalism).
  - Picker: `rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia)` —
    no manifest permissions on any supported API (the contract auto-falls-back to
    `OPEN_DOCUMENT` on pre-API 33 / no-GMS devices, which also needs no permission).
- **Detail screen (`TeaDetailScreen`):**
  - Replace the single hero swatch with a `PhotoGallery`: `HorizontalPager` of
    `AsyncImage`s (Coil) with thumbnail dots; tap any photo to open `PhotoZoomDialog` —
    full-screen `Box` with a `Modifier.transformable` pinch/pan/double-tap-to-reset, swipe
    left/right within the dialog by re-using the pager. Falls back to the type swatch when
    the tea has zero photos (preserves the current Настой look and stays license-clean).
- **Card thumbnail (`BoardScreen.DraggableTeaCard`):**
  - When `tea.photos.isNotEmpty()`, render the first photo as a small rounded thumbnail to
    the left of the name; otherwise the type swatch (current behaviour). Photo file → Coil
    `AsyncImage` with `crossfade` + the type swatch as the placeholder/error.

## Image-loading (Coil 3.5.0)

- Pin `io.coil-kt.coil3:coil-compose:3.5.0` only — **no `coil-network-*` artefact**, MVP only
  loads local files. Singleton `ImageLoader` is fine; configure with `crossfade(true)` and a
  small `MemoryCache` (Coil default OK). Provide via Hilt as `@Singleton ImageLoader` so a
  test can swap it for a fake painter.
- `coil-compose 3.5.0` requires JDK 11 bytecode. `app/` already builds with `jvmToolchain(17)`,
  so this is satisfied without further wiring.

## Reorder math (pure, tested)

- `computePhotoPositions(orderedIds: List<String>, current: List<TeaPhoto>): List<PhotoPosition>` —
  drops unknown ids, renumbers 0..n, no-ops when unchanged. Direct copy of the
  `computeTierPositions` shape from #39, so the pattern is familiar.
- `computePhotoDrop(state, dragId, drop)` — picks the insertion index by the count of *other*
  photos whose center-x lies left of the pointer; same idea as #38 with `placementId` swapped
  for `photoId`.

## Tests (pure-JVM)

- `PhotoStoreFake` — in-memory `Map<String, ByteArray>` with deterministic uuids.
- `Mappers` — round-trip a `Tea` with two photos through `toEntities` / `toSeedEntities` and
  back through `toDomain`; assert order by `position`.
- `TeaBoardRepository` —
  - `addPhoto` copies bytes, inserts at the next position, repo emits the updated boards
    Flow with the photos lit on the placement card.
  - `removePhoto` deletes the row, calls `PhotoStore.delete`, renumbers remaining photos.
  - `reorderPhotos` writes contiguous positions; ignores unknown ids.
  - `deleteTea` removes every photo file (cascading FK is exercised by FakeTeaDao + the
    pre-delete file enumeration is asserted).
- `AddTeaViewModel` — draft-photos buffer in add mode, commits after tea creation, in-place
  add on edit. Add-photo failure path leaves the tea saved.
- `computePhotoPositions` + `computePhotoDrop` — small property-style cases (renumber, no-op,
  drop unknown, drop into front/middle/back).

## Strings (ru-first, #12)

- `a11y_photo_strip`, `a11y_photo_thumbnail`, `a11y_add_photo`, `a11y_remove_photo`,
  `a11y_photo_drag_handle`, `action_add_photo`, `action_remove_photo`,
  `confirm_remove_photo_title`, `confirm_remove_photo_message`, `photo_zoom_close`.

## Out of this PR

- Catalog/CC photos (M4, with the backend catalog).
- AI web-search photos — see `research/08-ai-web-search/`. If we ever flip the decision to
  fetch web images, licensing has to be re-solved first; for now #24's "no arbitrary web
  fetch" stands.
- Real `Migration(1, 2)` and `Migration(2, 3)` — destructive again; both become mandatory
  before a public release.
- Export/import (#26) — the photos directory is documented as part of the future bundle but
  not implemented.

## Decisions to write at the end

`context/decisions.md` addendum 16 / #43 supersedes #24's "single user photo" line:

- Photos are a list per user-tea; user-uploaded only for MVP.
- Bytes copied to `filesDir/tea_photos/<uuid>.jpg`, absolute path stored.
- Coil 3.5.0 used as the image-loading layer.
- Drag-reorder + pinch-zoom viewer ship in MVP.
- #24's "no arbitrary web fetch" line still stands.

## Sequence (tracked in TodoWrite as c2…c4)

1. Schema: `tea_photos` entity, relations, mappers, DAO methods. Bump DB v2→v3.
2. Domain: `TeaPhoto`, `PhotoSource`; thread `photos` through `Tea`.
3. `PhotoStore` interface + Android impl + Hilt module; fake for tests.
4. Repository: addPhoto/removePhoto/reorderPhotos + tea(teaId) photo wiring; pure helpers.
5. ViewModel additions + draft list for add mode.
6. UI: `PhotoStripField` (edit), `PhotoGallery` + `PhotoZoomDialog` (detail), card thumbnail.
7. Coil pin + Hilt-bound `ImageLoader`.
8. Strings.
9. Tests across all of the above.
10. `decisions.md` addendum 16 / #43.
11. Local verify (`./gradlew check assembleDebug`).
12. Branch `photos` → commit → PR → CI green → squash-merge.
