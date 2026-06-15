package com.macsia.teatiers.data.db

import androidx.room.Database
import androidx.room.RoomDatabase

/**
 * exportSchema = false for the initial schema: there are no migrations yet, so there is nothing
 * to diff against. Flip it on (with a room.schemaLocation and committed JSON) when the schema
 * first changes, so migration tests have a baseline.
 */
@Database(
    entities = [
        BoardEntity::class,
        TierEntity::class,
        TeaEntity::class,
        FlavorEntity::class,
        PurchaseLocationEntity::class,
    ],
    version = 1,
    exportSchema = false,
)
abstract class TeaDatabase : RoomDatabase() {
    abstract fun teaDao(): TeaDao
}
