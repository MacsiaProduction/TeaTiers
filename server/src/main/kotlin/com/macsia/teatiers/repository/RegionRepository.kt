package com.macsia.teatiers.repository

import com.macsia.teatiers.domain.Region
import org.springframework.data.jpa.repository.JpaRepository

interface RegionRepository : JpaRepository<Region, Long> {
    fun findByWikidataQid(wikidataQid: String): Region?
}
