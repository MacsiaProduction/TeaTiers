package com.macsia.teatiers.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

/**
 * One placement's new spot: its tier (null = the unranked tray) and its sort position. Used by
 * drag-to-rank and tier-removal transactions so callers can ship a batch of moves through one
 * @Transaction without N round-trips.
 */
data class PlacementMove(val placementId: String, val tierId: String?, val position: Int)

/** A tier's new order within its board, used when the tier list is reordered. */
data class TierPosition(val tierId: String, val position: Int)

/** A photo's new sort position within its tea, used when the photo strip is reordered. */
data class PhotoPosition(val photoId: String, val position: Int)

/**
 * The scalar columns [TeaDao.updateTea] shares with [TeaDao.applyEnrichmentPatch] (UX2-P0-1) — the
 * only columns where a plain edit-save and a background enrichment patch can race each other.
 */
data class TeaMergeFields(
    val nameRu: String?,
    val nameZh: String?,
    val pinyin: String?,
    val nameEn: String?,
    val type: String,
    val origin: String?,
)

/**
 * Slim projection of a [TeaSampleEntity] used for the resolve-or-create match (decisions.md #42).
 * The Russian-language match has to be Unicode-case-insensitive, and SQLite's built-in `LOWER` is
 * ASCII-only, so we load the candidate set into Kotlin and match there with [String.lowercase].
 * `nameRu` is nullable since v7 (P1-2 — a sample may have only a zh/en/pinyin name).
 */
data class TeaMatchKeyRow(
    val id: String,
    val nameRu: String?,
    val nameZh: String?,
    val pinyin: String?,
    val nameEn: String?,
)

/**
 * Abstract class (not interface) so the multi-table writes below can run inside a single
 * @Transaction default method.
 */
@Dao
abstract class TeaDao {

    @Transaction
    @Query("SELECT * FROM boards ORDER BY position")
    abstract fun observeBoards(): Flow<List<BoardWithChildren>>

    @Transaction
    @Query("SELECT * FROM tea_samples WHERE id = :teaId")
    abstract suspend fun loadTea(teaId: String): TeaWithChildren?

    /**
     * Reactive single-tea read (review 2026-06-19): re-emits whenever the sample or its children
     * change, so the edit screen's photo strip stays fresh even for a sample with zero board
     * placements (which never appears in the boards flow). Emits null if the sample is gone.
     */
    @Transaction
    @Query("SELECT * FROM tea_samples WHERE id = :teaId")
    abstract fun observeTea(teaId: String): Flow<TeaWithChildren?>

    /**
     * Every sample (the cross-board "my teas" collection, decisions.md #27), including samples
     * with no placement left on any board (removed from every board but not deleted — #42).
     * Ordering is cosmetic and applied in Kotlin so Russian uppercase sorts correctly.
     */
    @Transaction
    @Query("SELECT * FROM tea_samples")
    abstract fun observeAllTeas(): Flow<List<TeaWithChildren>>

    @Query("SELECT id, nameRu, nameZh, pinyin, nameEn FROM tea_samples")
    abstract suspend fun loadTeaMatchKeys(): List<TeaMatchKeyRow>

    /**
     * The first sample linked to this catalog id, if any — the strongest dedup key (#42). Since v7
     * dropped the UNIQUE, many samples may share a ref, so order by id for a stable pick (finding #7).
     */
    @Query("SELECT id FROM tea_samples WHERE catalogTeaId = :catalogTeaId ORDER BY id LIMIT 1")
    abstract suspend fun findTeaIdByCatalogId(catalogTeaId: Long): String?

    @Query("SELECT COUNT(*) FROM placements WHERE teaId = :teaId")
    abstract suspend fun placementCountForTea(teaId: String): Int

    @Query("SELECT COUNT(*) FROM boards")
    abstract suspend fun boardCount(): Int

    // Delete a whole board (tier-list). Its tiers + placements cascade via FK (onDelete=CASCADE);
    // shared samples are NOT board-scoped so they persist (only the board's arrangement is removed).
    @Query("DELETE FROM boards WHERE id = :boardId")
    abstract suspend fun deleteBoardRow(boardId: String)

    @Query("UPDATE boards SET name = :name WHERE id = :boardId")
    abstract suspend fun updateBoardName(boardId: String, name: String)

    // Out-of-Room wipe sentinel (decision #111): numeric-only user-data counts, no content.
    @Query("SELECT COUNT(*) FROM tea_samples")
    abstract suspend fun teaCount(): Int

    @Query("SELECT COUNT(*) FROM tea_photos")
    abstract suspend fun photoCount(): Int

    /** Per-board, position is global across tiers + the tray; sort within a (board, tier) group. */
    @Query("SELECT COALESCE(MAX(position), -1) + 1 FROM placements WHERE boardId = :boardId")
    abstract suspend fun nextPlacementPosition(boardId: String): Int

    /** Whether this board already holds the given tea — the UNIQUE(boardId, teaId) invariant guard. */
    @Query("SELECT EXISTS(SELECT 1 FROM placements WHERE boardId = :boardId AND teaId = :teaId)")
    abstract suspend fun placementExists(boardId: String, teaId: String): Boolean

    @Query("SELECT COALESCE(MAX(position), -1) + 1 FROM tiers WHERE boardId = :boardId")
    abstract suspend fun nextTierPosition(boardId: String): Int

    @Insert
    abstract suspend fun insertBoards(boards: List<BoardEntity>)

    @Insert
    abstract suspend fun insertTiers(tiers: List<TierEntity>)

    /** Catalog-ref rows. Must be inserted BEFORE the samples that link to them (FK). */
    @Insert
    abstract suspend fun insertRefs(refs: List<CatalogRefEntity>)

    /**
     * Idempotent stub for one catalog ref — the FK precondition for any `catalogTeaId` write
     * (finding #14). `INSERT OR IGNORE` so a ref already cached (with full facts) is not clobbered
     * back to a stub. fetchedAtEpochMs=0 marks "stub, never refreshed".
     */
    @Query("INSERT OR IGNORE INTO catalog_refs (id, type, fetchedAtEpochMs) VALUES (:refId, :type, 0)")
    abstract suspend fun insertRefStub(refId: Long, type: String)

    /**
     * Catalog-refresh writer (#132 / finding #6/#22): upserts the FULL cached facts of a catalog ref
     * (overwrites ref columns only — never touches any sample). Called when enrichment resolves a
     * sample to a ref, so the stub created at link time populates with real origin/brand/blurb/etc.
     *
     * [CatalogRefEntity.catalogPublicId] is the durable cross-rebuild key (#137-C2): lazily backfilled
     * and MONOTONIC — null -> UUID, never back. An older server omits it (null), and a naive full-row
     * upsert would wipe a UUID we'd already stamped, dropping the app back to the volatile Long id. So
     * when the incoming publicId is null we keep whatever the row already holds.
     */
    @Transaction
    open suspend fun upsertRef(ref: CatalogRefEntity) {
        val merged = if (ref.catalogPublicId == null) {
            ref.copy(catalogPublicId = loadRefPublicId(ref.id))
        } else {
            ref
        }
        upsertRefRow(merged)
    }

    @Upsert
    abstract suspend fun upsertRefRow(ref: CatalogRefEntity)

    /** The durable public id currently stored for a ref (null for a stub / older row); the monotonic-merge read. */
    @Query("SELECT catalogPublicId FROM catalog_refs WHERE id = :refId")
    abstract suspend fun loadRefPublicId(refId: Long): String?

    @Insert
    abstract suspend fun insertTeas(teas: List<TeaSampleEntity>)

    @Insert
    abstract suspend fun insertPlacements(placements: List<PlacementEntity>)

    @Insert
    abstract suspend fun insertFlavors(flavors: List<FlavorEntity>)

    @Insert
    abstract suspend fun insertPurchases(purchases: List<PurchaseLocationEntity>)

    @Insert
    abstract suspend fun insertPhotos(photos: List<PhotoEntity>)

    @Query("SELECT * FROM tea_photos WHERE teaId = :teaId ORDER BY position")
    abstract suspend fun loadPhotos(teaId: String): List<PhotoEntity>

    @Query("SELECT uri FROM tea_photos WHERE id = :photoId")
    abstract suspend fun loadPhotoUri(photoId: String): String?

    @Query("SELECT COALESCE(MAX(position), -1) + 1 FROM tea_photos WHERE teaId = :teaId")
    abstract suspend fun nextPhotoPosition(teaId: String): Int

    @Query("UPDATE tea_photos SET position = :position WHERE id = :photoId")
    abstract suspend fun updatePhotoPosition(photoId: String, position: Int)

    @Query("DELETE FROM tea_photos WHERE id = :photoId")
    abstract suspend fun deletePhotoRow(photoId: String)

    @Query("UPDATE placements SET tierId = :tierId, position = :position WHERE id = :placementId")
    abstract suspend fun updatePlacement(placementId: String, tierId: String?, position: Int)

    @Query("DELETE FROM placements WHERE id = :placementId")
    abstract suspend fun deletePlacement(placementId: String)

    @Query("SELECT * FROM placements WHERE id = :placementId")
    abstract suspend fun loadPlacement(placementId: String): PlacementEntity?

    @Query("DELETE FROM tea_samples WHERE id = :teaId")
    abstract suspend fun deleteTeaRow(teaId: String)

    @Query("UPDATE tiers SET label = :label WHERE id = :tierId")
    abstract suspend fun updateTierLabel(tierId: String, label: String)

    @Query("UPDATE tiers SET colorArgb = :colorArgb WHERE id = :tierId")
    abstract suspend fun updateTierColor(tierId: String, colorArgb: Long?)

    @Query("UPDATE tiers SET position = :position WHERE id = :tierId")
    abstract suspend fun updateTierPosition(tierId: String, position: Int)

    @Query("DELETE FROM tiers WHERE id = :tierId")
    abstract suspend fun deleteTier(tierId: String)

    /**
     * Rewrites only the user-editable scalar columns of a sample. Leaves shortBlurb (catalog/AI)
     * untouched and never touches placements: edits to a sample ripple to every board it sits on.
     */
    @Query(
        "UPDATE tea_samples SET nameRu = :nameRu, nameZh = :nameZh, pinyin = :pinyin, " +
            "nameEn = :nameEn, type = :type, origin = :origin, notes = :notes, vendor = :vendor, " +
            "product = :product, harvestYear = :harvestYear, batch = :batch, grade = :grade WHERE id = :teaId",
    )
    abstract suspend fun updateTeaFields(
        teaId: String,
        nameRu: String?,
        nameZh: String?,
        pinyin: String?,
        nameEn: String?,
        type: String,
        origin: String?,
        notes: String?,
        vendor: String?,
        product: String?,
        harvestYear: Int?,
        batch: String?,
        grade: String?,
    )

    /** The raw sample row (no children) — used by the enrichment patch to merge non-blank fields. */
    @Query("SELECT * FROM tea_samples WHERE id = :teaId")
    abstract suspend fun loadTeaRow(teaId: String): TeaSampleEntity?

    /** Flips only the enrichment lifecycle column (PENDING/QUEUED/DONE/FAILED) — no field overwrite. */
    @Query("UPDATE tea_samples SET enrichmentState = :state WHERE id = :teaId")
    abstract suspend fun updateEnrichmentState(teaId: String, state: String)

    /**
     * Patches the catalog-derived fields after a successful resolve and stamps the final state.
     * Names/type/origin/blurb are enriched suggestions (#21); the caller merges them with the
     * user's typed values (keeping a non-blank user value when the catalog has none) before writing.
     * The catalog ref stub must already exist (FK) — [applyEnrichmentPatch] ensures it.
     */
    @Query(
        "UPDATE tea_samples SET nameRu = :nameRu, nameZh = :nameZh, pinyin = :pinyin, nameEn = :nameEn, " +
            "type = :type, origin = :origin, shortBlurb = :shortBlurb, catalogTeaId = :catalogTeaId, " +
            "enrichmentState = :state WHERE id = :teaId",
    )
    abstract suspend fun patchEnrichment(
        teaId: String,
        nameRu: String?,
        nameZh: String?,
        pinyin: String?,
        nameEn: String?,
        type: String,
        origin: String?,
        shortBlurb: String?,
        catalogTeaId: Long?,
        state: String,
    )

    /** Teas left mid-enrichment by a prior run (process death / offline / rate-limited) — re-dispatched on launch. */
    @Query("SELECT * FROM tea_samples WHERE enrichmentState IN ('PENDING', 'QUEUED', 'RATE_LIMITED')")
    abstract suspend fun teasNeedingEnrichment(): List<TeaSampleEntity>

    /**
     * Atomic enrichment merge (review 2026-06-19): load the row, fill only the fields the user left
     * blank with the catalog's `candidate*` values, and write back — all in ONE @Transaction, so a
     * concurrent user edit landing between the read and the write can't be clobbered (the old
     * read-merge-write in the manager had that lost-update window). The catalog is a suggestion that
     * wins only where the user is blank (#21). `state` is passed in (not hard-coded) to keep this DAO
     * free of the domain enum.
     *
     * Since v7 the link is per-sample (no UNIQUE): every sample resolving to ref R links to R
     * (findings #14/#16/#23 — the old `linkOwner` branch that left differently-spelled duplicates
     * unlinked is gone). The catalog_refs stub is created first so the `catalogTeaId` FK holds.
     */
    @Transaction
    open suspend fun applyEnrichmentPatch(
        teaId: String,
        candidateNameRu: String?,
        candidateNameZh: String?,
        candidatePinyin: String?,
        candidateNameEn: String?,
        type: String,
        candidateOrigin: String?,
        candidateShortBlurb: String?,
        ref: CatalogRefEntity,
        state: String,
    ) {
        val current = loadTeaRow(teaId) ?: return
        // Blank-only merge (#21, AND-P1-1): the user's value always wins; the catalog only FILLS a
        // field the user left blank — it is a suggestion, never authoritative. (The canonical catalog
        // name/facts still live on the linked ref via `upsertRef`, so nothing is lost.)
        val nameRu = current.nameRu?.takeIf { it.isNotBlank() } ?: candidateNameRu
        val nameZh = current.nameZh?.takeIf { it.isNotBlank() } ?: candidateNameZh
        val pinyin = current.pinyin?.takeIf { it.isNotBlank() } ?: candidatePinyin
        val nameEn = current.nameEn?.takeIf { it.isNotBlank() } ?: candidateNameEn
        val origin = current.origin?.takeIf { it.isNotBlank() } ?: candidateOrigin
        val shortBlurb = current.shortBlurb?.takeIf { it.isNotBlank() } ?: candidateShortBlurb
        // Never silently replace a user-set type. `OTHER` is the add-form default ("didn't choose"),
        // so adopt the catalog's classification only then; otherwise keep what the user picked.
        val mergedType = if (current.type == "OTHER") type else current.type

        // Cache the ref's full facts (FK target for the link below), then patch the sample.
        upsertRef(ref)
        patchEnrichment(teaId, nameRu, nameZh, pinyin, nameEn, mergedType, origin, shortBlurb, ref.id, state)
    }

    @Query("DELETE FROM tea_flavors WHERE teaId = :teaId")
    abstract suspend fun deleteFlavorsFor(teaId: String)

    @Query("DELETE FROM purchase_locations WHERE teaId = :teaId")
    abstract suspend fun deletePurchasesFor(teaId: String)

    // --- Full-database export / replace (decisions.md #26) -------------------------------------
    // Plain table dumps, no relation assembly: the backup serializer rebuilds the graph from ids,
    // which keeps the JSON schema a faithful 1:1 of the tables (placement positions/tierIds round
    // trip) and decoupled from the read-side aggregates.

    @Query("SELECT * FROM boards")
    abstract suspend fun allBoards(): List<BoardEntity>

    @Query("SELECT * FROM tiers")
    abstract suspend fun allTiers(): List<TierEntity>

    @Query("SELECT * FROM catalog_refs")
    abstract suspend fun allRefs(): List<CatalogRefEntity>

    @Query("SELECT * FROM tea_samples")
    abstract suspend fun allTeaRows(): List<TeaSampleEntity>

    @Query("SELECT * FROM placements")
    abstract suspend fun allPlacements(): List<PlacementEntity>

    @Query("SELECT * FROM tea_flavors")
    abstract suspend fun allFlavors(): List<FlavorEntity>

    @Query("SELECT * FROM purchase_locations")
    abstract suspend fun allPurchases(): List<PurchaseLocationEntity>

    @Query("SELECT * FROM tea_photos")
    abstract suspend fun allPhotos(): List<PhotoEntity>

    @Query("DELETE FROM boards")
    abstract suspend fun deleteAllBoards()

    @Query("DELETE FROM tea_samples")
    abstract suspend fun deleteAllTeas()

    @Query("DELETE FROM catalog_refs")
    abstract suspend fun deleteAllRefs()

    @Transaction
    open suspend fun seed(
        boards: List<BoardEntity>,
        tiers: List<TierEntity>,
        catalogRefs: List<CatalogRefEntity>,
        teas: List<TeaSampleEntity>,
        placements: List<PlacementEntity>,
        flavors: List<FlavorEntity>,
        purchases: List<PurchaseLocationEntity>,
        photos: List<PhotoEntity>,
    ) {
        insertBoards(boards)
        insertTiers(tiers)
        if (catalogRefs.isNotEmpty()) insertRefs(catalogRefs) // before samples (FK)
        insertTeas(teas)
        insertPlacements(placements)
        insertFlavors(flavors)
        insertPurchases(purchases)
        if (photos.isNotEmpty()) insertPhotos(photos)
    }

    /** One consistent read of every table for the export bundle (decisions.md #26). */
    @Transaction
    open suspend fun exportSnapshot(): SeedEntities = SeedEntities(
        boards = allBoards(),
        tiers = allTiers(),
        catalogRefs = allRefs(),
        teas = allTeaRows(),
        placements = allPlacements(),
        flavors = allFlavors(),
        purchases = allPurchases(),
        photos = allPhotos(),
    )

    /**
     * Destructive restore (decisions.md #26): wipes the whole catalog and reinserts [data] in one
     * transaction. Deleting boards then samples cascades tiers/placements/flavors/purchases/photos;
     * catalog_refs are dropped explicitly (they are parents, not cascaded) so a half-applied import
     * can never leave a mix of old and new rows. Photo *files* on disk are the caller's responsibility
     * (restored before this runs; old ones are orphaned).
     */
    @Transaction
    open suspend fun replaceAll(data: SeedEntities) {
        deleteAllBoards()
        deleteAllTeas()
        deleteAllRefs()
        seed(
            data.boards,
            data.tiers,
            data.catalogRefs,
            data.teas,
            data.placements,
            data.flavors,
            data.purchases,
            data.photos,
        )
    }

    /**
     * Atomic board create: inserts the board row and its seeded tiers (may be empty for a blank
     * template) so an interrupted write never leaves a board without its template's tiers.
     */
    @Transaction
    open suspend fun createBoardWithTiers(board: BoardEntity, tiers: List<TierEntity>) {
        insertBoards(listOf(board))
        if (tiers.isNotEmpty()) insertTiers(tiers)
    }

    /**
     * Re-inserts a board deleted by [deleteBoardRow] together with the tiers + placements that
     * cascaded with it, in one transaction. Backs the board-delete Undo. The shared samples were
     * never deleted (board delete only cascades the board's own tiers + placements), so they are
     * not part of the snapshot and the restored placements' teaId FK still resolves.
     */
    @Transaction
    open suspend fun restoreBoard(board: BoardEntity, tiers: List<TierEntity>, placements: List<PlacementEntity>) {
        insertBoards(listOf(board))
        if (tiers.isNotEmpty()) insertTiers(tiers)
        if (placements.isNotEmpty()) insertPlacements(placements)
    }

    /**
     * Re-inserts a tier deleted by [removeTier] and moves its placements back out of the tray to
     * the restored tier (their original tierId + position from the pre-delete snapshot), in one
     * transaction. Backs the tier-delete Undo.
     */
    @Transaction
    open suspend fun restoreTier(tier: TierEntity, reassigned: List<PlacementMove>) {
        insertTiers(listOf(tier))
        applyPlacements(reassigned)
    }

    /**
     * Re-inserts a tea deleted by [deleteTea] together with its flavors, purchases, placements, and
     * photo rows, in one transaction. Backs the tea-delete Undo. The catalog ref is stubbed first so
     * the `catalogTeaId` FK holds even if the ref was evicted while the tea was gone (mirrors [addTea]).
     */
    @Transaction
    open suspend fun restoreTea(
        tea: TeaSampleEntity,
        flavors: List<FlavorEntity>,
        purchases: List<PurchaseLocationEntity>,
        placements: List<PlacementEntity>,
        photos: List<PhotoEntity>,
    ) {
        tea.catalogTeaId?.let { insertRefStub(it, tea.type) }
        insertTeas(listOf(tea))
        insertFlavors(flavors)
        insertPurchases(purchases)
        if (placements.isNotEmpty()) insertPlacements(placements)
        if (photos.isNotEmpty()) insertPhotos(photos)
    }

    /**
     * Atomic add: when [tea] is non-null we insert a brand-new sample + its flavor and purchase
     * rows (stubbing the catalog ref first when linked, so the FK holds — finding #14); when null
     * the placement attaches to an existing sample (auto-link by name, decisions.md #42), so we
     * deliberately leave the existing sample's fields untouched.
     */
    @Transaction
    open suspend fun addTea(
        tea: TeaSampleEntity?,
        flavors: List<FlavorEntity>,
        purchases: List<PurchaseLocationEntity>,
        placement: PlacementEntity,
    ) {
        if (tea != null) {
            tea.catalogTeaId?.let { insertRefStub(it, tea.type) }
            insertTeas(listOf(tea))
            insertFlavors(flavors)
            insertPurchases(purchases)
        }
        insertPlacements(listOf(placement))
    }

    /** Rewrites tier + order for every affected placement in one transaction (drag-to-rank). */
    @Transaction
    open suspend fun applyPlacements(moves: List<PlacementMove>) {
        moves.forEach { updatePlacement(it.placementId, it.tierId, it.position) }
    }

    /** Rewrites the order of every reordered tier in one transaction. */
    @Transaction
    open suspend fun reorderTiers(positions: List<TierPosition>) {
        positions.forEach { updateTierPosition(it.tierId, it.position) }
    }

    /**
     * Deletes a tier and reassigns its placements to the unranked tray in one transaction.
     * Placements have no FK to tiers (only a nullable `tierId` column), so the orphaned ones
     * must be moved explicitly or they would disappear from the board.
     */
    @Transaction
    open suspend fun removeTier(tierId: String, reassignedPlacements: List<PlacementMove>) {
        applyPlacements(reassignedPlacements)
        deleteTier(tierId)
    }

    /**
     * Removes a single placement (one sample on one board); the sample row stays so any other
     * boards keep their copy. Used by the per-card "Убрать с подборки" action.
     */
    @Transaction
    open suspend fun removePlacement(placementId: String) {
        deletePlacement(placementId)
    }

    /**
     * Deletes the sample everywhere: the row goes, FK cascade drops all placements + flavors
     * + purchases. Used by the "Удалить чай совсем" action on the detail / edit screens.
     */
    @Transaction
    open suspend fun deleteTea(teaId: String) {
        deleteTeaRow(teaId)
    }

    /**
     * Updates a sample's fields and replaces its flavor + purchase rows in one transaction.
     * Child rows are wholesale rebuilt (delete + insert) because edits can add, remove, or
     * reorder any number of them — simpler and just as cheap as diffing for the small lists.
     * Placements and photos stay untouched: edits ripple, drag-to-rank and the photo strip
     * own their own writes (decisions.md #43).
     *
     * [original] guards against the UX2-P0-1 lost-update race: [edited] and [applyEnrichmentPatch]'s
     * `candidate*` values share the same six columns ([TeaMergeFields]), so a stale form snapshot
     * (loaded before a background enrichment patch landed) must not blast the patch back out. When
     * [original] is non-null, a column only takes [edited]'s value if it actually differs from
     * [original] — i.e. the user deliberately changed it; an untouched column keeps whatever is
     * currently in the DB. Callers that pass no [original] (or when the row has since been deleted)
     * get the old unconditional-overwrite behavior.
     */
    @Transaction
    open suspend fun updateTea(
        teaId: String,
        edited: TeaMergeFields,
        original: TeaMergeFields?,
        notes: String?,
        vendor: String?,
        product: String?,
        harvestYear: Int?,
        batch: String?,
        grade: String?,
        flavors: List<FlavorEntity>,
        purchases: List<PurchaseLocationEntity>,
    ) {
        val current = original?.let { loadTeaRow(teaId) }
        val merged = if (current == null) {
            edited
        } else {
            TeaMergeFields(
                nameRu = if (edited.nameRu != original.nameRu) edited.nameRu else current.nameRu,
                nameZh = if (edited.nameZh != original.nameZh) edited.nameZh else current.nameZh,
                pinyin = if (edited.pinyin != original.pinyin) edited.pinyin else current.pinyin,
                nameEn = if (edited.nameEn != original.nameEn) edited.nameEn else current.nameEn,
                type = if (edited.type != original.type) edited.type else current.type,
                origin = if (edited.origin != original.origin) edited.origin else current.origin,
            )
        }
        updateTeaFields(
            teaId, merged.nameRu, merged.nameZh, merged.pinyin, merged.nameEn, merged.type, merged.origin,
            notes, vendor, product, harvestYear, batch, grade,
        )
        deleteFlavorsFor(teaId)
        deletePurchasesFor(teaId)
        insertFlavors(flavors)
        insertPurchases(purchases)
    }

    /**
     * Narrow flavor-only write (UX2-P0-2): used by "use reference as my rating," which must change
     * only the flavor rows, not round-trip the whole sample through [updateTea] from a possibly-stale
     * snapshot (that overwrote name/notes/purchases with whatever a concurrent edit had just changed).
     */
    @Transaction
    open suspend fun updateFlavors(teaId: String, flavors: List<FlavorEntity>) {
        deleteFlavorsFor(teaId)
        insertFlavors(flavors)
    }

    /**
     * Inserts one photo row at the next available position. The repository copies the bytes
     * to disk first so the URI is already a stable absolute path by the time we write the row.
     * [photo]'s own `position` is ignored and recomputed here, inside the same @Transaction as
     * the insert — reading it in the repository first (a separate suspend call) raced two
     * concurrent adds for the same tea onto the same position (e.g. a multi-photo gallery pick).
     */
    @Transaction
    open suspend fun addPhoto(photo: PhotoEntity) {
        insertPhotos(listOf(photo.copy(position = nextPhotoPosition(photo.teaId))))
    }

    /**
     * Deletes one photo row and contiguously renumbers the survivors so positions stay 0..n
     * for the strip layout. The repository deletes the file on disk separately (best-effort,
     * outside the DB transaction) — keeping I/O outside the @Transaction avoids holding the
     * SQLite write lock for the duration of a filesystem call.
     */
    @Transaction
    open suspend fun removePhoto(photoId: String, survivingOrder: List<PhotoPosition>) {
        deletePhotoRow(photoId)
        survivingOrder.forEach { updatePhotoPosition(it.photoId, it.position) }
    }

    /** Rewrites the order of every photo in the strip in one transaction. */
    @Transaction
    open suspend fun applyPhotoPositions(positions: List<PhotoPosition>) {
        positions.forEach { updatePhotoPosition(it.photoId, it.position) }
    }
}
