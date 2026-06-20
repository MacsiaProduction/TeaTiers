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
 * invariant (NULLs stay distinct → custom teas unaffected).
 *
 * **v6 is the public migration baseline (decision #130 / review P0-1).** Now that v0.1.0 ships
 * publicly, `exportSchema = true` and `app/schemas/.../6.json` is committed so future migrations have
 * a JSON to diff against, and the prod build NO LONGER destructively migrates ([com.macsia.teatiers.di.AppModule])
 * — a missing `Migration(6, N)` must fail loudly rather than silently wipe a user's only local copy.
 * Every version after 6 needs an explicit `Migration` + a `MigrationTestHelper` upgrade test.
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
    exportSchema = true,
)
abstract class TeaDatabase : RoomDatabase() {
    abstract fun teaDao(): TeaDao
    abstract fun catalogDao(): CatalogDao
}
