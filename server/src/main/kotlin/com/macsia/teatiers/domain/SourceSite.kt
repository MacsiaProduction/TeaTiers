package com.macsia.teatiers.domain

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Table
import org.hibernate.annotations.JdbcTypeCode
import org.hibernate.type.SqlTypes
import java.time.Instant

/**
 * A scrape source registry row + its hard preflight gates (V8, decision #136). A run may not start for a
 * site unless [termsSignedOffAt] is set AND [active] is true; the scraper re-checks robots every run.
 * [allowedHosts] is the fetch allowlist enforced by `UrlSafety` (decision #141, PR-2): every observed /
 * robots / evidence URL must resolve to a host in this set, and changing it invalidates approval.
 */
@Entity
@Table(name = "source_site")
class SourceSite(
    @Column(nullable = false, unique = true)
    var code: String,

    @Column(name = "display_name", nullable = false)
    var displayName: String,

    @Column(name = "base_url", nullable = false)
    var baseUrl: String,

    // The first Postgres TEXT[] mapping in the codebase (decision #141, PR-2): Hibernate 6 ARRAY jdbc type.
    // A Kotlin Array has identity equals, so callers compare/normalize via .toSet() (see SourceSiteService).
    @JdbcTypeCode(SqlTypes.ARRAY)
    @Column(name = "allowed_hosts", nullable = false, columnDefinition = "text[]")
    var allowedHosts: Array<String> = emptyArray(),

    @Column(name = "license_default")
    var licenseDefault: String? = null,

    @Column(name = "terms_url")
    var termsUrl: String? = null,

    @Column(name = "terms_checked_at")
    var termsCheckedAt: Instant? = null,

    @Column(name = "terms_signed_off_by")
    var termsSignedOffBy: String? = null,

    @Column(name = "terms_signed_off_at")
    var termsSignedOffAt: Instant? = null,

    @Column(name = "robots_url")
    var robotsUrl: String? = null,

    @Column(name = "robots_checked_at")
    var robotsCheckedAt: Instant? = null,

    @Column(nullable = false)
    var active: Boolean = false,

    var notes: String? = null,

    @Column(name = "created_at", nullable = false)
    var createdAt: Instant = Instant.now(),

    @Column(name = "updated_at", nullable = false)
    var updatedAt: Instant = Instant.now(),

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long? = null,
) {
    /** The ToS owner-sign-off gate (Ryanair v PR Aviation): a run is allowed only when both hold. */
    fun importAllowed(): Boolean = active && termsSignedOffAt != null
}
