package com.macsia.teatiers.repository

import com.macsia.teatiers.domain.TeaType

/**
 * Custom fragment for the dynamic catalog search. The blank-query browse is built with the Criteria
 * API so that null filters are simply omitted (the `:param is null or ...` JPQL pattern fails on
 * PostgreSQL when a parameter is null — an untyped null bind is inferred as `bytea`); the fuzzy path
 * is a native pg_trgm query whose optional filters are appended only when present.
 */
interface TeaSearchRepository {

    /**
     * Tea ids for a search. Two modes (decision #79):
     * - Blank `q`: browse — every tea (filtered) ordered by id, keyset-paginated by `cursor`
     *   (the last id of the previous page; null for the first).
     * - Non-blank `q`: typo-tolerant fuzzy match (pg_trgm) — a tea matches when any of its names is
     *   trigram-similar to `q` or contains it as a substring. Results are RANK-ordered (best first)
     *   and capped to a single page of `limit`; `cursor` is ignored (ranked results don't
     *   id-paginate cleanly).
     *
     * Optionally restricted to a single name locale and/or filtered by type and origin. Returns ids
     * only so pagination stays clean (no collection fetch); for the fuzzy mode the returned order is
     * significant — the caller must preserve it.
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
