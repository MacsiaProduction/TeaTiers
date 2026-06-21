package com.macsia.teatiers.domain

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.math.BigDecimal
import java.time.Instant

/**
 * Per-field provenance (V9, decision #136): one row per (tea, field) recording WHERE that fact came from,
 * so a canonical tea assembled from several source records is representable. `tea.source` stays the
 * row-origin summary; field truth lives here. [fieldName] examples: 'type', 'region', 'name:ru'.
 */
@Entity
@Table(name = "tea_field_provenance")
class TeaFieldProvenance(
    @Column(name = "tea_id", nullable = false)
    var teaId: Long,

    @Column(name = "field_name", nullable = false)
    var fieldName: String,

    @Column(name = "source_record_id")
    var sourceRecordId: Long? = null,

    @Column(name = "source_site_id")
    var sourceSiteId: Long? = null,

    @Column(name = "source_url")
    var sourceUrl: String? = null,

    var license: String? = null,

    var confidence: BigDecimal? = null,

    @Column(name = "recorded_at", nullable = false)
    var recordedAt: Instant = Instant.now(),

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long? = null,
)
