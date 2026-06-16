package com.macsia.teatiers.service

import java.util.concurrent.ConcurrentHashMap
import org.springframework.stereotype.Component

/**
 * Per-client fixed-window rate limiter for `/resolve`. In-memory is sufficient for the single-server
 * deployment (rule 40-devops); it bounds how fast one caller can drive Wikidata (and the later LLM
 * tier) lookups. Not a security control — just abuse / cost protection.
 */
@Component
class ResolveRateLimiter(
    private val props: ResolveProperties,
) {

    // Swappable in tests to exercise window rollover without sleeping.
    internal var nowMillis: () -> Long = System::currentTimeMillis

    private val windows = ConcurrentHashMap<String, Window>()

    /** Returns true if the call is within budget; false once [ResolveProperties.ratePerMinute] is hit. */
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
            return window.count <= props.ratePerMinute
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
