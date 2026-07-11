package com.macsia.teatiers.data.db

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Room schema for the local store. After the shared-teas reopening (decisions.md #42) teas are
 * **user-global**: each [TeaEntity] is one user-tea, and a separate [PlacementEntity] tracks
 * "this tea sits on this board in this tier at this slot". The same user-tea can therefore
 * appear on N boards via N placements, while edits to its name/notes/flavor/purchases ripple
 * to every board automatically.
 *
 * Schema bump from v1 (board-scoped teas) is destructive (decisions.md #42): on first launch
 * after upgrade Room drops everything and the [com.macsia.teatiers.data.sample.SampleBoardProvider]
 * reseeds. Acceptable while we are pre-launch and the only real state is sample data.
 *
 * Enum-valued columns hold the enum `name` as text (decisions.md #10/#23) and are converted in
 * the mappers, so no TypeConverter is needed and a renamed enum fails loudly at mapping time.
 */
@Entity(tableName = "boards")
data class BoardEntity(
    @PrimaryKey val id: String,
    val name: String,
    val position: Int,
    // v9 (R4-F-1): wall-clock ms the board was created; nullable — pre-v9 rows and seed data carry null.
    val createdAtEpochMs: Long? = null,
)

@Entity(
    tableName = "tiers",
    foreignKeys = [
        ForeignKey(
            entity = BoardEntity::class,
            parentColumns = ["id"],
            childColumns = ["boardId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("boardId")],
)
data class TierEntity(
    @PrimaryKey val id: String,
    val boardId: String,
    val label: String,
    val position: Int,
    // null => default tea-toned ramp resolved by position in the UI (decisions.md #6).
    val colorArgb: Long?,
)

/**
 * The user's physical **sample** (v7 tea/sample split, decision #132). One v6 `teas` row became one
 * `tea_samples` row; the canonical catalog identity moved to [CatalogRefEntity]. After the
 * shared-teas reopening this row is not tied to any board — placement is tracked separately in
 * [PlacementEntity]. [shortBlurb] stays here because it is catalog/AI-derived (decisions.md #25).
 *
 * **P1-1:** [catalogTeaId] is now a *nullable FK* → [CatalogRefEntity.id] (`ON DELETE SET NULL`) with
 * **no UNIQUE** — many samples may link to one catalog ref (the v6 one-per-ref invariant is gone), so
 * a user can keep two physical samples of the same catalog tea with independent notes/flavor/photos.
 * Evicting a ref never deletes a sample. (Working-core: the auto-reuse policy + "add another sample"
 * UX still ride a later slice; the schema no longer blocks them.)
 *
 * **P1-2:** [nameRu] is nullable — a sample is valid with ≥1 non-blank name in any locale
 * (ru/zh/pinyin/en); the display title is resolved by priority (see `Tea.displayName`).
 *
 * [vendor]/[product]/[harvestYear]/[batch]/[grade]/[displayNamePref] are the v7 sample-identity
 * columns (disambiguate two samples of one ref); they exist now for schema completeness — the add/edit
 * form wiring rides the deferred UX slice, so they stay null until then. [enrichmentState] holds the
 * [com.macsia.teatiers.domain.model.EnrichmentState] name driving optimistic background enrichment (#28).
 */
@Entity(
    tableName = "tea_samples",
    foreignKeys = [
        ForeignKey(
            entity = CatalogRefEntity::class,
            parentColumns = ["id"],
            childColumns = ["catalogTeaId"],
            onDelete = ForeignKey.SET_NULL,
        ),
    ],
    indices = [Index("catalogTeaId")],
)
data class TeaSampleEntity(
    @PrimaryKey val id: String,
    val nameRu: String?,
    val nameZh: String?,
    val pinyin: String?,
    val nameEn: String?,
    val type: String,
    val origin: String?,
    val shortBlurb: String?,
    val notes: String?,
    val catalogTeaId: Long? = null,
    val enrichmentState: String = "NONE",
    val vendor: String? = null,
    val product: String? = null,
    val harvestYear: Int? = null,
    val batch: String? = null,
    val grade: String? = null,
    val displayNamePref: String? = null,
    // v9 (R4-F-1): wall-clock ms the sample was first added; nullable — pre-v9 rows and seed data
    // carry null. Drives the My Teas "recently added" sort.
    val createdAtEpochMs: Long? = null,
)

/**
 * Cached canonical catalog reference (v7, decision #132). Read-only facts mirrored from the server
 * catalog; the user never owns it. A row is OPTIONAL — custom samples have none. At seed/link time
 * only [id] + [type] are populated (a stub); the rest is backfilled by the catalog-refresh writer.
 *
 * **v8 dual-key (#137-C2):** [id] = server `tea.id` (Long) stays the primary key and the legacy
 * fallback, but it is volatile across server DB rebuilds. [catalogPublicId] is the durable UUID the
 * server emits (Step A decodes it); it's nullable and lazily backfilled — stamped onto the ref the
 * next time its detail is fetched (see [com.macsia.teatiers.data.repository.TeaEnrichmentManager]),
 * so old refs carry null until then. NOT unique: many samples can share a ref, and an old + a
 * rebuilt-id ref may briefly co-exist before reconciliation.
 */
@Entity(tableName = "catalog_refs")
data class CatalogRefEntity(
    @PrimaryKey val id: Long,
    val type: String,
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
    val fetchedAtEpochMs: Long,
)

/**
 * One tea on one board. [tierId] is null when the placement sits in the unranked tray; it has
 * **no FK to tiers** on purpose (the tier editor's removeTier reassigns placements to the tray
 * inside one transaction — decisions.md #39 — so a cascade would orphan teas off-board).
 *
 * UNIQUE(boardId, teaId) enforces the brainstorm invariant: the same user-tea cannot be
 * declared twice on the same board (decisions.md #42).
 */
@Entity(
    tableName = "placements",
    foreignKeys = [
        ForeignKey(
            entity = BoardEntity::class,
            parentColumns = ["id"],
            childColumns = ["boardId"],
            onDelete = ForeignKey.CASCADE,
        ),
        ForeignKey(
            entity = TeaSampleEntity::class,
            parentColumns = ["id"],
            childColumns = ["teaId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [
        Index(value = ["boardId", "teaId"], unique = true),
        Index("teaId"),
        Index("tierId"),
    ],
)
data class PlacementEntity(
    @PrimaryKey val id: String,
    val boardId: String,
    val teaId: String,
    val tierId: String?,
    val position: Int,
)

@Entity(
    tableName = "tea_flavors",
    primaryKeys = ["teaId", "dimension"],
    foreignKeys = [
        ForeignKey(
            entity = TeaSampleEntity::class,
            parentColumns = ["id"],
            childColumns = ["teaId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
)
data class FlavorEntity(
    val teaId: String,
    val dimension: String,
    val intensity: Int,
    val position: Int,
)

@Entity(
    tableName = "purchase_locations",
    foreignKeys = [
        ForeignKey(
            entity = TeaSampleEntity::class,
            parentColumns = ["id"],
            childColumns = ["teaId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("teaId")],
)
data class PurchaseLocationEntity(
    @PrimaryKey val id: String,
    val teaId: String,
    val position: Int,
    // "URL" (marketplace link) or "TEXT" (free text) per decisions.md #20; no map geopoint in MVP.
    val kind: String,
    val label: String?,
    val value: String,
)

/**
 * One photo attached to a user-tea (decisions.md #43; supersedes #24's single-photo line).
 *
 * - `uri` is an absolute file path under the app's private dir for `source = "USER"` (the only
 *   source wired in MVP); `PhotoStore` copies bytes there so the URI keeps working through
 *   gallery cleanup and the export bundle (#26) is just a directory copy. Future "CATALOG"
 *   photos store an HTTPS URL.
 * - `position` is per-tea contiguous (0..n); reorder rewrites every row in one transaction
 *   (mirrors decisions.md #38/#39).
 * - `license` / `sourceUrl` stay null for user uploads and exist now so a future CC catalog
 *   image fits the same row without another migration (decisions.md #43).
 */
@Entity(
    tableName = "tea_photos",
    foreignKeys = [
        ForeignKey(
            entity = TeaSampleEntity::class,
            parentColumns = ["id"],
            childColumns = ["teaId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("teaId")],
)
data class PhotoEntity(
    @PrimaryKey val id: String,
    val teaId: String,
    val uri: String,
    val position: Int,
    val source: String,
    val license: String?,
    val sourceUrl: String?,
    val createdAtEpochMs: Long,
)
