package com.macsia.teatiers.domain

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.FetchType
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.JoinColumn
import jakarta.persistence.ManyToOne
import jakarta.persistence.Table

/**
 * Reference flavor intensity (0-5) for one dimension of a tea. An absent row means "unknown"
 * for that dimension; 0 means "none", not "unknown". At most one row per (tea, dimension).
 */
@Entity
@Table(name = "tea_flavor")
class TeaFlavor(
    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    var dimension: FlavorDimension,

    @Column(nullable = false)
    var intensity: Short,

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long? = null,
) {
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "tea_id", nullable = false)
    lateinit var tea: Tea
}
