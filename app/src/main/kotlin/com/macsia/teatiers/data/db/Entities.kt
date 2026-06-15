package com.macsia.teatiers.data.db

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Room schema for the local tier-list store (M1). Teas are board-scoped: each placement is its
 * own row keyed by a board-unique id, mirroring the in-memory aggregate the UI already consumes.
 * A shared tea catalog (one tea on many boards) is a later milestone (M2/M4 with the backend).
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

@Entity(
    tableName = "teas",
    foreignKeys = [
        ForeignKey(
            entity = BoardEntity::class,
            parentColumns = ["id"],
            childColumns = ["boardId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("boardId"), Index("tierId")],
)
data class TeaEntity(
    @PrimaryKey val id: String,
    val boardId: String,
    // null => the board's unranked tray; otherwise the owning TierEntity.id.
    val tierId: String?,
    val position: Int,
    val nameRu: String,
    val nameZh: String?,
    val pinyin: String?,
    val nameEn: String?,
    val type: String,
    val origin: String?,
    val shortBlurb: String?,
    val notes: String?,
)

@Entity(
    tableName = "tea_flavors",
    // Composite PK already indexes teaId as its leftmost column, covering the foreign key.
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
