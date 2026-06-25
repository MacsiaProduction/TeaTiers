package com.macsia.teatiers.service

/**
 * A global, in-memory per-UTC-day call counter. Two beans use it (see [RateLimiterConfig]):
 *
 *  - the enrichment LLM ceiling (plan.md section 6 "quota protection") — caps total LLM spend across
 *    *all* callers per UTC day; once hit, a Wikidata miss fails closed to UNRESOLVED until midnight;
 *  - the client-diagnostics insert ceiling (decision #111, review finding) — bounds total inserts per
 *    UTC day so the APK-extractable token can't be used to flood the table and fill disk.
 *
 * Both are deliberately GLOBAL counters, not per-IP limiters: the per-IP [ClientRateLimiter] bounds one
 * caller's burst, but behind NAT many users share an IP, and the diagnostics endpoint never reads the
 * client IP at all. In-memory is sufficient for the single-server deployment (rule 40-devops): a restart
 * resets the day's count, which only ever loosens the cap — acceptable for a cost/disk-protection bound.
 *
 * The cap is read through [cap] on every [tryAcquire] so a live property change takes effect immediately.
 * A non-positive cap means unlimited.
 */
class DailyBudget(
    private val cap: () -> Int,
) {

    // Swappable in tests to exercise the day rollover without waiting.
    internal var nowMillis: () -> Long = System::currentTimeMillis

    private val lock = Any()
    private var dayIndex: Long = Long.MIN_VALUE
    private var count: Int = 0

    /**
     * Consumes one call from today's budget. Returns true if within the cap (and the call may proceed),
     * false once the cap is reached. A non-positive cap means unlimited.
     */
    fun tryAcquire(): Boolean {
        val cap = cap()
        if (cap <= 0) return true
        synchronized(lock) {
            val today = nowMillis() / DAY_MS
            if (today != dayIndex) {
                dayIndex = today
                count = 0
            }
            if (count >= cap) return false
            count += 1
            return true
        }
    }

    private companion object {
        const val DAY_MS = 86_400_000L
    }
}
