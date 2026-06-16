package com.macsia.teatiers.domain

import jakarta.persistence.CascadeType
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.FetchType
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.OneToMany
import jakarta.persistence.Table
import java.time.Instant

/**
 * A shared-catalog tea (plan.md section 4a). Names, descriptions, and flavor rows hang off
 * this aggregate root. Logical enums are persisted as text + CHECK in the schema, so the type
 * here uses @Enumerated(STRING); free-form provenance fields stay String to avoid converters.
 */
@Entity
@Table(name = "tea")
class Tea(
    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    var type: TeaType,

    @Column(nullable = false)
    var source: String,

    @Column(name = "dedup_key", nullable = false)
    var dedupKey: String,

    @Column(name = "wikidata_qid")
    var wikidataQid: String? = null,

    @Column(name = "origin_country")
    var originCountry: String? = null,

    var region: String? = null,

    var cultivar: String? = null,

    @Column(name = "oxidation_min")
    var oxidationMin: Short? = null,

    @Column(name = "oxidation_max")
    var oxidationMax: Short? = null,

    var brand: String? = null,

    @Column(name = "image_url")
    var imageUrl: String? = null,

    @Column(name = "image_license")
    var imageLicense: String? = null,

    @Column(name = "image_source_url")
    var imageSourceUrl: String? = null,

    @Column(name = "source_url")
    var sourceUrl: String? = null,

    var license: String? = null,

    @Column(name = "retrieved_at")
    var retrievedAt: Instant? = null,

    @Column(name = "verification_status", nullable = false)
    var verificationStatus: String = "unverified",

    var confidence: Float? = null,

    @Column(name = "enriched_by")
    var enrichedBy: String? = null,

    @Column(name = "enriched_at")
    var enrichedAt: Instant? = null,

    // Async LLM enrichment lifecycle (V2): null unless an LLM job is/was attached to this row.
    @Column(name = "enrichment_state")
    var enrichmentState: String? = null,

    @Column(name = "enrichment_error")
    var enrichmentError: String? = null,

    @Column(name = "created_at", nullable = false)
    var createdAt: Instant = Instant.now(),

    @Column(name = "updated_at", nullable = false)
    var updatedAt: Instant = Instant.now(),

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long? = null,

    @OneToMany(mappedBy = "tea", cascade = [CascadeType.ALL], orphanRemoval = true)
    var names: MutableList<TeaName> = mutableListOf(),

    @OneToMany(mappedBy = "tea", cascade = [CascadeType.ALL], orphanRemoval = true)
    var descriptions: MutableList<TeaDescription> = mutableListOf(),

    @OneToMany(mappedBy = "tea", cascade = [CascadeType.ALL], orphanRemoval = true)
    var flavors: MutableList<TeaFlavor> = mutableListOf(),
) {
    fun addName(name: TeaName) {
        name.tea = this
        names += name
    }

    fun addDescription(description: TeaDescription) {
        description.tea = this
        descriptions += description
    }

    fun addFlavor(flavor: TeaFlavor) {
        flavor.tea = this
        flavors += flavor
    }
}
