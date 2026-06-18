package com.macsia.teatiers.domain

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.LocalDate

/**
 * One aggregated `/resolve` miss (decision #116): a normalized query string the catalog could not
 * resolve, with how many times it has been asked and the first/last date it was seen. Intentionally
 * **no-PII** — there is no IP, session, device id, user id, or time-of-day, so a row cannot
 * re-identify who searched.
 *
 * Rows are written by [com.macsia.teatiers.service.MissLogService] via an atomic upsert (the
 * `query_norm` is the primary key); the operator reviews `ORDER BY miss_count DESC` and promotes the
 * real teas into the curated seed. The entity is read-only from the app's side.
 */
@Entity
@Table(name = "catalog_miss")
class CatalogMiss(
    @Id
    @Column(name = "query_norm", nullable = false)
    val queryNorm: String,

    @Column(name = "miss_count", nullable = false)
    val missCount: Long,

    @Column(name = "first_seen", nullable = false)
    val firstSeen: LocalDate,

    @Column(name = "last_seen", nullable = false)
    val lastSeen: LocalDate,
)
