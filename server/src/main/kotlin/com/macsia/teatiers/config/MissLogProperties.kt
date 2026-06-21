package com.macsia.teatiers.config

import org.springframework.boot.context.properties.ConfigurationProperties

/**
 * Retention for the demand-driven miss log (decision #116). Although a row is no-PII by construction
 * (just the normalized query string + a counter + dates), the query string is free text a user typed,
 * so we don't keep it forever (review P0-2 / decision #130): the daily sweep
 * ([com.macsia.teatiers.service.MissLogService.purgeStale]) drops rows last seen before the retention
 * window **and** asked fewer than [minMissCountToKeep] times. Frequently-requested teas survive
 * regardless of age — they are the curation signal the log exists for.
 */
@ConfigurationProperties(prefix = "teatiers.misslog")
data class MissLogProperties(
    /** Rows last seen more than this many days ago are eligible for the purge. */
    val retentionDays: Long = 90,
    /** A row asked at least this many times is kept regardless of age (the curation signal). */
    val minMissCountToKeep: Long = 3,
)
