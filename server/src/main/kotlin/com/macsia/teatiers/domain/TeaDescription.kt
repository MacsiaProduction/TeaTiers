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
 * Localized description for a tea. `shortText` is the card blurb; `fullText` is the optional
 * "read full" body (a Wikipedia extract here is CC-BY-SA and must be attributed). One row per
 * (tea, locale) where locale is `ru`/`en`/`zh`.
 */
@Entity
@Table(name = "tea_description")
class TeaDescription(
    @Column(nullable = false)
    var locale: String,

    @Column(name = "short_text")
    var shortText: String? = null,

    @Column(name = "full_text")
    var fullText: String? = null,

    var source: String? = null,

    var license: String? = null,

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long? = null,
) {
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "tea_id", nullable = false)
    lateinit var tea: Tea
}
