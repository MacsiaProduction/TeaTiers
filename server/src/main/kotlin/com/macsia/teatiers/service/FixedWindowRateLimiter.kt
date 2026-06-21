package com.macsia.teatiers.service

import com.github.benmanes.caffeine.cache.Cache
import com.github.benmanes.caffeine.cache.Caffeine
import java.time.Duration

/**
 * Per-client fixed-window rate limiter. In-memory is sufficient for the single-server deployment
 * (rule 40-devops); it bounds how fast one caller can drive a downstream cost (Wikidata/LLM lookups
 * for `/resolve`, sidecar inference for `/ocr`). Not a security control — just abuse / cost
 * protection. Each endpoint gets its OWN instance (see [RateLimiterConfig]), so its window and
 * budget are independent: OCR traffic never depletes the `/resolve` allowance and vice versa.
 *
 * Client state lives in a **bounded** [Caffeine] cache (review P1-9). The old self-purge only dropped
 * PREVIOUS-window entries, so a flood of distinct forwarded IPs within a single active window could
 * still grow the map without bound (attacker-controlled heap). `maximumSize` now caps that worst case
 * and `expireAfterAccess` drops idle clients. Eviction never relaxes an active client's budget — a
 * re-created entry simply starts a fresh window, identical to a first-ever request.
 */
class FixedWindowRateLimiter(
    private val ratePerMinute: Int,
    maxTrackedClients: Long = MAX_TRACKED_CLIENTS,
) {

    // Swappable in tests to exercise window rollover without sleeping. Caffeine's own eviction uses
    // the real clock; the window math below uses this, so the time-travel tests still hold.
    internal var nowMillis: () -> Long = System::currentTimeMillis

    private val windows: Cache<String, Window> = Caffeine.newBuilder()
        .maximumSize(maxTrackedClients)
        .expireAfterAccess(Duration.ofMillis(2 * WINDOW_MS))
        .build()

    /** Returns true if the call is within budget; false once [ratePerMinute] is hit in this window. */
    fun tryAcquire(clientId: String): Boolean {
        val now = nowMillis()
        val windowStart = now - (now % WINDOW_MS)
        val window = windows.get(clientId) { Window(windowStart) }
        synchronized(window) {
            if (window.start != windowStart) {
                window.start = windowStart
                window.count = 0
            }
            window.count += 1
            return window.count <= ratePerMinute
        }
    }

    /** Test/diagnostic hook: the number of tracked clients after pending evictions are applied. */
    internal fun trackedClients(): Long {
        windows.cleanUp()
        return windows.estimatedSize()
    }

    private class Window(@Volatile var start: Long, @Volatile var count: Int = 0)

    private companion object {
        const val WINDOW_MS = 60_000L
        const val MAX_TRACKED_CLIENTS = 10_000L
    }
}
