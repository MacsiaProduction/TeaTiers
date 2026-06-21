package com.macsia.teatiers.service

import com.macsia.teatiers.domain.SourceSite
import com.macsia.teatiers.repository.SourceSiteRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Instant

/**
 * Operator administration of the source registry (decision #136). Registering a site does NOT make it
 * importable: an operator must separately record the ToS owner sign-off AND activate it. These are the
 * hard preflight gates, kept as explicit two-step actions so a site can never go live by default.
 */
@Service
class SourceSiteService(
    private val sourceSiteRepository: SourceSiteRepository,
) {

    /** Create or update a source registration. Always lands inactive + un-signed-off. */
    @Transactional
    fun register(
        code: String,
        displayName: String,
        baseUrl: String,
        termsUrl: String? = null,
        robotsUrl: String? = null,
        licenseDefault: String? = null,
    ): SourceSite {
        val site = sourceSiteRepository.findByCode(code) ?: SourceSite(
            code = code,
            displayName = displayName,
            baseUrl = baseUrl,
        )
        site.displayName = displayName
        site.baseUrl = baseUrl
        site.termsUrl = termsUrl
        site.robotsUrl = robotsUrl
        site.licenseDefault = licenseDefault
        site.updatedAt = Instant.now()
        return sourceSiteRepository.save(site)
    }

    /** Record the ToS owner sign-off (the enforceable lever, Ryanair v PR Aviation). */
    @Transactional
    fun signOffTerms(code: String, signedOffBy: String): SourceSite {
        val site = sourceSiteRepository.findByCode(code) ?: throw UnknownSourceSiteException(code)
        val now = Instant.now()
        site.termsSignedOffBy = signedOffBy
        site.termsSignedOffAt = now
        site.termsCheckedAt = now
        site.updatedAt = now
        return sourceSiteRepository.save(site)
    }

    /** Flip the active flag. A site is import-eligible only when active AND ToS-signed-off. */
    @Transactional
    fun setActive(code: String, active: Boolean): SourceSite {
        val site = sourceSiteRepository.findByCode(code) ?: throw UnknownSourceSiteException(code)
        site.active = active
        site.updatedAt = Instant.now()
        return sourceSiteRepository.save(site)
    }
}
