package com.macsia.teatiers.service

import com.macsia.teatiers.client.OcrProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import java.util.concurrent.Semaphore

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

    @Bean
    fun searchRateLimiter(props: SearchProperties): FixedWindowRateLimiter =
        FixedWindowRateLimiter(props.ratePerMinute)

    /**
     * GLOBAL concurrency gate for /teas/ocr (review F4): the sidecar serializes inference, so this
     * bounds how many requests can be in flight against it before the controller fast-fails 503 —
     * keeping blocked OCR requests from pinning Tomcat worker threads and starving the catalog API.
     */
    @Bean
    fun ocrConcurrencyGate(props: OcrProperties): Semaphore = Semaphore(props.maxConcurrent)
}
