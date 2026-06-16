package com.macsia.teatiers.repository

import com.macsia.teatiers.domain.Tea
import org.springframework.data.jpa.repository.JpaRepository

interface TeaRepository : JpaRepository<Tea, Long> {

    /** Cross-source identity lookup; used by the seed/enrich upsert to avoid duplicate rows. */
    fun findByWikidataQid(wikidataQid: String): Tea?

    /** Normalized dedup-key lookup backing the section 6 enrich-on-miss upsert. */
    fun findByDedupKey(dedupKey: String): Tea?
}
