package com.macsia.teatiers.domain

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.Instant

/**
 * The old canonical URL of a [SourceRecord], recorded when a slug rename reconciles its identity
 * (V10, decision #137-C5 / SCR-P0-5). An audit trail so a renamed product's prior URL is never lost.
 */
@Entity
@Table(name = "source_record_url_history")
class SourceRecordUrlHistory(
    @Column(name = "source_record_id", nullable = false)
    var sourceRecordId: Long,

    @Column(name = "canonical_url", nullable = false)
    var canonicalUrl: String,

    @Column(name = "recorded_at", nullable = false)
    var recordedAt: Instant = Instant.now(),

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long? = null,
)
