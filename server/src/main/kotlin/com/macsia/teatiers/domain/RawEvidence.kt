package com.macsia.teatiers.domain

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.Instant

/**
 * Immutable fetch envelope (V8, decision #136): hashes + metadata only. [rawBlobRef] points at an
 * object-store key for a retained raw body IF one is kept at all (access-controlled, short retention,
 * never shipped, never on the API path) -- NULL in the facts-only first cut. Append-only.
 */
@Entity
@Table(name = "raw_evidence")
class RawEvidence(
    @Column(name = "import_run_id", nullable = false)
    var importRunId: Long,

    @Column(name = "source_site_id", nullable = false)
    var sourceSiteId: Long,

    @Column(name = "canonical_url", nullable = false)
    var canonicalUrl: String,

    @Column(name = "http_status")
    var httpStatus: Int? = null,

    @Column(name = "retrieved_at", nullable = false)
    var retrievedAt: Instant,

    @Column(name = "content_hash", nullable = false)
    var contentHash: String,

    @Column(name = "content_type")
    var contentType: String? = null,

    @Column(name = "parser_version", nullable = false)
    var parserVersion: String,

    @Column(name = "raw_blob_ref")
    var rawBlobRef: String? = null,

    @Column(name = "created_at", nullable = false)
    var createdAt: Instant = Instant.now(),

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long? = null,
)
