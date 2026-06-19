package com.macsia.teatiers.data.diagnostics

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

/** Pure wipe-detection logic for the out-of-Room migration sentinel (decision #111). */
class DetectWipeTest {

    private val U = DiagnosticsPreferences.UNKNOWN

    private fun state(
        version: Int = 1, db: Int = 6, boards: Int = 0, teas: Int = 0, photos: Int = 0, destructive: Boolean = false,
    ) = SentinelState(version, db, boards, teas, photos, destructive)

    @Test
    fun `fresh install reports nothing (no baseline, nothing to lose)`() {
        val prev = state(version = U, db = U, boards = U, teas = U, photos = U)
        // First launch reseeds a sample board, so counts aren't all zero.
        assertNull(detectWipe(prev, RowCounts(boards = 1, teas = 0, photos = 0), currentDbVersion = 6, currentVersionCode = 1))
    }

    @Test
    fun `a normal relaunch with data intact reports nothing`() {
        val prev = state(version = 1, db = 6, boards = 2, teas = 5, photos = 3)
        assertNull(detectWipe(prev, RowCounts(2, 5, 3), currentDbVersion = 6, currentVersionCode = 1))
    }

    @Test
    fun `an update that empties the database reports before and after counts`() {
        val prev = state(version = 1, db = 6, boards = 2, teas = 5, photos = 3)

        val counts = detectWipe(prev, RowCounts(0, 0, 0), currentDbVersion = 6, currentVersionCode = 2)

        assertEquals(
            mapOf(
                "boards_before" to 2, "boards_after" to 0,
                "teas_before" to 5, "teas_after" to 0,
                "photos_before" to 3, "photos_after" to 0,
            ),
            counts,
        )
    }

    @Test
    fun `the destructive-migration flag reports even when a sample board was reseeded`() {
        // Room dropped the tables (callback fired); the sample provider then reseeded one board, so
        // counts aren't all zero — the count check alone would miss it, but the flag catches it.
        val prev = state(version = 1, db = 6, boards = 2, teas = 5, photos = 3, destructive = true)

        val counts = detectWipe(prev, RowCounts(1, 0, 0), currentDbVersion = 6, currentVersionCode = 2)

        assertEquals(1, counts!!["boards_after"])
        assertEquals(2, counts["boards_before"])
    }

    @Test
    fun `the destructive flag on a fresh install reports nothing`() {
        val prev = state(version = U, db = U, boards = U, teas = U, photos = U, destructive = true)
        assertNull(detectWipe(prev, RowCounts(1, 0, 0), currentDbVersion = 6, currentVersionCode = 1))
    }

    @Test
    fun `a proper migration that preserves data across a schema bump reports nothing`() {
        // The key false-positive guard: db version changed (5 -> 6) but the rows survived.
        val prev = state(version = 1, db = 5, boards = 2, teas = 5, photos = 3)
        assertNull(detectWipe(prev, RowCounts(2, 5, 3), currentDbVersion = 6, currentVersionCode = 1))
    }
}
