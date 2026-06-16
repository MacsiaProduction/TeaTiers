package com.macsia.teatiers

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.context.properties.ConfigurationPropertiesScan
import org.springframework.boot.runApplication

/**
 * TeaTiers catalog service entry point.
 *
 * Scope (decisions.md #2): a multilingual tea-catalog API. It holds no user data / no PII.
 * M2 shipped the Flyway schema + read-only search/detail API; M4 adds the `/resolve`
 * enrichment flow (Wikidata-first; LLM tier follows). @ConfigurationPropertiesScan binds the
 * `teatiers.*` tunables (Wikidata client, resolve rate-limit).
 */
@SpringBootApplication
@ConfigurationPropertiesScan
class TeaTiersServerApplication

fun main(args: Array<String>) {
    runApplication<TeaTiersServerApplication>(*args)
}
