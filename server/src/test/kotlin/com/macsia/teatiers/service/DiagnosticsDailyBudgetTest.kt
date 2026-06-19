package com.macsia.teatiers.service

import com.macsia.teatiers.controller.ClientDiagnosticsProperties
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The global daily insert cap that bounds disk growth on /client-diagnostics (review finding). */
class DiagnosticsDailyBudgetTest {

    private val dayMs = 86_400_000L

    private fun budget(cap: Int, now: () -> Long = { 0L }): DiagnosticsDailyBudget =
        DiagnosticsDailyBudget(ClientDiagnosticsProperties(dailyCap = cap)).apply { nowMillis = now }

    @Test
    fun `allows up to the cap then blocks`() {
        val budget = budget(cap = 2)

        assertTrue(budget.tryAcquire())
        assertTrue(budget.tryAcquire())
        assertFalse(budget.tryAcquire())
        assertFalse(budget.tryAcquire())
    }

    @Test
    fun `resets at the next UTC day`() {
        var now = 0L
        val budget = budget(cap = 1) { now }

        assertTrue(budget.tryAcquire())
        assertFalse(budget.tryAcquire())

        now += dayMs
        assertTrue(budget.tryAcquire())
        assertFalse(budget.tryAcquire())
    }

    @Test
    fun `a non-positive cap means unlimited`() {
        val zero = budget(cap = 0)
        val negative = budget(cap = -1)

        repeat(1_000) {
            assertTrue(zero.tryAcquire())
            assertTrue(negative.tryAcquire())
        }
    }
}
