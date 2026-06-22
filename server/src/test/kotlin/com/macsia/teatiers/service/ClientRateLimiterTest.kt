package com.macsia.teatiers.service

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Decision #141 / PRIV-P1-1: the per-client token-bucket limiter (replaces the fixed-window limiter whose
 * eviction reset a client to a fresh window). These cover the per-client semantics and the memory bound.
 *
 * NOTE on the churn defect: the per-client limiter's improvement is in TIME eviction (an idle bucket is only
 * evicted once refilled to full, via basedOnTimeForRefillingBucketUpToMax) -- which is wall-clock/eviction
 * dependent and not deterministically unit-testable here. Under extreme SIZE pressure the Caffeine cache can
 * still drop an active entry (a documented, bounded residual); the churn-IMMUNE protection PRIV-P1-1 asks for
 * is the GLOBAL edge bucket -- asserted in TeaControllerTest ("...global edge ceiling is saturated -> 503").
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
    fun `a client's exhausted bucket is unaffected by other clients' traffic (within the cache bound)`() {
        // Per-client isolation under load: other clients' requests (no size eviction here -- well within the
        // bound) neither share nor refill the victim's bucket. (The eviction-RESET case is the global edge
        // bucket's job; see the class KDoc + TeaControllerTest.)
        val limiter = ClientRateLimiter(ratePerMinute = 1, maxTrackedClients = 10_000)
        assertTrue(limiter.tryAcquire("victim"))
        assertFalse(limiter.tryAcquire("victim"))

        repeat(500) { limiter.tryAcquire("other-$it") }

        assertFalse(limiter.tryAcquire("victim"), "other clients' traffic must not refill the victim's bucket")
    }

    @Test
    fun `tracked client state stays bounded under a flood of distinct ids (review P1-9)`() {
        val max = 50L
        val limiter = ClientRateLimiter(ratePerMinute = 1, maxTrackedClients = max)
        repeat(5_000) { limiter.tryAcquire("ip-$it") }
        assertTrue(limiter.trackedClients() <= max, "the per-client store must stay within maximumSize")
    }
}
