package com.macsia.teatiers.client

import org.springframework.boot.context.properties.ConfigurationProperties

/**
 * Tunables for the Wikidata SPARQL client. Endpoint and User-Agent are configurable so the
 * Wikimedia UA policy (a descriptive agent with contact info) can be satisfied per deployment.
 */
@ConfigurationProperties(prefix = "teatiers.wikidata")
data class WikidataProperties(
    val enabled: Boolean = true,
    val endpoint: String = "https://query.wikidata.org/sparql",
    val userAgent: String = "TeaTiers/0.1 (+https://github.com/macsia/TeaTiers) catalog-resolver",
    val connectTimeoutMs: Int = 3_000,
    val readTimeoutMs: Int = 8_000,
)
