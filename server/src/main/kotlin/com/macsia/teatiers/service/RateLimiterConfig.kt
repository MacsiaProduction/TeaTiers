package com.macsia.teatiers.service

import com.macsia.teatiers.client.OcrProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

/**
 * One [FixedWindowRateLimiter] per cost-bearing endpoint so each keeps an independent window
 * (decision #103, review P2): `/resolve` protects the Wikidata/LLM budget, `/ocr` protects the
 * heavier sidecar inference. Beans are named after their constructor-parameter counterparts in
 * `TeaController`, which selects between them with `@Qualifier`.
 */
@Configuration
class RateLimiterConfig {

    @Bean
    fun resolveRateLimiter(props: ResolveProperties): FixedWindowRateLimiter =
        FixedWindowRateLimiter(props.ratePerMinute)

    @Bean
    fun ocrRateLimiter(props: OcrProperties): FixedWindowRateLimiter =
        FixedWindowRateLimiter(props.ratePerMinute)
}
