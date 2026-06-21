package com.macsia.teatiers.domain

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.Instant

/**
 * A cross-script identity alias (V9, decision #136): the authoritative layer unifying Да Хун Пао /
 * Da Hong Pao / 大红袍. Curated + human-confirmed rows establish identity (Tier 0); library-derived rows
 * stay [verified] = false and only propose review candidates. [aliasNorm] is a DB-generated column
 * (lower(f_unaccent(alias))), read-only here.
 */
@Entity
@Table(name = "tea_identity_alias")
class TeaIdentityAlias(
    @Column(name = "tea_id", nullable = false)
    var teaId: Long,

    @Column(nullable = false)
    var locale: String,

    @Column(nullable = false)
    var alias: String,

    @Column(name = "origin", nullable = false)
    var origin: String,

    @Column(name = "romanization_system")
    var romanizationSystem: String? = null,

    @Column(nullable = false)
    var verified: Boolean = false,

    var source: String? = null,

    // DB-generated (lower(f_unaccent(alias))); never written by the app.
    @Column(name = "alias_norm", insertable = false, updatable = false)
    var aliasNorm: String? = null,

    @Column(name = "created_at", nullable = false)
    var createdAt: Instant = Instant.now(),

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long? = null,
)
