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

    @Query("select distinct t from Tea t left join fetch t.names where t.id in :ids order by t.id")
    fun findAllWithNames(@Param("ids") ids: Collection<Long>): List<Tea>

    @Query("select distinct t.type from Tea t order by t.type")
    fun distinctTypes(): List<TeaType>

    @Query("select distinct t.originCountry from Tea t where t.originCountry is not null order by t.originCountry")
    fun distinctOrigins(): List<String>
}
