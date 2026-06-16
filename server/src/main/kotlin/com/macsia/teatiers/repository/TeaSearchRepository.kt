package com.macsia.teatiers.repository

import com.macsia.teatiers.domain.TeaType

/**
 * Custom fragment for the dynamic catalog search. Built with the Criteria API so that null filters
 * are simply omitted: the `:param is null or ...` JPQL pattern fails on PostgreSQL when a parameter
 * is null (an untyped null bind is inferred as `bytea`, breaking `lower(...)`/concat).
 */
interface TeaSearchRepository {

    /**
     * Keyset page of matching tea ids ordered by id. A tea matches when any of its names contains
     * `q` (case-insensitive substring), optionally restricted to a single name locale and/or
     * filtered by type and origin. `cursor` is the last id from the previous page (null for the
     * first). Returns ids only so pagination stays clean (no collection fetch).
     */
    fun searchIds(
        q: String?,
        locale: String?,
        type: TeaType?,
        origin: String?,
        cursor: Long?,
        limit: Int,
    ): List<Long>
}
