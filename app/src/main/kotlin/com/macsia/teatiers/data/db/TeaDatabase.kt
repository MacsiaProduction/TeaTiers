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
    ],
    version = 3,
    exportSchema = false,
)
abstract class TeaDatabase : RoomDatabase() {
    abstract fun teaDao(): TeaDao
}
