package com.macsia.teatiers.domain

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.FetchType
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.JoinColumn
import jakarta.persistence.ManyToOne
import jakarta.persistence.Table

/**
 * One reference image for a tea (plan.md section 4a, decision #70.2). A tea can have several,
 * ordered by [position]; the first is the card thumbnail, the rest fill the detail gallery. Only
 * curated CC/Wikimedia images ever land here — web image fetching stays banned (#24). [license] +
 * [sourceUrl] carry the attribution share-alike licenses require; [source] is provenance (e.g.
 * `wikimedia`).
 */
@Entity
@Table(name = "tea_image")
class TeaImage(
    @Column(nullable = false)
    var position: Short,

    @Column(nullable = false)
    var url: String,

    var license: String? = null,

    @Column(name = "source_url")
    var sourceUrl: String? = null,

    var source: String? = null,

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long? = null,
) {
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "tea_id", nullable = false)
    lateinit var tea: Tea
}
