package com.macsia.teatiers.service

import com.macsia.teatiers.client.LlmProperties
import org.springframework.stereotype.Component

/**
 * Global daily ceiling on enrichment LLM calls (plan.md section 6 "quota protection"). The per-IP
 * [ClientRateLimiter] bounds one caller's burst, but behind NAT / shared mobile networks many users
 * share an IP, so this caps total spend across *all* callers per UTC day. Once the cap is hit a
 * Wikidata miss fails closed to `UNRESOLVED` (no stub, no LLM call) until the next UTC midnight.
 *
 * In-memory is sufficient for the single-server deployment (rule 40-devops); a restart resets the
 * day's count, which only ever loosens the cap, never tightens it — acceptable for cost protection.
 */
@Component
class LlmDailyBudget(
    private val props: LlmProperties,
) {

    // Swappable in tests to exercise the day rollover without waiting.
    internal var nowMillis: () -> Long = System::currentTimeMillis

    private val lock = Any()
    private var dayIndex: Long = Long.MIN_VALUE
    private var count: Int = 0

    /**
     * Consumes one call from today's budget. Returns true if within the cap (and the call may proceed),
     * false once [LlmProperties.dailyCallCap] is reached. A non-positive cap means unlimited.
     */
    fun tryAcquire(): Boolean {
        val cap = props.dailyCallCap
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
