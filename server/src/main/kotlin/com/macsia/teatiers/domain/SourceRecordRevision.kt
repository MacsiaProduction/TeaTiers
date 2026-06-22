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
 * An immutable observation revision (V10, decision #137-C5). Each distinct [contentHash] of a
 * [SourceRecord]'s parsed facts is an append-only revision; a [MatchDecision] binds to the revision it
 * reviewed so a corrected re-import re-enters review and a stale approval is rejected. Never mutated after
 * insert -- a change is a new revision, not an edit.
 */
@Entity
@Table(name = "source_record_revision")
class SourceRecordRevision(
    @Column(name = "source_record_id", nullable = false)
    var sourceRecordId: Long,

    @Column(name = "content_hash", nullable = false)
    var contentHash: String,

    @Column(name = "parser_version", nullable = false)
    var parserVersion: String,

    @Column(name = "retrieved_at", nullable = false)
    var retrievedAt: Instant,

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "raw_facts", nullable = false, columnDefinition = "jsonb")
    var rawFacts: String,

    @Column(name = "import_run_id", nullable = false)
    var importRunId: Long,

    // Bound to the immutable fetch envelope at ingest (decision #141, PR-2); NOT NULL from V14 -- every
    // revision must prove the fetch that produced it, and canonical apply fails closed without it.
    @Column(name = "raw_evidence_id", nullable = false)
    var rawEvidenceId: Long,

    @Column(name = "created_at", nullable = false)
    var createdAt: Instant = Instant.now(),

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long? = null,
)
