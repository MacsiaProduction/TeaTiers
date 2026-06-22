package com.macsia.teatiers.domain

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.Instant

/**
 * One local one-off import run (V8, decision #136). Carries the per-run robots snapshot (re-fetched every
 * run, never one-time); its [status] lifecycle is governed by ImportRunStateMachine (decision #137-C4).
 * Runs are operator-initiated, never a daemon on prod.
 */
@Entity
@Table(name = "import_run")
class ImportRun(
    @Column(name = "source_site_id", nullable = false)
    var sourceSiteId: Long,

    @Column(nullable = false)
    var operator: String,

    @Column(name = "tool_version", nullable = false)
    var toolVersion: String,

    @Column(name = "parser_version", nullable = false)
    var parserVersion: String,

    @Column(name = "dry_run", nullable = false)
    var dryRun: Boolean = true,

    // Lifecycle managed by ImportRunStateMachine (decision #137-C4); startRun persists 'preflight_allowed'.
    @Column(nullable = false)
    var status: String = "preflight_allowed",

    @Column(name = "started_at", nullable = false)
    var startedAt: Instant = Instant.now(),

    @Column(name = "finished_at")
    var finishedAt: Instant? = null,

    @Column(name = "robots_fetched_at")
    var robotsFetchedAt: Instant? = null,

    @Column(name = "robots_http_status")
    var robotsHttpStatus: Int? = null,

    @Column(name = "robots_hash")
    var robotsHash: String? = null,

    @Column(name = "robots_decision")
    var robotsDecision: String? = null,

    @Column(name = "robots_url")
    var robotsUrl: String? = null,

    @Column(name = "robots_user_agent")
    var robotsUserAgent: String? = null,

    var notes: String? = null,

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long? = null,
)
