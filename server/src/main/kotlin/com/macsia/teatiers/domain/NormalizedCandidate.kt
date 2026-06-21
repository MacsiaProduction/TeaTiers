package com.macsia.teatiers.domain

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.Instant

/**
 * Cross-script normalized projection of a [SourceRecord] (V8, decision #136), ready to match against the
 * catalog. The *_norm columns are built with the server convention lower(f_unaccent(...)) so they line up
 * with `tea_name.name_norm` and the existing pg_trgm GIN (the shared-normalizer invariant).
 */
@Entity
@Table(name = "normalized_candidate")
class NormalizedCandidate(
    @Column(name = "source_record_id", nullable = false)
    var sourceRecordId: Long,

    @Column(name = "name_ru")
    var nameRu: String? = null,

    @Column(name = "name_en")
    var nameEn: String? = null,

    @Column(name = "name_zh")
    var nameZh: String? = null,

    @Column(name = "name_pinyin")
    var namePinyin: String? = null,

    @Column(name = "name_ru_norm")
    var nameRuNorm: String? = null,

    @Column(name = "name_pinyin_norm")
    var namePinyinNorm: String? = null,

    @Column(name = "pinyin_from_hanzi")
    var pinyinFromHanzi: String? = null,

    @Column(name = "palladius_bridge")
    var palladiusBridge: String? = null,

    var type: String? = null,

    @Column(name = "origin_country")
    var originCountry: String? = null,

    var region: String? = null,

    var cultivar: String? = null,

    var brand: String? = null,

    var vendor: String? = null,

    @Column(name = "created_at", nullable = false)
    var createdAt: Instant = Instant.now(),

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long? = null,
)
