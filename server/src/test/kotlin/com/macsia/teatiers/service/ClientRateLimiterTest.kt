package com.macsia.teatiers.service

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Decision #141 / PRIV-P1-1: the per-client token-bucket limiter. The old fixed-window limiter reset an
 * evicted client to a fresh window (count=0); the token bucket's refill state survives, and an exhausted
 * client is not handed a fresh allowance by other clients' traffic.
 */
class ClientRateLimiterTest {

    @Test
    fun `allows up to the configured limit then blocks within the window`() {
        val limiter = ClientRateLimiter(ratePerMinute = 3)
        assertTrue(limiter.tryAcquire("ip"))
        assertTrue(limiter.tryAcquire("ip"))
        assertTrue(limiter.tryAcquire("ip"))
        assertFalse(limiter.tryAcquire("ip"), "the 4th call within the minute is over budget")
    }

    @Test
    fun `tracks each client independently`() {
        val limiter = ClientRateLimiter(ratePerMinute = 1)
        assertTrue(limiter.tryAcquire("a"))
        assertFalse(limiter.tryAcquire("a"))
        assertTrue(limiter.tryAcquire("b"), "b has its own bucket")
    }

    @Test
    fun `an exhausted client is not reset by other clients' traffic (PRIV-P1-1)`() {
        // The fixed-window defect: a flood of distinct ids could evict the victim's entry, which was then
        // re-created at count=0 -> a fresh budget. With the token bucket (refill-based eviction + a generous
        // bound so the victim is not size-evicted), the victim stays blocked across other clients' traffic.
        val limiter = ClientRateLimiter(ratePerMinute = 1, maxTrackedClients = 10_000)
        assertTrue(limiter.tryAcquire("victim"))
        assertFalse(limiter.tryAcquire("victim"))

        repeat(500) { limiter.tryAcquire("other-$it") }

        assertFalse(limiter.tryAcquire("victim"), "other clients' churn must not refill the victim's budget")
    }

    @Test
    fun `tracked client state stays bounded under a flood of distinct ids (review P1-9)`() {
        val max = 50L
        val limiter = ClientRateLimiter(ratePerMinute = 1, maxTrackedClients = max)
        repeat(5_000) { limiter.tryAcquire("ip-$it") }
        assertTrue(limiter.trackedClients() <= max, "the per-client store must stay within maximumSize")
    }
}
