package com.macsia.teatiers.data.db

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Upsert

/**
 * Local cache of catalog search results (plan §4b `catalog_cache`), so a tea the user already saw
 * stays searchable offline. Separate from the user-data tables (it holds shared, read-only catalog
 * rows, not the user's teas). [searchText] is a pre-lowercased, space-joined dump of every name so
 * the offline fallback can do a single case-insensitive LIKE without a names sub-table; [namesJson]
 * carries the structured names back. Destructive schema bumps drop it like the rest (pre-launch).
 */
@Entity(tableName = "catalog_cache")
data class CatalogCacheEntity(
    @PrimaryKey val id: Long,
    val type: String,
    val originCountry: String?,
    val brand: String?,
    val verificationStatus: String,
    val namesJson: String,
    val searchText: String,
    val fetchedAtEpochMs: Long,
)

@Dao
interface CatalogDao {

    @Upsert
    suspend fun upsertAll(rows: List<CatalogCacheEntity>)

    /**
     * Offline fallback: rows whose joined names contain [query] (already lowercased AND LIKE-escaped by the
     * caller), most-recently-fetched first. Both sides are pre-lowercased so Cyrillic/CJK match too (SQLite
     * `LIKE` only case-folds ASCII). `ESCAPE '\'` (AND-P2-1) makes the caller's `\%`/`\_` match a literal
     * `%`/`_` instead of treating user-typed wildcards as wildcards (a query of `%` matching everything).
     * [type] (the wire enum name, e.g. "OOLONG") narrows to one category when set; null means every type —
     * without this, an active type filter would silently vanish for the offline fallback (review).
     */
    @Query(
        "SELECT * FROM catalog_cache WHERE searchText LIKE '%' || :query || '%' ESCAPE '\\' " +
            "AND (:type IS NULL OR type = :type) " +
            "ORDER BY fetchedAtEpochMs DESC LIMIT :limit",
    )
    suspend fun search(query: String, type: String?, limit: Int): List<CatalogCacheEntity>
}
