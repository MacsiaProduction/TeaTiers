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
 * A parsed source record (V8, decision #136): the unit of re-import idempotency, keyed by
 * (sourceSiteId, canonicalUrl) and (sourceSiteId, externalId). [rawFacts] is a FACTS-ONLY JSON document
 * (names/type/origin/region/cultivar/oxidation/brand/vendor) -- never vendor prose. [teaId] is the
 * canonical link, filled only after an approved match decision.
 */
@Entity
@Table(name = "source_record")
class SourceRecord(
    @Column(name = "source_site_id", nullable = false)
    var sourceSiteId: Long,

    @Column(name = "canonical_url", nullable = false)
    var canonicalUrl: String,

    @Column(name = "external_id")
    var externalId: String? = null,

    @Column(name = "import_run_id", nullable = false)
    var importRunId: Long,

    @Column(name = "raw_evidence_id")
    var rawEvidenceId: Long? = null,

    @Column(name = "content_hash", nullable = false)
    var contentHash: String,

    @Column(name = "parser_version", nullable = false)
    var parserVersion: String,

    @Column(name = "retrieved_at", nullable = false)
    var retrievedAt: Instant,

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "raw_facts", nullable = false, columnDefinition = "jsonb")
    var rawFacts: String,

    @Column(nullable = false)
    var status: String = "parsed",

    @Column(name = "first_seen_at", nullable = false)
    var firstSeenAt: Instant = Instant.now(),

    @Column(name = "last_seen_at", nullable = false)
    var lastSeenAt: Instant = Instant.now(),

    @Column(name = "tea_id")
    var teaId: Long? = null,

    // The latest immutable revision of this record's facts (V10, decision #137-C5). Decisions bind to a
    // revision; this points at the one the next review/approval should act on.
    @Column(name = "current_revision_id")
    var currentRevisionId: Long? = null,

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long? = null,
)
