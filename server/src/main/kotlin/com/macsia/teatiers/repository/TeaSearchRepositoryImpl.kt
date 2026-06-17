package com.macsia.teatiers.repository

import com.macsia.teatiers.domain.Tea
import com.macsia.teatiers.domain.TeaName
import com.macsia.teatiers.domain.TeaType
import jakarta.persistence.EntityManager
import jakarta.persistence.criteria.Predicate

class TeaSearchRepositoryImpl(
    private val entityManager: EntityManager,
) : TeaSearchRepository {

    override fun searchIds(
        q: String?,
        locale: String?,
        type: TeaType?,
        origin: String?,
        cursor: Long?,
        limit: Int,
    ): List<Long> {
        val text = q?.trim()?.takeIf { it.isNotEmpty() }
        return if (text == null) {
            browseIds(locale, type, origin, cursor, limit)
        } else {
            fuzzyIds(text, locale, type, origin, limit)
        }
    }

    /** Blank-query browse: every tea (filtered) ordered by id, keyset-paginated by [cursor]. */
    private fun browseIds(
        locale: String?,
        type: TeaType?,
        origin: String?,
        cursor: Long?,
        limit: Int,
    ): List<Long> {
        val cb = entityManager.criteriaBuilder
        val query = cb.createQuery(Long::class.javaObjectType)
        val tea = query.from(Tea::class.java)
        val name = tea.join<Tea, TeaName>("names")

        val predicates = buildList {
            locale?.takeIf { it.isNotBlank() }?.let { add(cb.equal(name.get<String>("locale"), it)) }
            type?.let { add(cb.equal(tea.get<TeaType>("type"), it)) }
            origin?.takeIf { it.isNotBlank() }?.let { add(cb.equal(tea.get<String>("originCountry"), it)) }
            cursor?.let { add(cb.greaterThan(tea.get("id"), it)) }
        }

        query.select(tea.get("id"))
            .distinct(true)
            .where(*predicates.toTypedArray<Predicate>())
            .orderBy(cb.asc(tea.get<Long>("id")))

        return entityManager.createQuery(query).setMaxResults(limit).resultList
    }

    /**
     * Typo-tolerant search (decision #79). Matches a tea when any of its names is trigram-similar
     * (`<%`, GIN-indexed on `name_norm`) OR contains the normalized query as a literal substring
     * (`strpos`, which also covers exact CJK / sub-3-char queries that trigrams handle poorly).
     * Ranked by word-similarity, then full-string `similarity` (so an exact short name outranks the
     * same word inside a longer one), then id. Returns a single best-match page (no keyset cursor):
     * ranked results don't id-paginate cleanly.
     */
    private fun fuzzyIds(
        q: String,
        locale: String?,
        type: TeaType?,
        origin: String?,
        limit: Int,
    ): List<Long> {
        // Lower the word-similarity threshold transaction-locally so `<%` tolerates typos
        // (its session default is 0.6). set_config(..., is_local=true) is the ORM-safe SET LOCAL:
        // a plain SELECT, so no native-DML ambiguity, and it is reset at transaction end.
        entityManager.createNativeQuery("select set_config('pg_trgm.word_similarity_threshold', :t, true)")
            .setParameter("t", WORD_SIMILARITY_THRESHOLD.toString())
            .singleResult

        val sql = buildString {
            append(
                """
                select t.id
                from tea t join tea_name n on n.tea_id = t.id
                where (lower(f_unaccent(:q)) <% n.name_norm
                       or strpos(n.name_norm, lower(f_unaccent(:q))) > 0)
                """.trimIndent(),
            )
            if (locale != null) append("\n  and n.locale = :locale")
            if (type != null) append("\n  and t.type = :type")
            if (origin != null) append("\n  and t.origin_country = :origin")
            append(
                """

                group by t.id
                order by max(word_similarity(lower(f_unaccent(:q)), n.name_norm)) desc,
                         max(similarity(lower(f_unaccent(:q)), n.name_norm)) desc,
                         t.id
                limit :limit
                """.trimIndent(),
            )
        }

        val query = entityManager.createNativeQuery(sql)
            .setParameter("q", q)
            .setParameter("limit", limit)
        locale?.let { query.setParameter("locale", it) }
        type?.let { query.setParameter("type", it.name) }
        origin?.let { query.setParameter("origin", it) }

        @Suppress("UNCHECKED_CAST")
        return (query.resultList as List<Number>).map { it.toLong() }
    }

    private companion object {
        /**
         * pg_trgm word-similarity floor for the `<%` filter. Tuned on the run-09 ru/en/pinyin gold
         * set: the hardest case (a 1-char substitution in a short word, "улонг" -> "Улун") scores
         * 0.333, so 0.3 keeps full recall; raising it drops that class. See TeaSearchFuzzyIT.
         */
        const val WORD_SIMILARITY_THRESHOLD = 0.3
    }
}
