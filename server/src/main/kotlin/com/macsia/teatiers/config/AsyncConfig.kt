package com.macsia.teatiers.config

import java.util.concurrent.Executor
import java.util.concurrent.ThreadPoolExecutor
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.scheduling.annotation.EnableAsync
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor

/**
 * Enables `@Async` and the bounded pool that runs background LLM enrichment (plan.md section 6
 * step 3). The queue is capped and rejection is `AbortPolicy`, so an overloaded server fails the
 * dispatch fast (the caller marks the row FAILED) instead of growing an unbounded backlog or
 * blocking the request thread on a slow model call.
 */
@Configuration
@EnableAsync
class AsyncConfig {

    @Bean("enrichmentExecutor")
    fun enrichmentExecutor(): Executor = ThreadPoolTaskExecutor().apply {
        corePoolSize = 2
        maxPoolSize = 4
        queueCapacity = 50
        setThreadNamePrefix("llm-enrich-")
        setRejectedExecutionHandler(ThreadPoolExecutor.AbortPolicy())
        initialize()
    }
}
