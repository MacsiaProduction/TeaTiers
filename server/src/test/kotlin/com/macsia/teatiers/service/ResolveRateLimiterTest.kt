package com.macsia.teatiers.service

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ResolveRateLimiterTest {

    @Test
    fun `allows up to the configured limit then blocks within a window`() {
        val limiter = ResolveRateLimiter(ResolveProperties(ratePerMinute = 3)).apply { nowMillis = { 1_000L } }

        assertTrue(limiter.tryAcquire("ip"))
        assertTrue(limiter.tryAcquire("ip"))
        assertTrue(limiter.tryAcquire("ip"))
        assertFalse(limiter.tryAcquire("ip"))
    }

    @Test
    fun `tracks each client independently`() {
        val limiter = ResolveRateLimiter(ResolveProperties(ratePerMinute = 1)).apply { nowMillis = { 1_000L } }

        assertTrue(limiter.tryAcquire("a"))
        assertFalse(limiter.tryAcquire("a"))
        assertTrue(limiter.tryAcquire("b"))
    }

    @Test
    fun `the budget refills when the fixed window rolls over`() {
        var now = 0L
        val limiter = ResolveRateLimiter(ResolveProperties(ratePerMinute = 1)).apply { nowMillis = { now } }

        assertTrue(limiter.tryAcquire("ip"))
        assertFalse(limiter.tryAcquire("ip"))
        now = 60_000L
        assertTrue(limiter.tryAcquire("ip"))
    }
}
