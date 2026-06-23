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
 * **v7 (tea/sample split, decision #132):** `teas` → [TeaSampleEntity] (`tea_samples`); the canonical
 * catalog identity moves to [CatalogRefEntity] (`catalog_refs`); the v6 `UNIQUE(catalogTeaId)` is gone
 * so many samples may share one ref (P1-1), and `nameRu` is nullable (P1-2). **No `Migration(6,7)`:**
 * per owner decision (2026-06-23) the existing collection is mock data, so v6→v7 is a one-time
 * *destructive* reset + reseed ([com.macsia.teatiers.di.AppModule] enables `fallbackToDestructiveMigration`
 * in release for this bump) rather than a lossless migration. v7 is the new schema baseline; once a
 * real collection lands, the next bump goes back to an explicit `Migration(7, N)` + a test.
 */
@Database(
    entities = [
        BoardEntity::class,
        TierEntity::class,
        TeaSampleEntity::class,
        CatalogRefEntity::class,
        PlacementEntity::class,
        FlavorEntity::class,
        PurchaseLocationEntity::class,
        PhotoEntity::class,
        CatalogCacheEntity::class,
    ],
    version = 7,
    exportSchema = true,
)
abstract class TeaDatabase : RoomDatabase() {
    abstract fun teaDao(): TeaDao
    abstract fun catalogDao(): CatalogDao
}
