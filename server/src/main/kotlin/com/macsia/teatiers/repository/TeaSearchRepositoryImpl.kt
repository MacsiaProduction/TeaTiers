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
        val cb = entityManager.criteriaBuilder
        val query = cb.createQuery(Long::class.javaObjectType)
        val tea = query.from(Tea::class.java)
        val name = tea.join<Tea, TeaName>("names")

        val predicates = buildList {
            q?.trim()?.takeIf { it.isNotEmpty() }?.let {
                add(cb.like(cb.lower(name.get("name")), "%${escapeLike(it.lowercase())}%", LIKE_ESCAPE))
            }
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

    /** Treat the user query as a literal: escape the LIKE wildcards so `%`/`_` don't act as patterns. */
    private fun escapeLike(input: String): String = input
        .replace("\\", "\\\\")
        .replace("%", "\\%")
        .replace("_", "\\_")

    private companion object {
        const val LIKE_ESCAPE = '\\'
    }
}
