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
 * The user-tea pool. After the shared-teas reopening this row is no longer tied to any
 * particular board — placement is tracked separately in [PlacementEntity]. [shortBlurb] stays
 * here because it is catalog/AI-derived (decisions.md #25), not user-typed.
 *
 * [catalogTeaId] links the tea to its shared-catalog row once resolved (#21); [enrichmentState]
 * holds the [com.macsia.teatiers.domain.model.EnrichmentState] name driving the optimistic
 * background enrichment status + retry (#28). Both default for custom/seed teas.
 */
@Entity(tableName = "teas")
data class TeaEntity(
    @PrimaryKey val id: String,
    val nameRu: String,
    val nameZh: String?,
    val pinyin: String?,
    val nameEn: String?,
    val type: String,
    val origin: String?,
    val shortBlurb: String?,
    val notes: String?,
    val catalogTeaId: Long? = null,
    val enrichmentState: String = "NONE",
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
            entity = TeaEntity::class,
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
            entity = TeaEntity::class,
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
            entity = TeaEntity::class,
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
            entity = TeaEntity::class,
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
