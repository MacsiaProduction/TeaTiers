package com.macsia.teatiers.config

import org.springframework.context.annotation.Configuration
import org.springframework.scheduling.annotation.EnableScheduling

/**
 * Enables `@Scheduled` so the client-diagnostics retention purge (decision #111) runs daily. Kept
 * separate from [AsyncConfig] (which owns `@EnableAsync` + the enrichment pool) so the two concerns
 * stay independent.
 */
@Configuration
@EnableScheduling
class SchedulingConfig
