package com.macsia.teatiers.service

import org.springframework.boot.ApplicationArguments
import org.springframework.boot.ApplicationRunner
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.stereotype.Component

/**
 * Runs the curated catalog seed once on startup. Gated by `teatiers.seed.enabled` (on by default)
 * so tests can keep an empty catalog; the seed itself is idempotent.
 */
@Component
@ConditionalOnProperty(value = ["teatiers.seed.enabled"], havingValue = "true", matchIfMissing = true)
class CatalogSeedRunner(
    private val seeder: CatalogSeeder,
) : ApplicationRunner {

    override fun run(args: ApplicationArguments) {
        seeder.seed()
    }
}
