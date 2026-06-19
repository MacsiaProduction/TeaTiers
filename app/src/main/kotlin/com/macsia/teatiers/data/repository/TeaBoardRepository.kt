package com.macsia.teatiers.data.repository

import android.net.Uri
import com.macsia.teatiers.data.db.BoardEntity
import com.macsia.teatiers.data.db.BoardWithChildren
import com.macsia.teatiers.data.db.PhotoEntity
import com.macsia.teatiers.data.db.PhotoPosition
import com.macsia.teatiers.data.db.PlacementEntity
import com.macsia.teatiers.data.db.PlacementMove
import com.macsia.teatiers.data.db.TeaDao
import com.macsia.teatiers.data.db.TeaMatchKeyRow
import com.macsia.teatiers.data.db.TierEntity
import com.macsia.teatiers.data.db.TierPosition
import com.macsia.teatiers.data.db.toDomain
import com.macsia.teatiers.data.db.toEntities
import com.macsia.teatiers.data.db.toSeedEntities
import com.macsia.teatiers.data.photos.PhotoStore
import com.macsia.teatiers.data.sample.SampleBoardProvider
import com.macsia.teatiers.di.AppScope
import com.macsia.teatiers.domain.model.Board
import com.macsia.teatiers.domain.model.PhotoSource
import com.macsia.teatiers.domain.model.Placement
import com.macsia.teatiers.domain.model.Tea
import com.macsia.teatiers.domain.model.TeaPhoto
import com.macsia.teatiers.domain.model.TierTemplate
import com.macsia.teatiers.domain.model.seedTiers
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/** Result of [TeaBoardRepository.addTea]: the resolved user-tea id and whether a new row was inserted. */
data class AddedTea(val teaId: String, val created: Boolean)

/**
 * Room-backed single source of truth for boards (M1; shared-teas reopening per decisions.md
 * #42). Boards are exposed as a hot [StateFlow] collected on the app scope so the synchronous
 * reads below see loaded data and an added tea shows up on every screen at once.
 *
 * After the shared-teas reopening teas are user-global: each board exposes [Placement]s, the
 * underlying [Tea] is shared across boards, and edits to a tea ripple to every board it sits
 * on. New teas auto-link to an existing user-tea by name match (case-insensitive on `nameRu`,
 * exact on `nameZh`, case-insensitive on `pinyin`, in that order).
 *
 * On first run (empty DB) the store is seeded from [SampleBoardProvider]; later runs read what
 * the user saved. v1 → v2 schema migration is destructive (the builder configures
 * `fallbackToDestructiveMigration` — acceptable pre-launch).
 */
@Singleton
class TeaBoardRepository @Inject constructor(
    private val dao: TeaDao,
    private val photoStore: PhotoStore,
    @AppScope private val scope: CoroutineScope,
    seed: SampleBoardProvider,
) {

    /**
     * Wall-clock used when stamping new photo rows. Tests override it with a fixed value via
     * [setClockForTest] (Hilt can't resolve a function-type binding, so this stays a plain
     * field rather than a constructor parameter).
     */
    private var clock: () -> Long = System::currentTimeMillis

    internal fun setClockForTest(clock: () -> Long) {
        this.clock = clock
    }

    val boards: StateFlow<List<Board>> = dao.observeBoards()
        .map { rows -> rows.map(BoardWithChildren::toDomain) }
        .stateIn(scope, SharingStarted.Eagerly, emptyList())

    /**
     * The user's whole tea collection across boards (decisions.md #27), as a cold Room flow —
     * the "my teas" screen collects it while open. Unlike [boards] this has no synchronous
     * reader, so it is not eagerly shared.
     */
    val allTeas: Flow<List<Tea>> = dao.observeAllTeas()
        .map { rows -> rows.map { it.toDomain() } }

    init {
        scope.launch {
            if (dao.boardCount() == 0) {
                val entities = seed.boards().toSeedEntities()
                dao.seed(
                    entities.boards,
                    entities.tiers,
                    entities.teas,
                    entities.placements,
                    entities.flavors,
                    entities.purchases,
                    entities.photos,
                )
            }
            // App-open orphan sweep (review 2026-06-18): drop any photo file that no DB row
            // references — e.g. left by a crash between copy-in and row-insert, or an older import
            // before reconcile existed. Runs once at startup, before any add-photo UI exists, so it
            // can't race a fresh copy-in. Best-effort.
            runCatching {
                val known = dao.allPhotos().map { it.uri }.filter { it.startsWith("/") }.toSet()
                photoStore.reconcile(known)
            }
        }
    }

    fun board(boardId: String): Board? = boards.value.firstOrNull { it.id == boardId }

    /**
     * Resolves the user-tea by id. Walks the in-memory boards first (placement-side cache, free
     * if the tea sits on at least one board), then falls back to a DB read so a tea with zero
     * placements (e.g. removed from every board but not deleted) still resolves on the detail
     * and edit screens.
     */
    suspend fun tea(teaId: String): Tea? {
        boards.value.forEach { board ->
            val hit = (board.placements.values.flatten() + board.unranked).firstOrNull { it.tea.id == teaId }
            if (hit != null) return hit.tea
        }
        return dao.loadTea(teaId)?.toDomain()
    }

    /**
     * Reactive single-tea read for the edit screen's photo strip (review 2026-06-19). Unlike [tea]
     * (a one-shot snapshot read off the boards cache), this is a Room Flow, so adding or removing a
     * photo refreshes the strip even for a tea with zero board placements — which never shows up in
     * [boards]. Emits null if the tea is deleted.
     */
    fun observeTea(teaId: String): Flow<Tea?> = dao.observeTea(teaId).map { it?.toDomain() }

    /**
     * Counts how many boards the user-tea currently sits on. Drives the "Изменения видны во
     * всех подборках" hint on the edit screen — only shown when this is greater than 1.
     */
    suspend fun placementCountForTea(teaId: String): Int = dao.placementCountForTea(teaId)

    /**
     * Creates a new board with [label] (trimmed; blank is a no-op returning null) and seeds it
     * with the tiers prescribed by [template]. Position is the next slot at the end of the boards
     * list. Returns the new board id, or null if [label] was blank.
     */
    suspend fun createBoard(label: String, template: TierTemplate): String? {
        val clean = label.trim().ifEmpty { return null }
        val boardId = "board-${UUID.randomUUID().toString().take(8)}"
        val position = boards.value.size
        val boardEntity = BoardEntity(id = boardId, name = clean, position = position)
        val tierEntities = template.seedTiers(boardId).map { tier ->
            TierEntity(
                id = tier.id,
                boardId = boardId,
                label = tier.label,
                position = tier.position,
                colorArgb = tier.colorArgb,
            )
        }
        dao.createBoardWithTiers(boardEntity, tierEntities)
        return boardId
    }

    /**
     * Adds a placement of [tea] on [boardId]. Resolve-or-create (decisions.md #42): if a
     * matching user-tea already exists by name we reuse it (no overwrite of its fields), else
     * we insert a fresh user-tea. With a known [tierId] the placement joins that tier; an
     * unknown tier (or null) lands it in the unranked tray. Returns the resolved user-tea id
     * (existing or freshly-created) plus whether a new row was inserted — the caller only kicks
     * off background enrichment for a genuinely new tea (#21), never an auto-linked existing one.
     * Null when [boardId] is unknown (and nothing was written).
     */
    suspend fun addTea(boardId: String, tea: Tea, tierId: String?): AddedTea? {
        val board = board(boardId) ?: return null
        val resolvedTier = tierId?.takeIf { id -> board.tiers.any { it.id == id } }
        val existingTeaId = resolveTeaIdForMatch(tea)

        // Always generate a fresh teaId for new teas: the candidate id from the form is a
        // throwaway UUID, but a sample/test caller may pass a sticky id and we don't want
        // to risk a PK collision — the placement is the user-visible handle anyway.
        val teaIdToPlace = existingTeaId ?: "tea-${UUID.randomUUID()}"
        val teaToInsert = if (existingTeaId == null) tea.copy(id = teaIdToPlace).toEntities() else null

        val placementId = "placement-${UUID.randomUUID()}"
        val position = dao.nextPlacementPosition(boardId)
        val placementEntity = PlacementEntity(
            id = placementId,
            boardId = boardId,
            teaId = teaIdToPlace,
            tierId = resolvedTier,
            position = position,
        )
        dao.addTea(
            tea = teaToInsert?.tea,
            flavors = teaToInsert?.flavors.orEmpty(),
            purchases = teaToInsert?.purchases.orEmpty(),
            placement = placementEntity,
        )
        return AddedTea(teaId = teaIdToPlace, created = existingTeaId == null)
    }

    /**
     * Drag-to-rank: moves [placementId] within [boardId] to [targetTierId] (null = the unranked
     * tray) at [targetIndex], the slot among the *other* placements in the target group. An
     * unknown tier falls back to the tray. No-op when the board or placement is unknown, or
     * the placement would not change.
     */
    suspend fun moveTea(boardId: String, placementId: String, targetTierId: String?, targetIndex: Int) {
        val board = board(boardId) ?: return
        val moves = computeMovePlacements(board, placementId, targetTierId, targetIndex)
        if (moves.isNotEmpty()) dao.applyPlacements(moves)
    }

    /**
     * Removes a single placement (one tea on one board); the user-tea row stays so it remains
     * visible on every other board it sits on. No-op when the placement is unknown — the DB
     * delete is idempotent so we don't bother validating against the current snapshot.
     */
    suspend fun removePlacement(placementId: String) {
        dao.removePlacement(placementId)
    }

    /**
     * Deletes the user-tea everywhere: removes the tea row and (via FK cascade) every
     * placement on every board, plus its flavors, purchases, and photos. The photo files on
     * disk are deleted *before* the row goes so a half-failed cascade leaves the DB clean —
     * the file delete is best-effort, the DB delete is the authoritative fact.
     */
    suspend fun deleteTea(teaId: String) {
        val photoPaths = dao.loadPhotoUrisFor(teaId)
        dao.deleteTea(teaId)
        photoPaths.filter { it.startsWith("/") }.forEach { runCatching { photoStore.delete(it) } }
    }

    /**
     * Adds a new user photo to [teaId]. Copies the bytes via [PhotoStore] (which lands them
     * under the app-private dir, decisions.md #43), then inserts the row at the next position.
     * Returns the new photo id, or null if the copy failed (no row is written so a botched
     * pick never half-creates a placement). No-op when the tea is unknown.
     */
    suspend fun addPhoto(teaId: String, source: Uri): String? {
        if (dao.loadTea(teaId) == null) return null
        val storedPath = photoStore.copyIn(source) ?: return null
        val photoId = "photo-${UUID.randomUUID()}"
        val position = dao.nextPhotoPosition(teaId)
        dao.addPhoto(
            PhotoEntity(
                id = photoId,
                teaId = teaId,
                uri = storedPath,
                position = position,
                source = PhotoSource.USER.name,
                license = null,
                sourceUrl = null,
                createdAtEpochMs = clock(),
            ),
        )
        return photoId
    }

    /**
     * Removes a single photo: deletes the DB row and renumbers the survivors so positions
     * stay 0..n. The file delete on disk is best-effort and runs *outside* the @Transaction
     * so SQLite doesn't hold its write lock through filesystem I/O.
     */
    suspend fun removePhoto(teaId: String, photoId: String) {
        val path = dao.loadPhotoUri(photoId) ?: return
        val survivors = dao.loadPhotos(teaId)
            .filter { it.id != photoId }
            .mapIndexed { index, row -> PhotoPosition(row.id, index) }
        dao.removePhoto(photoId, survivors)
        if (path.startsWith("/")) runCatching { photoStore.delete(path) }
    }

    /**
     * Reorders the photos on [teaId] to match [orderedPhotoIds]. Drops unknown ids, appends
     * any of the tea's photos the request omitted, and renumbers contiguously. No-op when the
     * resulting order matches the current one.
     */
    suspend fun reorderPhotos(teaId: String, orderedPhotoIds: List<String>) {
        val current = dao.loadPhotos(teaId)
        if (current.isEmpty()) return
        val positions = computePhotoPositions(current.map(PhotoEntity::id), orderedPhotoIds)
        if (positions.isNotEmpty()) dao.applyPhotoPositions(positions)
    }

    /** Appends a new tier (default ramp color) to the end of [boardId]'s tier list. */
    suspend fun addTier(boardId: String, label: String) {
        val clean = label.trim().ifEmpty { return }
        if (board(boardId) == null) return
        val position = dao.nextTierPosition(boardId)
        dao.insertTiers(
            listOf(TierEntity(id = "tier-${UUID.randomUUID()}", boardId = boardId, label = clean, position = position, colorArgb = null)),
        )
    }

    /** Renames a tier; blank labels are ignored so a tier always keeps a usable label. */
    suspend fun renameTier(boardId: String, tierId: String, label: String) {
        val clean = label.trim().ifEmpty { return }
        if (board(boardId)?.hasTier(tierId) != true) return
        dao.updateTierLabel(tierId, clean)
    }

    /** Sets a tier's color override, or clears it ([colorArgb] null) to fall back to the ramp. */
    suspend fun setTierColor(boardId: String, tierId: String, colorArgb: Long?) {
        if (board(boardId)?.hasTier(tierId) != true) return
        dao.updateTierColor(tierId, colorArgb)
    }

    /** Reorders the board's tiers to match [orderedTierIds]; a no-op when nothing changes. */
    suspend fun reorderTiers(boardId: String, orderedTierIds: List<String>) {
        val board = board(boardId) ?: return
        val positions = computeTierPositions(board, orderedTierIds)
        if (positions.isNotEmpty()) dao.reorderTiers(positions)
    }

    /** Removes a tier and drops its placements into the unranked tray (open item #11). */
    suspend fun removeTier(boardId: String, tierId: String) {
        val board = board(boardId) ?: return
        if (!board.hasTier(tierId)) return
        dao.removeTier(tierId, computeTrayReassignment(board, tierId))
    }

    /**
     * Rewrites the editable fields and child rows (flavors, purchases) of [teaId]. The DAO
     * never touches placements, so editing the form ripples to every board the tea is on
     * without moving any of them. No-op when the tea is unknown.
     */
    suspend fun updateTea(teaId: String, tea: Tea) {
        if (tea(teaId) == null) return
        // toEntities also rebuilds a TeaEntity, but the DAO @Transaction only reads the scalar
        // columns we pass explicitly and never writes the entity itself, so we just need
        // flavor + purchase rows keyed by teaId.
        val entities = tea.toEntities(rowId = teaId)
        dao.updateTea(
            teaId = teaId,
            nameRu = tea.nameRu,
            nameZh = tea.nameZh,
            pinyin = tea.pinyin,
            nameEn = tea.nameEn,
            type = tea.type.name,
            origin = tea.origin,
            notes = tea.notes,
            flavors = entities.flavors,
            purchases = entities.purchases,
        )
    }

    /**
     * Tries to find an existing user-tea matching [candidate] by name (decisions.md #42). Picks
     * the first non-blank match field on the candidate in priority `nameRu` → `nameZh` →
     * `pinyin`, then returns any user-tea whose same field equals it. Returns null when no
     * match field is non-blank or no row matches.
     *
     * The match is done in Kotlin rather than SQLite because SQLite's built-in `LOWER` is
     * ASCII-only and would never lower-case Russian uppercase letters; pulling the (small)
     * candidate set into memory keeps the rule correct without an ICU build.
     */
    private suspend fun resolveTeaIdForMatch(candidate: Tea): String? {
        // Catalog identity wins over name matching (#42): two adds of the same catalog tea — even with
        // differently-typed names — resolve to one user-tea, and a second catalog-linked row is prevented.
        candidate.catalogTeaId?.let { id -> dao.findTeaIdByCatalogId(id)?.let { return it } }
        val candidateKey = candidate.matchKey() ?: return null
        return dao.loadTeaMatchKeys()
            .firstOrNull { row -> row.matches(candidateKey) }
            ?.id
    }
}

private fun Board.hasTier(tierId: String): Boolean = tiers.any { it.id == tierId }

/**
 * The first non-blank name field of a tea, used for resolve-or-create matching. The order
 * (`nameRu` → `nameZh` → `pinyin`) is also the priority the matcher walks.
 */
private sealed class MatchKey {
    abstract val value: String

    data class Ru(override val value: String) : MatchKey()
    data class Zh(override val value: String) : MatchKey()
    data class Py(override val value: String) : MatchKey()
}

private fun Tea.matchKey(): MatchKey? {
    nameRu.trim().lowercase().takeIf { it.isNotEmpty() }?.let { return MatchKey.Ru(it) }
    nameZh?.trim()?.takeIf { it.isNotEmpty() }?.let { return MatchKey.Zh(it) }
    pinyin?.trim()?.lowercase()?.takeIf { it.isNotEmpty() }?.let { return MatchKey.Py(it) }
    return null
}

private fun TeaMatchKeyRow.matches(candidate: MatchKey): Boolean = when (candidate) {
    // A Latin-script candidate (typed into the required nameRu, or a pinyin) also matches an existing
    // row's enriched English name, so "Tieguanyin" dedups against a row catalog-enriched with that
    // nameEn instead of becoming a second card (review F6). Still exact full-string, so low false-match.
    is MatchKey.Ru -> nameRu.trim().lowercase() == candidate.value || matchesEn(candidate.value)
    is MatchKey.Zh -> nameZh?.trim() == candidate.value
    is MatchKey.Py -> pinyin?.trim()?.lowercase() == candidate.value || matchesEn(candidate.value)
}

private fun TeaMatchKeyRow.matchesEn(value: String): Boolean =
    nameEn?.trim()?.lowercase()?.takeIf { it.isNotEmpty() } == value

/**
 * Pure reorder math for [TeaBoardRepository.moveTea], split out so it is unit-testable without
 * a DAO. Removes [placementId] from its current group, inserts it into the target group (an
 * unknown tier becomes the tray) at [targetIndex] clamped into range, and returns contiguous
 * 0..n positions for every placement in the affected group(s). Ordering within a group is all
 * that matters — [BoardWithChildren.toDomain] sorts by position inside each group — so only
 * touched groups are renumbered. Returns empty when the move changes nothing (placement
 * absent, or already at that exact slot).
 */
internal fun computeMovePlacements(
    board: Board,
    placementId: String,
    targetTierId: String?,
    targetIndex: Int,
): List<PlacementMove> {
    val target = if (targetTierId != null && board.tiers.any { tier -> tier.id == targetTierId }) targetTierId else null

    val groups = LinkedHashMap<String?, MutableList<String>>()
    board.tiers.forEach { tier ->
        groups[tier.id] = board.placements[tier.id].orEmpty().map { it.placementId }.toMutableList()
    }
    groups[null] = board.unranked.map { it.placementId }.toMutableList()

    // Resolve the source entry before reading its key: the tray's key is null, so an elvis on
    // `?.key` would treat "placement sits in the tray" the same as "placement not found".
    val sourceEntry = groups.entries.firstOrNull { it.value.contains(placementId) } ?: return emptyList()
    val sourceKey = sourceEntry.key
    val sourceList = sourceEntry.value
    val sourceIndex = sourceList.indexOf(placementId)
    sourceList.remove(placementId)

    val targetList = groups.getValue(target)
    val index = targetIndex.coerceIn(0, targetList.size)
    if (sourceKey == target && index == sourceIndex) return emptyList()
    targetList.add(index, placementId)

    return setOf(sourceKey, target).flatMap { key ->
        groups.getValue(key).mapIndexed { position, id -> PlacementMove(id, key, position) }
    }
}

/**
 * Pure reorder math for [TeaBoardRepository.reorderTiers]. Keeps the requested order (dropping
 * unknown ids), then appends any board tiers the request omitted, and renumbers to contiguous
 * 0..n. Returns empty when the resulting order already matches the board, so an idle drop writes
 * nothing.
 */
internal fun computeTierPositions(board: Board, orderedTierIds: List<String>): List<TierPosition> {
    val known = board.tiers.map { it.id }.toSet()
    val requested = orderedTierIds.filter { it in known }.distinct()
    val full = requested + board.tiers.map { it.id }.filterNot { it in requested }
    val current = board.tiers.sortedBy { it.position }.map { it.id }
    if (full == current) return emptyList()
    return full.mapIndexed { index, id -> TierPosition(id, index) }
}

/**
 * Pure helper for [TeaBoardRepository.removeTier]: the removed tier's placements join the
 * unranked tray after the placements already there, and the whole tray is renumbered to
 * contiguous 0..n so positions never collide. Empty when the tier holds no placements (the
 * tier is then simply deleted).
 */
internal fun computeTrayReassignment(board: Board, removedTierId: String): List<PlacementMove> {
    val removed = board.placements[removedTierId].orEmpty().map { it.placementId }
    if (removed.isEmpty()) return emptyList()
    val trayOrder = board.unranked.map { it.placementId } + removed
    return trayOrder.mapIndexed { position, id -> PlacementMove(id, null, position) }
}

/**
 * Pure reorder math for [TeaBoardRepository.reorderPhotos]. Drops unknown ids from the
 * request, appends any tea-side photos the request omitted (in their current relative order),
 * and renumbers contiguously to 0..n. Returns empty when the resulting order already matches
 * [currentOrder] so an idle drop writes nothing.
 */
internal fun computePhotoPositions(
    currentOrder: List<String>,
    requestedOrder: List<String>,
): List<PhotoPosition> {
    val known = currentOrder.toSet()
    val requested = requestedOrder.filter { it in known }.distinct()
    val full = requested + currentOrder.filterNot { it in requested }
    if (full == currentOrder) return emptyList()
    return full.mapIndexed { index, id -> PhotoPosition(id, index) }
}
