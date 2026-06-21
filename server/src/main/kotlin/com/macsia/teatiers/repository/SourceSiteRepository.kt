package com.macsia.teatiers.repository

import com.macsia.teatiers.domain.SourceSite
import org.springframework.data.jpa.repository.JpaRepository

interface SourceSiteRepository : JpaRepository<SourceSite, Long> {
    fun findByCode(code: String): SourceSite?
}
