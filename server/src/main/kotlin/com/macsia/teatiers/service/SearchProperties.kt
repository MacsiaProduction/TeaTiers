package com.macsia.teatiers.service

import org.springframework.boot.context.properties.ConfigurationProperties

/**
 * Tunables for `GET /teas/search` (review 2026-06-19 P2/P3). [ratePerMinute] is a per-client
 * fixed-window cap that protects this unauthenticated, public read endpoint from abuse. It is
 * deliberately **generous** — well above any debounced search-as-you-type rate — so it's an
 * anti-abuse floor, not something a normal user can hit.
 */
@ConfigurationProperties(prefix = "teatiers.search")
data class SearchProperties(
    val ratePerMinute: Int = 120,
)
