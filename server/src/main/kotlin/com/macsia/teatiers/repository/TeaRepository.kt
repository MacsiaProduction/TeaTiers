package com.macsia.teatiers.repository

import com.macsia.teatiers.domain.Tea
import com.macsia.teatiers.domain.TeaType
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param

interface TeaRepository : JpaRepository<Tea, Long>, TeaSearchRepository {

    /** Cross-source identity lookup; used by the seed/enrich upsert to avoid duplicate rows. */
    fun findByWikidataQid(wikidataQid: String): Tea?

    /** Normalized dedup-key lookup backing the section 6 enrich-on-miss upsert. */
    fun findByDedupKey(dedupKey: String): Tea?

    /**
     * `/resolve` cache hit: the lowest id whose name in any locale equals [q] once unaccented and
     * lowercased. `unaccent` (enabled in V1) folds Latin diacritics (e.g. pinyin tone marks); it is
     * a no-op for Cyrillic/CJK. Keeps the second user who types a known tea from re-enriching it.
     */
    @Query(
        value = "SELECT t.id FROM tea t JOIN tea_name n ON n.tea_id = t.id " +
            "WHERE lower(unaccent(n.name)) = lower(unaccent(:q)) ORDER BY t.id LIMIT 1",
        nativeQuery = true,
    )
    fun findIdByNormalizedName(@Param("q") q: String): Long?

    @Query("select distinct t from Tea t left join fetch t.names where t.id in :ids order by t.id")
    fun findAllWithNames(@Param("ids") ids: Collection<Long>): List<Tea>

    @Query("select distinct t.type from Tea t order by t.type")
    fun distinctTypes(): List<TeaType>

    @Query("select distinct t.originCountry from Tea t where t.originCountry is not null order by t.originCountry")
    fun distinctOrigins(): List<String>
}
