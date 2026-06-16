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
 * One name (or alias) for a tea in a single locale. Multi-row-per-locale so aliases fit;
 * locale is `en`/`ru`/`zh-Hans`/`pinyin` (validated by the schema CHECK).
 */
@Entity
@Table(name = "tea_name")
class TeaName(
    @Column(nullable = false)
    var locale: String,

    @Column(nullable = false)
    var name: String,

    @Column(name = "is_primary", nullable = false)
    var isPrimary: Boolean = false,

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
