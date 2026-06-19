package com.macsia.teatiers.repository

import com.macsia.teatiers.domain.CatalogMiss
import org.springframework.data.domain.Limit
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param

/** Persistence for the demand-driven miss log (decision #116). */
interface CatalogMissRepository : JpaRepository<CatalogMiss, String> {

    /**
     * Atomically record one miss for [queryNorm]: insert it (count 1) or, if it already exists,
     * bump `miss_count` and refresh `last_seen`. A single statement with `ON CONFLICT`, so two
     * concurrent resolves of the same unknown tea can't race or duplicate (mirrors the catalog
     * enrich-on-miss upsert). `CURRENT_DATE` keeps the log date-granular (no time-of-day → no PII).
     */
    @Modifying
    @Query(
        value = "INSERT INTO catalog_miss (query_norm, miss_count, first_seen, last_seen) " +
            "VALUES (:queryNorm, 1, CURRENT_DATE, CURRENT_DATE) " +
            "ON CONFLICT (query_norm) DO UPDATE " +
            "SET miss_count = catalog_miss.miss_count + 1, last_seen = CURRENT_DATE",
        nativeQuery = true,
    )
    fun recordMiss(@Param("queryNorm") queryNorm: String)

    /** Operator review surface: the most-wanted unresolved teas, highest demand first. */
    fun findAllByOrderByMissCountDesc(limit: Limit): List<CatalogMiss>
}
