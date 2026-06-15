package com.macsia.teatiers.data.repository

import com.macsia.teatiers.data.db.TeaPlacement
import com.macsia.teatiers.domain.model.Board
import com.macsia.teatiers.domain.model.Tea
import com.macsia.teatiers.domain.model.TeaType
import com.macsia.teatiers.domain.model.Tier
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class MovePlacementTest {

    private fun tea(id: String) = Tea(id = id, nameRu = id, type = TeaType.GREEN)

    /** board with tiers s -> [g1, g2], a -> [b1], and an unranked tray [u1]. */
    private fun board() = Board(
        id = "b",
        name = "B",
        tiers = listOf(Tier("s", "S", 0), Tier("a", "A", 1)),
        placements = mapOf("s" to listOf(tea("g1"), tea("g2")), "a" to listOf(tea("b1"))),
        unranked = listOf(tea("u1")),
    )

    /** teaId -> (tierId, position) for order-independent assertions. */
    private fun List<TeaPlacement>.byTea() = associate { it.teaId to (it.tierId to it.position) }

    @Test
    fun `move across tiers renumbers both source and target`() {
        val result = computeMovePlacements(board(), teaId = "g1", targetTierId = "a", targetIndex = 1).byTea()

        // source "s" collapses to just g2 at 0; target "a" becomes [b1, g1]
        assertEquals("s" to 0, result["g2"])
        assertEquals("a" to 0, result["b1"])
        assertEquals("a" to 1, result["g1"])
        assertEquals(3, result.size)
    }

    @Test
    fun `move into the unranked tray uses a null tier`() {
        val result = computeMovePlacements(board(), teaId = "b1", targetTierId = null, targetIndex = 0).byTea()

        // b1 leads the tray; the previous tray tea follows it
        assertEquals(null to 0, result["b1"])
        assertEquals(null to 1, result["u1"])
    }

    @Test
    fun `an unknown target tier falls back to the tray`() {
        val result = computeMovePlacements(board(), teaId = "g1", targetTierId = "does-not-exist", targetIndex = 0).byTea()

        assertEquals(null, result["g1"]?.first)
        assertEquals(0, result["g1"]?.second)
    }

    @Test
    fun `reorder within a tier shifts only that tier`() {
        val wide = board().copy(placements = mapOf("s" to listOf(tea("g1"), tea("g2"), tea("g3")), "a" to emptyList()))

        val result = computeMovePlacements(wide, teaId = "g1", targetTierId = "s", targetIndex = 2).byTea()

        assertEquals("s" to 0, result["g2"])
        assertEquals("s" to 1, result["g3"])
        assertEquals("s" to 2, result["g1"])
    }

    @Test
    fun `a no-op move returns no placements`() {
        // g1 is already first in "s"
        assertTrue(computeMovePlacements(board(), teaId = "g1", targetTierId = "s", targetIndex = 0).isEmpty())
    }

    @Test
    fun `an unknown tea returns no placements`() {
        assertTrue(computeMovePlacements(board(), teaId = "missing", targetTierId = "a", targetIndex = 0).isEmpty())
    }

    @Test
    fun `an out-of-range index is clamped to the end of the target`() {
        val result = computeMovePlacements(board(), teaId = "u1", targetTierId = "a", targetIndex = 99)

        // a had [b1]; u1 clamps to the end after it
        assertEquals(
            listOf(TeaPlacement("b1", "a", 0), TeaPlacement("u1", "a", 1)),
            result,
        )
    }
}
