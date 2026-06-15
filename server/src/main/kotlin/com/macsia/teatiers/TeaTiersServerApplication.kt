package com.macsia.teatiers

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication

/**
 * TeaTiers catalog service entry point.
 *
 * Scope (decisions.md #2): a read-only, multilingual tea-catalog API. It holds no user
 * data / no PII. Phase 0 ships only the application shell + actuator health endpoint;
 * the Flyway schema, search/detail API, and enrichment land in milestones M2/M4.
 */
@SpringBootApplication
class TeaTiersServerApplication

fun main(args: Array<String>) {
    runApplication<TeaTiersServerApplication>(*args)
}
