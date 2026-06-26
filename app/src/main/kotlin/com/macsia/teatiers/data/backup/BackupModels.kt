package com.macsia.teatiers.data.backup

import com.macsia.teatiers.data.db.BoardEntity
import com.macsia.teatiers.data.db.CatalogRefEntity
import com.macsia.teatiers.data.db.FlavorEntity
import com.macsia.teatiers.data.db.PhotoEntity
import com.macsia.teatiers.data.db.PlacementEntity
import com.macsia.teatiers.data.db.PurchaseLocationEntity
import com.macsia.teatiers.data.db.SeedEntities
import com.macsia.teatiers.data.db.TeaSampleEntity
import com.macsia.teatiers.data.db.TierEntity
import kotlinx.serialization.Serializable

/**
 * Backup bundle format (decisions.md #26). The zip carries one [BACKUP_JSON_ENTRY] plus the photo
 * files under [BACKUP_PHOTO_DIR]. The DTOs are a faithful 1:1 of the DB tables (not the read-side
 * aggregates) so placement positions, tier ids, and child-row order all round-trip; they are kept
 * separate from the Room entities on purpose so a future DB-schema change does not silently break
 * an old backup — [formatVersion] gates compatibility instead.
 *
 * Photos: a file-backed photo stores its bytes in the zip and records [BackupPhoto.bundledFileName]
 * (its entry name); the device-specific absolute path is intentionally dropped. A future URL-backed
 * (catalog) photo would carry [BackupPhoto.uri] and no bundled file.
 *
 * **v2 (tea/sample split, #132):** [BackupTea.nameRu] is nullable (P1-2) and [BackupBundle.catalogRefs]
 * carries the new `catalog_refs` table. Restore is forward/back tolerant via defaults; there is no
 * v1→v2 up-converter (the pre-v7 collection was mock data — owner decision 2026-06-23), but a restore
 * always derives a ref stub for any linked sample so the `catalogTeaId` FK can never dangle (finding #13).
 */
const val BACKUP_FORMAT_VERSION = 2
const val BACKUP_JSON_ENTRY = "backup.json"
const val BACKUP_PHOTO_DIR = "photos/"

@Serializable
data class BackupBundle(
    val formatVersion: Int = BACKUP_FORMAT_VERSION,
    val exportedAtEpochMs: Long = 0L,
    val appVersion: String = "",
    val boards: List<BackupBoard> = emptyList(),
    val tiers: List<BackupTier> = emptyList(),
    val catalogRefs: List<BackupCatalogRef> = emptyList(),
    val teas: List<BackupTea> = emptyList(),
    val placements: List<BackupPlacement> = emptyList(),
    val flavors: List<BackupFlavor> = emptyList(),
    val purchases: List<BackupPurchase> = emptyList(),
    val photos: List<BackupPhoto> = emptyList(),
)

@Serializable
data class BackupCatalogRef(
    val id: Long,
    val type: String,
    // v8 dual-key (#137-C2); defaulted so a pre-v8 bundle restores with a null publicId (re-stamped
    // lazily on the next enrichment — the Long id is the fallback meanwhile).
    val catalogPublicId: String? = null,
    val wikidataQid: String? = null,
    val originCountry: String? = null,
    val region: String? = null,
    val cultivar: String? = null,
    val oxidationMin: Int? = null,
    val oxidationMax: Int? = null,
    val brand: String? = null,
    val verificationStatus: String? = null,
    val confidence: Double? = null,
    val enrichmentState: String? = null,
    val shortBlurb: String? = null,
    val source: String? = null,
    val sourceUrl: String? = null,
    val license: String? = null,
    val fetchedAtEpochMs: Long = 0L,
)

@Serializable
data class BackupBoard(val id: String, val name: String, val position: Int)

@Serializable
data class BackupTier(
    val id: String,
    val boardId: String,
    val label: String,
    val position: Int,
    val colorArgb: Long? = null,
)

@Serializable
data class BackupTea(
    val id: String,
    val nameRu: String? = null,
    val nameZh: String? = null,
    val pinyin: String? = null,
    val nameEn: String? = null,
    val type: String,
    val origin: String? = null,
    val shortBlurb: String? = null,
    val notes: String? = null,
    // Catalog linkage + enrichment lifecycle (Room v5, decisions.md #69) — defaulted so a pre-v5
    // backup restores as an un-enriched custom tea (null / NONE), which is its correct state.
    val catalogTeaId: Long? = null,
    val enrichmentState: String = "NONE",
)

@Serializable
data class BackupPlacement(
    val id: String,
    val boardId: String,
    val teaId: String,
    val tierId: String? = null,
    val position: Int,
)

@Serializable
data class BackupFlavor(val teaId: String, val dimension: String, val intensity: Int, val position: Int)

@Serializable
data class BackupPurchase(
    val id: String,
    val teaId: String,
    val position: Int,
    val kind: String,
    val label: String? = null,
    val value: String,
)

@Serializable
data class BackupPhoto(
    val id: String,
    val teaId: String,
    val position: Int,
    val source: String,
    val license: String? = null,
    val sourceUrl: String? = null,
    val createdAtEpochMs: Long = 0L,
    // Entry name inside the zip's photos/ dir for a file-backed photo; null for a URL-backed one.
    val bundledFileName: String? = null,
    // Original URL for a URL-backed photo; null when the bytes are bundled.
    val uri: String? = null,
)

/** True for an on-disk (USER) photo whose bytes must travel in the zip. */
private fun PhotoEntity.isFileBacked(): Boolean = uri.startsWith("/")

/** Stable zip entry name for a bundled photo: `<photoId>.<ext-from-path>` (defaults to jpg). */
fun PhotoEntity.bundledFileName(): String {
    val ext = uri.substringAfterLast('.', "").let { if (it.isEmpty() || it.length > 5) "jpg" else it }
    return "$id.$ext"
}

/**
 * Builds the JSON model from a DB snapshot. File-backed photos get a [BackupPhoto.bundledFileName]
 * (and drop their device path); URL-backed photos keep their [BackupPhoto.uri].
 */
fun SeedEntities.toBundle(exportedAtEpochMs: Long, appVersion: String): BackupBundle = BackupBundle(
    formatVersion = BACKUP_FORMAT_VERSION,
    exportedAtEpochMs = exportedAtEpochMs,
    appVersion = appVersion,
    boards = boards.map { BackupBoard(it.id, it.name, it.position) },
    tiers = tiers.map { BackupTier(it.id, it.boardId, it.label, it.position, it.colorArgb) },
    catalogRefs = catalogRefs.map {
        BackupCatalogRef(
            it.id, it.type, it.catalogPublicId, it.wikidataQid, it.originCountry, it.region, it.cultivar,
            it.oxidationMin, it.oxidationMax, it.brand, it.verificationStatus, it.confidence,
            it.enrichmentState, it.shortBlurb, it.source, it.sourceUrl, it.license, it.fetchedAtEpochMs,
        )
    },
    teas = teas.map {
        BackupTea(
            it.id, it.nameRu, it.nameZh, it.pinyin, it.nameEn, it.type, it.origin, it.shortBlurb, it.notes,
            it.catalogTeaId, it.enrichmentState,
        )
    },
    placements = placements.map { BackupPlacement(it.id, it.boardId, it.teaId, it.tierId, it.position) },
    flavors = flavors.map { BackupFlavor(it.teaId, it.dimension, it.intensity, it.position) },
    purchases = purchases.map { BackupPurchase(it.id, it.teaId, it.position, it.kind, it.label, it.value) },
    photos = photos.map { photo ->
        val fileBacked = photo.isFileBacked()
        BackupPhoto(
            id = photo.id,
            teaId = photo.teaId,
            position = photo.position,
            source = photo.source,
            license = photo.license,
            sourceUrl = photo.sourceUrl,
            createdAtEpochMs = photo.createdAtEpochMs,
            bundledFileName = if (fileBacked) photo.bundledFileName() else null,
            uri = if (fileBacked) null else photo.uri,
        )
    },
)

/** The bundled-photo entry names a backup expects to find in the zip (for the manager to extract). */
fun BackupBundle.bundledPhotoNames(): List<String> = photos.mapNotNull { it.bundledFileName }

/**
 * Rebuilds DB rows from the bundle. [restoredPaths] maps a bundled file's entry name to the
 * absolute path it was just written to on this device. A bundled photo whose file is missing from
 * [restoredPaths] (corrupt/partial zip) is dropped rather than inserted with a dangling path; a
 * URL-backed photo keeps its [BackupPhoto.uri]. Pure: no Android, no I/O.
 */
fun BackupBundle.toSeedEntities(restoredPaths: Map<String, String>): SeedEntities {
    val teaRows = teas.map {
        TeaSampleEntity(
            it.id, it.nameRu, it.nameZh, it.pinyin, it.nameEn, it.type, it.origin, it.shortBlurb, it.notes,
            it.catalogTeaId, it.enrichmentState,
        )
    }
    // Restore the bundle's refs, then union in a stub for any linked sample whose ref is missing from
    // the bundle (old/partial bundle), so the catalogTeaId FK can never dangle on restore (finding #13).
    val bundledRefIds = catalogRefs.map { it.id }.toSet()
    val refTypeById = teaRows.filter { it.catalogTeaId != null }.associate { it.catalogTeaId!! to it.type }
    val refRows = catalogRefs.map {
        CatalogRefEntity(
            it.id, it.type, it.catalogPublicId, it.wikidataQid, it.originCountry, it.region, it.cultivar,
            it.oxidationMin, it.oxidationMax, it.brand, it.verificationStatus, it.confidence,
            it.enrichmentState, it.shortBlurb, it.source, it.sourceUrl, it.license, it.fetchedAtEpochMs,
        )
    } + refTypeById.filterKeys { it !in bundledRefIds }
        .map { (id, type) -> CatalogRefEntity(id = id, type = type, fetchedAtEpochMs = 0L) }
    return SeedEntities(
        boards = boards.map { BoardEntity(it.id, it.name, it.position) },
        tiers = tiers.map { TierEntity(it.id, it.boardId, it.label, it.position, it.colorArgb) },
        catalogRefs = refRows,
        teas = teaRows,
        placements = placements.map { PlacementEntity(it.id, it.boardId, it.teaId, it.tierId, it.position) },
        // Dedup on the (teaId, dimension) composite PK: a malformed/hand-edited bundle carrying two
        // rows for the same axis would otherwise abort the whole replaceAll transaction (finding #15).
        flavors = flavors.distinctBy { it.teaId to it.dimension }
            .map { FlavorEntity(it.teaId, it.dimension, it.intensity, it.position) },
        purchases = purchases.map { PurchaseLocationEntity(it.id, it.teaId, it.position, it.kind, it.label, it.value) },
        photos = photos.mapNotNull { photo ->
            val resolvedUri = when {
                photo.bundledFileName != null -> restoredPaths[photo.bundledFileName] ?: return@mapNotNull null
                else -> photo.uri ?: return@mapNotNull null
            }
            PhotoEntity(
                id = photo.id,
                teaId = photo.teaId,
                uri = resolvedUri,
                position = photo.position,
                source = photo.source,
                license = photo.license,
                sourceUrl = photo.sourceUrl,
                createdAtEpochMs = photo.createdAtEpochMs,
            )
        },
    )
}
