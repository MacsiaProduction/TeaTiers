package com.macsia.teatiers.data.repository

import com.macsia.teatiers.data.db.BoardEntity
import com.macsia.teatiers.data.db.BoardWithChildren
import com.macsia.teatiers.data.db.CatalogRefEntity
import com.macsia.teatiers.data.db.FlavorEntity
import com.macsia.teatiers.data.db.PhotoEntity
import com.macsia.teatiers.data.db.PlacementEntity
import com.macsia.teatiers.data.db.PlacementWithTea
import com.macsia.teatiers.data.db.PurchaseLocationEntity
import com.macsia.teatiers.data.db.TeaDao
import com.macsia.teatiers.data.db.TeaMatchKeyRow
import com.macsia.teatiers.data.db.TeaSampleEntity
import com.macsia.teatiers.data.db.TeaWithChildren
import com.macsia.teatiers.data.db.TierEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map

/**
 * In-memory [TeaDao] for JVM unit tests. The inherited @Transaction methods (seed, addTea,
 * applyEnrichmentPatch, applyPlacements, removeTier, removePlacement, deleteTea, updateTea) run
 * unchanged on top of these inserts/updates; [observeBoards] re-emits the rebuilt aggregate after
 * each write, mirroring how Room's relation query reacts to table changes.
 *
 * The aggregate now joins through [PlacementEntity] (decisions.md #42), so the boards-side
 * cache key is `boardId` and the per-placement sample reference resolves against the global pool.
 */
class FakeTeaDao : TeaDao() {

    private val boards = mutableListOf<BoardEntity>()
    private val tiers = mutableListOf<TierEntity>()
    private val catalogRefs = mutableListOf<CatalogRefEntity>()
    private val teas = mutableListOf<TeaSampleEntity>()
    private val placements = mutableListOf<PlacementEntity>()
    private val flavors = mutableListOf<FlavorEntity>()
    private val purchases = mutableListOf<PurchaseLocationEntity>()
    private val photos = mutableListOf<PhotoEntity>()

    private val state = MutableStateFlow<List<BoardWithChildren>>(emptyList())
    private val allTeasState = MutableStateFlow<List<TeaWithChildren>>(emptyList())

    override fun observeBoards(): Flow<List<BoardWithChildren>> = state

    override fun observeAllTeas(): Flow<List<TeaWithChildren>> = allTeasState

    // Re-emits on every write (refresh() updates allTeasState), mirroring Room's reactive single-row
    // query — including for a placement-less sample, which still appears in the all-teas projection.
    override fun observeTea(teaId: String): Flow<TeaWithChildren?> =
        allTeasState.map { rows -> rows.firstOrNull { it.tea.id == teaId } }

    override suspend fun loadTea(teaId: String): TeaWithChildren? =
        teas.firstOrNull { it.id == teaId }?.let { tea ->
            TeaWithChildren(
                tea = tea,
                flavors = flavors.filter { it.teaId == tea.id },
                purchases = purchases.filter { it.teaId == tea.id },
                photos = photos.filter { it.teaId == tea.id },
            )
        }

    override suspend fun loadTeaMatchKeys(): List<TeaMatchKeyRow> =
        teas.map { TeaMatchKeyRow(id = it.id, nameRu = it.nameRu, nameZh = it.nameZh, pinyin = it.pinyin, nameEn = it.nameEn) }

    // Stable pick by id (mirrors the real `ORDER BY id LIMIT 1`) now that many samples may share a ref.
    override suspend fun findTeaIdByCatalogId(catalogTeaId: Long): String? =
        teas.filter { it.catalogTeaId == catalogTeaId }.minByOrNull { it.id }?.id

    override suspend fun placementCountForTea(teaId: String): Int =
        placements.count { it.teaId == teaId }

    override suspend fun boardCount(): Int = boards.size

    override suspend fun deleteBoardRow(boardId: String) {
        if (boards.removeAll { it.id == boardId }) {
            tiers.removeAll { it.boardId == boardId }       // FK cascade
            placements.removeAll { it.boardId == boardId }  // FK cascade; samples (shared) persist
            refresh()
        }
    }

    override suspend fun teaCount(): Int = teas.size

    override suspend fun photoCount(): Int = photos.size

    override suspend fun nextPlacementPosition(boardId: String): Int =
        (placements.filter { it.boardId == boardId }.maxOfOrNull { it.position } ?: -1) + 1

    override suspend fun nextTierPosition(boardId: String): Int =
        (tiers.filter { it.boardId == boardId }.maxOfOrNull { it.position } ?: -1) + 1

    override suspend fun insertBoards(boards: List<BoardEntity>) {
        this.boards += boards
        refresh()
    }

    override suspend fun insertTiers(tiers: List<TierEntity>) {
        this.tiers += tiers
        refresh()
    }

    override suspend fun insertRefs(refs: List<CatalogRefEntity>) {
        this.catalogRefs += refs
    }

    // Idempotent stub (INSERT OR IGNORE): no-op if a ref with this id already exists.
    override suspend fun insertRefStub(refId: Long, type: String) {
        if (catalogRefs.none { it.id == refId }) {
            catalogRefs += CatalogRefEntity(id = refId, type = type, fetchedAtEpochMs = 0L)
        }
    }

    override suspend fun insertTeas(teas: List<TeaSampleEntity>) {
        this.teas += teas
        refresh()
    }

    override suspend fun insertPlacements(placements: List<PlacementEntity>) {
        this.placements += placements
        refresh()
    }

    override suspend fun insertFlavors(flavors: List<FlavorEntity>) {
        this.flavors += flavors
        refresh()
    }

    override suspend fun insertPurchases(purchases: List<PurchaseLocationEntity>) {
        this.purchases += purchases
        refresh()
    }

    override suspend fun insertPhotos(photos: List<PhotoEntity>) {
        this.photos += photos
        refresh()
    }

    override suspend fun allBoards(): List<BoardEntity> = boards.toList()

    override suspend fun allTiers(): List<TierEntity> = tiers.toList()

    override suspend fun allRefs(): List<CatalogRefEntity> = catalogRefs.toList()

    override suspend fun allTeaRows(): List<TeaSampleEntity> = teas.toList()

    override suspend fun allPlacements(): List<PlacementEntity> = placements.toList()

    override suspend fun allFlavors(): List<FlavorEntity> = flavors.toList()

    override suspend fun allPurchases(): List<PurchaseLocationEntity> = purchases.toList()

    override suspend fun allPhotos(): List<PhotoEntity> = photos.toList()

    // Mirrors Room's ON DELETE CASCADE: boards -> tiers + placements.
    override suspend fun deleteAllBoards() {
        boards.clear()
        tiers.clear()
        placements.clear()
        refresh()
    }

    // Mirrors Room's ON DELETE CASCADE: samples -> placements + flavors + purchases + photos.
    override suspend fun deleteAllTeas() {
        teas.clear()
        placements.clear()
        flavors.clear()
        purchases.clear()
        photos.clear()
        refresh()
    }

    override suspend fun deleteAllRefs() {
        catalogRefs.clear()
    }

    override suspend fun loadPhotos(teaId: String): List<PhotoEntity> =
        photos.filter { it.teaId == teaId }.sortedBy { it.position }

    override suspend fun loadPhotoUrisFor(teaId: String): List<String> =
        photos.filter { it.teaId == teaId }.map { it.uri }

    override suspend fun loadPhotoUri(photoId: String): String? =
        photos.firstOrNull { it.id == photoId }?.uri

    override suspend fun nextPhotoPosition(teaId: String): Int =
        (photos.filter { it.teaId == teaId }.maxOfOrNull { it.position } ?: -1) + 1

    override suspend fun updatePhotoPosition(photoId: String, position: Int) {
        val index = photos.indexOfFirst { it.id == photoId }
        if (index >= 0) {
            photos[index] = photos[index].copy(position = position)
            refresh()
        }
    }

    override suspend fun deletePhotoRow(photoId: String) {
        if (photos.removeAll { it.id == photoId }) refresh()
    }

    override suspend fun updatePlacement(placementId: String, tierId: String?, position: Int) {
        val index = placements.indexOfFirst { it.id == placementId }
        if (index >= 0) {
            placements[index] = placements[index].copy(tierId = tierId, position = position)
            refresh()
        }
    }

    override suspend fun deletePlacement(placementId: String) {
        if (placements.removeAll { it.id == placementId }) refresh()
    }

    override suspend fun deleteTeaRow(teaId: String) {
        // Mimic Room's ON DELETE CASCADE for placements + flavors + purchases + photos.
        if (teas.removeAll { it.id == teaId }) {
            placements.removeAll { it.teaId == teaId }
            flavors.removeAll { it.teaId == teaId }
            purchases.removeAll { it.teaId == teaId }
            photos.removeAll { it.teaId == teaId }
            refresh()
        }
    }

    override suspend fun updateTierLabel(tierId: String, label: String) {
        updateTier(tierId) { it.copy(label = label) }
    }

    override suspend fun updateTierColor(tierId: String, colorArgb: Long?) {
        updateTier(tierId) { it.copy(colorArgb = colorArgb) }
    }

    override suspend fun updateTierPosition(tierId: String, position: Int) {
        updateTier(tierId) { it.copy(position = position) }
    }

    override suspend fun deleteTier(tierId: String) {
        if (tiers.removeAll { it.id == tierId }) refresh()
    }

    override suspend fun updateTeaFields(
        teaId: String,
        nameRu: String?,
        nameZh: String?,
        pinyin: String?,
        nameEn: String?,
        type: String,
        origin: String?,
        notes: String?,
    ) {
        val index = teas.indexOfFirst { it.id == teaId }
        if (index >= 0) {
            teas[index] = teas[index].copy(
                nameRu = nameRu,
                nameZh = nameZh,
                pinyin = pinyin,
                nameEn = nameEn,
                type = type,
                origin = origin,
                notes = notes,
            )
            refresh()
        }
    }

    override suspend fun loadTeaRow(teaId: String): TeaSampleEntity? = teas.firstOrNull { it.id == teaId }

    override suspend fun updateEnrichmentState(teaId: String, state: String) {
        val index = teas.indexOfFirst { it.id == teaId }
        if (index >= 0) {
            teas[index] = teas[index].copy(enrichmentState = state)
            refresh()
        }
    }

    override suspend fun patchEnrichment(
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
    ) {
        // v7 dropped the catalogTeaId UNIQUE: many samples may link to one ref, so no collision check.
        val index = teas.indexOfFirst { it.id == teaId }
        if (index >= 0) {
            teas[index] = teas[index].copy(
                nameRu = nameRu,
                nameZh = nameZh,
                pinyin = pinyin,
                nameEn = nameEn,
                type = type,
                origin = origin,
                shortBlurb = shortBlurb,
                catalogTeaId = catalogTeaId,
                enrichmentState = state,
            )
            refresh()
        }
    }

    override suspend fun teasNeedingEnrichment(): List<TeaSampleEntity> =
        teas.filter { it.enrichmentState == "PENDING" || it.enrichmentState == "QUEUED" }

    override suspend fun deleteFlavorsFor(teaId: String) {
        if (flavors.removeAll { it.teaId == teaId }) refresh()
    }

    override suspend fun deletePurchasesFor(teaId: String) {
        if (purchases.removeAll { it.teaId == teaId }) refresh()
    }

    private fun updateTier(tierId: String, transform: (TierEntity) -> TierEntity) {
        val index = tiers.indexOfFirst { it.id == tierId }
        if (index >= 0) {
            tiers[index] = transform(tiers[index])
            refresh()
        }
    }

    private fun refresh() {
        allTeasState.value = teas.map { tea ->
            TeaWithChildren(
                tea = tea,
                flavors = flavors.filter { it.teaId == tea.id },
                purchases = purchases.filter { it.teaId == tea.id },
                photos = photos.filter { it.teaId == tea.id },
            )
        }
        state.value = boards.sortedBy { it.position }.map { board ->
            BoardWithChildren(
                board = board,
                tiers = tiers.filter { it.boardId == board.id },
                placements = placements.filter { it.boardId == board.id }.map { placement ->
                    val tea = teas.firstOrNull { it.id == placement.teaId }
                    val teaWithChildren = if (tea != null) {
                        listOf(
                            TeaWithChildren(
                                tea = tea,
                                flavors = flavors.filter { it.teaId == tea.id },
                                purchases = purchases.filter { it.teaId == tea.id },
                                photos = photos.filter { it.teaId == tea.id },
                            ),
                        )
                    } else {
                        emptyList()
                    }
                    PlacementWithTea(placement = placement, tea = teaWithChildren)
                },
            )
        }
    }
}
