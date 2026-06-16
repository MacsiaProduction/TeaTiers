package com.macsia.teatiers.service

import org.springframework.boot.context.properties.ConfigurationProperties

/**
 * Tunables for the `/resolve` endpoint. [ratePerMinute] is the per-client fixed-window cap that
 * protects the downstream Wikidata (and, later, LLM) budget. Payload size caps live as Bean
 * Validation constants on [com.macsia.teatiers.dto.ResolveRequestDto].
 */
@ConfigurationProperties(prefix = "teatiers.resolve")
data class ResolveProperties(
    val ratePerMinute: Int = 20,
)
