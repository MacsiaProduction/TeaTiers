package com.macsia.teatiers.service

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class FixedWindowRateLimiterTest {

    @Test
    fun `tracked client state stays bounded under a flood of distinct ids (review P1-9)`() {
        // The old map could grow without bound within one active window; Caffeine's maximumSize caps it.
        val max = 50L
        val limiter = FixedWindowRateLimiter(ratePerMinute = 1, maxTrackedClients = max)
            .apply { nowMillis = { 1_000L } }

        repeat(5_000) { limiter.tryAcquire("ip-$it") }

        assertTrue(limiter.trackedClients() <= max, "tracked clients must stay within maximumSize")
    }

    @Test
    fun `allows up to the configured limit then blocks within a window`() {
        val limiter = FixedWindowRateLimiter(ratePerMinute = 3).apply { nowMillis = { 1_000L } }

        assertTrue(limiter.tryAcquire("ip"))
        assertTrue(limiter.tryAcquire("ip"))
        assertTrue(limiter.tryAcquire("ip"))
        assertFalse(limiter.tryAcquire("ip"))
    }

    @Test
    fun `tracks each client independently`() {
        val limiter = FixedWindowRateLimiter(ratePerMinute = 1).apply { nowMillis = { 1_000L } }

        assertTrue(limiter.tryAcquire("a"))
        assertFalse(limiter.tryAcquire("a"))
        assertTrue(limiter.tryAcquire("b"))
    }

    @Test
    fun `the budget refills when the fixed window rolls over`() {
        var now = 0L
        val limiter = FixedWindowRateLimiter(ratePerMinute = 1).apply { nowMillis = { now } }

        assertTrue(limiter.tryAcquire("ip"))
        assertFalse(limiter.tryAcquire("ip"))
        now = 60_000L
        assertTrue(limiter.tryAcquire("ip"))
    }

    @Test
    fun `separate instances keep independent budgets`() {
        // /resolve and /ocr get their own limiter instances, so exhausting one leaves the other
        // untouched — the property that gives each endpoint its own window (decision #103).
        val resolve = FixedWindowRateLimiter(ratePerMinute = 1).apply { nowMillis = { 1_000L } }
        val ocr = FixedWindowRateLimiter(ratePerMinute = 1).apply { nowMillis = { 1_000L } }

        assertTrue(resolve.tryAcquire("ip"))
        assertFalse(resolve.tryAcquire("ip")) // resolve exhausted
        assertTrue(ocr.tryAcquire("ip")) // ocr still has its own budget
    }
}
