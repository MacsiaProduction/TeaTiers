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

    /**
     * Create or update a source registration. A new site lands inactive + un-signed-off. Re-registering an
     * existing site with a MATERIAL change (base/terms/robots URL or license) INVALIDATES its approval
     * (decision #139-R3): it is deactivated and its ToS sign-off cleared, so the operator must re-verify and
     * re-activate. A cosmetic-only re-register (same material fields, e.g. a display-name fix) keeps approval.
     */
    @Transactional
    fun register(
        code: String,
        displayName: String,
        baseUrl: String,
        termsUrl: String? = null,
        robotsUrl: String? = null,
        licenseDefault: String? = null,
    ): SourceSite {
        val existing = sourceSiteRepository.findByCode(code)
        val site = existing ?: SourceSite(code = code, displayName = displayName, baseUrl = baseUrl)
        val materialChange = existing != null && (
            existing.baseUrl != baseUrl ||
                existing.termsUrl != termsUrl ||
                existing.robotsUrl != robotsUrl ||
                existing.licenseDefault != licenseDefault
            )
        site.displayName = displayName
        site.baseUrl = baseUrl
        site.termsUrl = termsUrl
        site.robotsUrl = robotsUrl
        site.licenseDefault = licenseDefault
        if (materialChange) {
            site.active = false
            site.termsSignedOffBy = null
            site.termsSignedOffAt = null
            site.termsCheckedAt = null
        }
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
