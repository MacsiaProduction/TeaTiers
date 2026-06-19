package com.macsia.teatiers.service

import com.macsia.teatiers.domain.CatalogMiss
import com.macsia.teatiers.repository.CatalogMissRepository
import org.springframework.data.domain.Limit
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

/**
 * The demand-driven catalog-growth engine (decision #116, research run 16): record the aggregate,
 * name-string-only set of `/resolve` misses so the operator can curate the most-wanted teas into the
 * `verified` seed. [record] is best-effort — callers ([ResolveService]) wrap it so a logging failure
 * can never break a resolve — and writes via an atomic upsert in its own transaction.
 *
 * No-PII by construction: only the normalized query string is stored (see [normalize]); nothing here
 * touches IP, session, device id, or time-of-day.
 */
@Service
class MissLogService(
    private val repository: CatalogMissRepository,
) {

    /** Record one resolve miss for [rawName]. A blank/normalized-empty name is ignored. */
    @Transactional
    fun record(rawName: String) {
        val norm = normalize(rawName)
        if (norm.isEmpty()) return
        repository.recordMiss(norm)
    }

    /** Operator review: the [limit] most-requested unresolved teas, highest demand first. */
    @Transactional(readOnly = true)
    fun topMisses(limit: Int = DEFAULT_TOP): List<CatalogMiss> =
        repository.findAllByOrderByMissCountDesc(Limit.of(limit.coerceIn(1, MAX_TOP)))

    /**
     * Group case/whitespace variants of the same query without mangling readability: trim, collapse
     * internal whitespace, lowercase, and cap the length. Deliberately does NOT strip diacritics
     * (unlike the catalog dedup key) so Cyrillic `ё`/`й` survive and the operator can read the row.
     *
     * The cap counts **code points**, not UTF-16 units: a plain `take(MAX_LEN)` can cut between a
     * surrogate pair (lowercase() can expand length and push an astral char onto the boundary),
     * leaving a lone surrogate that isn't valid UTF-8 and would be rejected by the `text` column.
     */
    private fun normalize(value: String): String {
        val collapsed = value.trim().replace(WHITESPACE, " ").lowercase()
        if (collapsed.codePointCount(0, collapsed.length) <= MAX_LEN) return collapsed
        return collapsed.substring(0, collapsed.offsetByCodePoints(0, MAX_LEN))
    }

    private companion object {
        val WHITESPACE = Regex("\\s+")
        const val MAX_LEN = 200
        const val DEFAULT_TOP = 50
        const val MAX_TOP = 500
    }
}
