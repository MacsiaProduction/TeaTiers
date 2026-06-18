package com.macsia.teatiers.service

import java.util.concurrent.ConcurrentHashMap

/**
 * Per-client fixed-window rate limiter. In-memory is sufficient for the single-server deployment
 * (rule 40-devops); it bounds how fast one caller can drive a downstream cost (Wikidata/LLM lookups
 * for `/resolve`, sidecar inference for `/ocr`). Not a security control — just abuse / cost
 * protection. Each endpoint gets its OWN instance (see `RateLimiterConfig`), so its window and
 * budget are independent: OCR traffic never depletes the `/resolve` allowance and vice versa.
 */
class FixedWindowRateLimiter(
    private val ratePerMinute: Int,
) {

    // Swappable in tests to exercise window rollover without sleeping.
    internal var nowMillis: () -> Long = System::currentTimeMillis

    private val windows = ConcurrentHashMap<String, Window>()

    /** Returns true if the call is within budget; false once [ratePerMinute] is hit in this window. */
    fun tryAcquire(clientId: String): Boolean {
        val now = nowMillis()
        val windowStart = now - (now % WINDOW_MS)
        if (windows.size > MAX_TRACKED_CLIENTS) purgeStale(windowStart)

        val window = windows.computeIfAbsent(clientId) { Window(windowStart) }
        synchronized(window) {
            if (window.start != windowStart) {
                window.start = windowStart
                window.count = 0
            }
            window.count += 1
            return window.count <= ratePerMinute
        }
    }

    private fun purgeStale(currentWindowStart: Long) {
        windows.entries.removeIf { (_, w) -> synchronized(w) { w.start != currentWindowStart } }
    }

    private class Window(@Volatile var start: Long, @Volatile var count: Int = 0)

    private companion object {
        const val WINDOW_MS = 60_000L
        const val MAX_TRACKED_CLIENTS = 10_000
    }
}
