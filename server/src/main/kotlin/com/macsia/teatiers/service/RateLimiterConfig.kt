package com.macsia.teatiers.service

import com.macsia.teatiers.client.LlmProperties
import com.macsia.teatiers.client.OcrProperties
import com.macsia.teatiers.controller.ClientDiagnosticsProperties
import io.github.bucket4j.Bucket
import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import java.time.Duration
import java.util.concurrent.Semaphore

/**
 * One [ClientRateLimiter] per cost-bearing endpoint so each keeps an independent per-client budget
 * (decision #103, review P2): `/resolve` protects the Wikidata/LLM budget, `/ocr` protects the
 * heavier sidecar inference, `/search` the catalog. Beans are named after their constructor-parameter
 * counterparts in `TeaController`, which selects between them with `@Qualifier`.
 */
@Configuration
class RateLimiterConfig {

    @Bean
    fun resolveRateLimiter(
        @Value("\${teatiers.resolve.rate-per-minute:20}") ratePerMinute: Int,
    ): ClientRateLimiter = ClientRateLimiter(ratePerMinute)

    @Bean
    fun ocrRateLimiter(props: OcrProperties): ClientRateLimiter =
        ClientRateLimiter(props.ratePerMinute)

    @Bean
    fun searchRateLimiter(
        @Value("\${teatiers.search.rate-per-minute:120}") ratePerMinute: Int,
    ): ClientRateLimiter = ClientRateLimiter(ratePerMinute)

    /**
     * GLOBAL edge ceiling for the cheap-but-unbounded read paths `/search` + `/resolve` (decision #141 /
     * PRIV-P1-1). A single shared token bucket that NO per-client key churn can reset: it bounds total
     * throughput even when a flood of distinct client ids each stays under its own per-client budget (which
     * eviction could otherwise refresh). Generous default ([EDGE_CAP] / min) -- well above normal load, sized
     * to shed only a genuine overload; calibrate against real traffic.
     */
    @Bean
    fun edgeRateBucket(): Bucket = Bucket.builder()
        .addLimit { it.capacity(EDGE_CAP).refillGreedy(EDGE_CAP, Duration.ofMinutes(1)) }
        .build()

    /**
     * GLOBAL concurrency gate for /teas/ocr (review F4): the sidecar serializes inference, so this
     * bounds how many requests can be in flight against it before the controller fast-fails 503 —
     * keeping blocked OCR requests from pinning Tomcat worker threads and starving the catalog API.
     */
    @Bean
    fun ocrConcurrencyGate(props: OcrProperties): Semaphore = Semaphore(props.maxConcurrent)

    /**
     * Global per-UTC-day enrichment-LLM call ceiling (plan.md section 6 "quota protection"): caps total
     * LLM spend across all callers; once hit a Wikidata miss fails closed to UNRESOLVED. The cap is read
     * live each call so a property change takes effect immediately.
     */
    @Bean
    fun llmDailyBudget(props: LlmProperties): DailyBudget = DailyBudget { props.dailyCallCap }

    /**
     * Global per-UTC-day client-diagnostics insert ceiling (decision #111, review finding): bounds total
     * inserts so the APK-extractable token can't flood the table and fill disk. Global (not per-IP) so the
     * endpoint still never reads the client IP. The cap is read live each call.
     */
    @Bean
    fun diagnosticsDailyBudget(props: ClientDiagnosticsProperties): DailyBudget =
        DailyBudget { props.dailyCap }

    private companion object {
        const val EDGE_CAP = 6_000L // ~100 req/s smoothed across ALL clients for /search + /resolve combined
    }
}
