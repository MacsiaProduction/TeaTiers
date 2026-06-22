package com.macsia.teatiers.service

import com.github.benmanes.caffeine.cache.Caffeine
import io.github.bucket4j.BucketConfiguration
import io.github.bucket4j.caffeine.Bucket4jCaffeine
import io.github.bucket4j.caffeine.CaffeineProxyManager
import io.github.bucket4j.distributed.ExpirationAfterWriteStrategy
import java.time.Duration

/**
 * Per-client token-bucket rate limiter (decision #141 / PRIV-P1-1). Replaces the fixed-window limiter
 * whose Caffeine eviction reset an evicted-but-active client to a fresh window (a re-created entry started
 * at count=0). Each client now gets a Bucket4j token bucket whose refill state lives in a bounded Caffeine
 * cache keyed on the client id, with the eviction TTL = the time for THAT bucket to refill back to full
 * ([ExpirationAfterWriteStrategy.basedOnTimeForRefillingBucketUpToMax]): an active client keeps writing so
 * its entry is never time-evicted mid-flight, and an idle bucket is only evicted once it has refilled to
 * full -- so re-creating it yields an identical full bucket and key churn can never hand a client a fresh
 * allowance early. In-memory, single-JVM (sufficient at this scale; no Redis).
 *
 * NOTE: `maximumSize` remains a hard memory backstop. Under an extreme flood that exceeds it, Caffeine's
 * size eviction can still drop an active entry (bounded over-admission); the GLOBAL edge bucket
 * (`RateLimiterConfig.edgeRateBucket`), which no per-client key churn can reset, is the real ceiling for
 * that case. The two together are the PRIV-P1-1 fix.
 */
class ClientRateLimiter(ratePerMinute: Int, maxTrackedClients: Long = MAX_TRACKED_CLIENTS) {

    private val config: BucketConfiguration = BucketConfiguration.builder()
        .addLimit { it.capacity(ratePerMinute.toLong()).refillGreedy(ratePerMinute.toLong(), Duration.ofMinutes(1)) }
        .build()

    private val proxyManager: CaffeineProxyManager<String> =
        Bucket4jCaffeine.builderFor<String>(Caffeine.newBuilder().maximumSize(maxTrackedClients))
            .expirationAfterWrite(ExpirationAfterWriteStrategy.basedOnTimeForRefillingBucketUpToMax(EVICTION_JITTER))
            .build()

    /** True if within budget; consumes one token from the client's bucket. */
    fun tryAcquire(clientId: String): Boolean = proxyManager.getProxy(clientId) { config }.tryConsume(1)

    /** Test/diagnostic hook: tracked clients after pending evictions (the bounded-under-flood invariant). */
    internal fun trackedClients(): Long {
        proxyManager.cache.cleanUp()
        return proxyManager.cache.estimatedSize()
    }

    private companion object {
        const val MAX_TRACKED_CLIENTS = 100_000L
        val EVICTION_JITTER: Duration = Duration.ofSeconds(10)
    }
}
