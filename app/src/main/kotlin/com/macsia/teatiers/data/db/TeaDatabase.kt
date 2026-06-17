package com.macsia.teatiers.data.db

import androidx.room.Database
import androidx.room.RoomDatabase

/**
 * v3 (photos reopening, decisions.md #43): adds a `tea_photos` table for the per-user-tea
 * photo list. Like v1→v2 (#42), v2→v3 is destructive: callers configure
 * `fallbackToDestructiveMigration` on the builder so the older data is dropped on first
 * launch and the sample provider reseeds. Acceptable pre-launch; a real Migration(2, 3)
 * (and the still-pending Migration(1, 2)) become mandatory the moment we ship to a real user.
 *
 * v4 (catalog integration, M3): adds the `catalog_cache` table — read-only shared-catalog rows
 * cached for offline search reuse (plan §4b). Still destructive on bump (pre-launch).
 *
 * v5 (M4 enrichment): adds `teas.catalogTeaId` + `teas.enrichmentState` for the optimistic
 * background catalog enrichment (#21/#28). Still destructive on bump (pre-launch).
 *
 * v6 (second-pass review): UNIQUE index on `teas.catalogTeaId` so catalog identity is a schema-level
 * invariant (NULLs stay distinct → custom teas unaffected). Still destructive (pre-launch).
 *
 * exportSchema stays false until we ship to real users — at that point flip it on with
 * room.schemaLocation + a committed JSON baseline so migration tests have something to diff.
 */
@Database(
    entities = [
        BoardEntity::class,
        TierEntity::class,
        TeaEntity::class,
        PlacementEntity::class,
        FlavorEntity::class,
        PurchaseLocationEntity::class,
        PhotoEntity::class,
        CatalogCacheEntity::class,
    ],
    version = 6,
    exportSchema = false,
)
abstract class TeaDatabase : RoomDatabase() {
    abstract fun teaDao(): TeaDao
    abstract fun catalogDao(): CatalogDao
}
