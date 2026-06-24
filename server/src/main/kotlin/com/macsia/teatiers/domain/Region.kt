package com.macsia.teatiers.domain

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.Instant

/**
 * A canonical tea-growing region (decision #138), keyed by a Wikidata QID when known and carrying localized
 * names so the UI can show the region in the user's locale. `tea.region_id` points here; the raw, as-observed
 * region text stays on `tea.region` until a curation/QID step resolves it. No parent hierarchy yet (YAGNI).
 */
@Entity
@Table(name = "region")
class Region(
    @Column(name = "wikidata_qid")
    var wikidataQid: String? = null,

    @Column(name = "country_code")
    var countryCode: String? = null,

    @Column(name = "name_ru")
    var nameRu: String? = null,

    @Column(name = "name_en")
    var nameEn: String? = null,

    @Column(name = "name_zh")
    var nameZh: String? = null,

    @Column(name = "created_at", nullable = false)
    var createdAt: Instant = Instant.now(),

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long? = null,
)
