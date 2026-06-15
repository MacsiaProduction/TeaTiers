package com.macsia.teatiers.data.db

import androidx.room.Database
import androidx.room.RoomDatabase

/**
 * v2 (shared-teas reopening, decisions.md #42): teas are user-global, a new `placements` table
 * carries per-board placement. The schema bump is destructive: callers configure
 * `fallbackToDestructiveMigration` on the builder so the v1 data is dropped on first launch
 * and the sample provider reseeds. Acceptable pre-launch (#42); a real Migration(1, 2) becomes
 * mandatory the moment we ship to a real user.
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
    ],
    version = 2,
    exportSchema = false,
)
abstract class TeaDatabase : RoomDatabase() {
    abstract fun teaDao(): TeaDao
}
