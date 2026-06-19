package com.macsia.teatiers.service

import com.macsia.teatiers.controller.ClientDiagnosticsProperties
import org.springframework.stereotype.Component

/**
 * Global daily ceiling on accepted client-diagnostic reports (decision #111, review finding). The
 * endpoint's shared token ships in the APK and is explicitly NOT a security boundary, so an attacker
 * who extracts it could otherwise flood `client_diagnostic` and fill the shared Postgres volume. This
 * caps the **total** inserts per UTC day, bounding disk growth absolutely.
 *
 * It is deliberately a GLOBAL counter, NOT a per-IP limiter: the diagnostics endpoint never reads or
 * stores the client IP (a confirmed privacy property of this feature), so anti-abuse must not depend
 * on it. The tradeoff — a flood can exhaust the day's budget and crowd out legitimate reports — is
 * acceptable for a best-effort, opt-in diagnostics channel (we lose some reports that day; the catalog
 * service stays up and disk stays bounded).
 *
 * In-memory is sufficient for the single-server deployment (rule 40-devops); a restart resets the
 * day's count, which only ever loosens the cap — acceptable for a disk-protection bound.
 */
@Component
class DiagnosticsDailyBudget(
    private val props: ClientDiagnosticsProperties,
) {

    // Swappable in tests to exercise the day rollover without waiting.
    internal var nowMillis: () -> Long = System::currentTimeMillis

    private val lock = Any()
    private var dayIndex: Long = Long.MIN_VALUE
    private var count: Int = 0

    /**
     * Consumes one report from today's budget. Returns true if within the cap (the report may be
     * stored), false once [ClientDiagnosticsProperties.dailyCap] is reached. A non-positive cap means
     * unlimited.
     */
    fun tryAcquire(): Boolean {
        val cap = props.dailyCap
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
