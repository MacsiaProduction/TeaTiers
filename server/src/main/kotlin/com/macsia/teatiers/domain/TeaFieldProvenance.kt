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
 * A per-field provenance CLAIM (V9 + V10, decision #137-C6): one row per (tea, field, source) recording
 * the [claimedValue], WHERE it came from (source record/revision/site/url), the reviewing [matchDecisionId]
 * + [reviewer], and whether it is the [selected] value for that scalar field. A scalar field has exactly
 * one selected claim; a conflict (an existing value won) is kept as a non-selected claim rather than lost.
 * Names ('name:<locale>') are multi-valued provenance, exempt from the single-selection rule. `tea.source`
 * stays the row-origin summary; field truth lives here.
 */
@Entity
@Table(name = "tea_field_provenance")
class TeaFieldProvenance(
    @Column(name = "tea_id", nullable = false)
    var teaId: Long,

    @Column(name = "field_name", nullable = false)
    var fieldName: String,

    @Column(name = "claimed_value")
    var claimedValue: String? = null,

    @Column(name = "selected", nullable = false)
    var selected: Boolean = true,

    @Column(name = "source_record_id")
    var sourceRecordId: Long? = null,

    @Column(name = "source_record_revision_id")
    var sourceRecordRevisionId: Long? = null,

    @Column(name = "match_decision_id")
    var matchDecisionId: Long? = null,

    var reviewer: String? = null,

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
