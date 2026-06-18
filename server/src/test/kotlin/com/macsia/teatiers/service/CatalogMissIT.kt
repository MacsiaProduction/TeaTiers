package com.macsia.teatiers.service

import com.macsia.teatiers.AbstractIntegrationTest
import com.macsia.teatiers.repository.CatalogMissRepository
import kotlin.test.Test
import kotlin.test.assertEquals
import org.junit.jupiter.api.BeforeEach
import org.springframework.beans.factory.annotation.Autowired

/**
 * Integration test for the demand-driven miss log (decision #116) against real Postgres: the
 * `ON CONFLICT` upsert inserts on first sight and atomically increments the count on repeats, and
 * normalized variants of the same query collapse to one row. Exercised through [MissLogService] so
 * the @Modifying upsert runs in its transaction (the path `/resolve` actually uses).
 */
class CatalogMissIT : AbstractIntegrationTest() {

    @Autowired
    lateinit var missLog: MissLogService

    @Autowired
    lateinit var repository: CatalogMissRepository

    @BeforeEach
    fun clean() {
        // The container is shared across IT classes and these tests commit, so start from empty.
        repository.deleteAll()
    }

    @Test
    fun `upsert inserts once then increments, and case-or-whitespace variants share a row`() {
        missLog.record("Da Hong Pao")
        missLog.record("  da   hong   pao ") // normalizes to the same key -> increment, not a new row
        missLog.record("Longjing")

        val top = missLog.topMisses(10)

        assertEquals(2, top.size, "two distinct normalized queries")
        assertEquals("da hong pao", top[0].queryNorm)
        assertEquals(2L, top[0].missCount, "the repeated query is counted, ordered first")
        assertEquals("longjing", top[1].queryNorm)
        assertEquals(1L, top[1].missCount)
    }
}
