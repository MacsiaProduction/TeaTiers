package com.macsia.teatiers.data.repository

import com.macsia.teatiers.data.db.PlacementMove
import com.macsia.teatiers.domain.model.Board
import com.macsia.teatiers.domain.model.Placement
import com.macsia.teatiers.domain.model.Tea
import com.macsia.teatiers.domain.model.TeaType
import com.macsia.teatiers.domain.model.Tier
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class MovePlacementTest {

    /** Placement id "p-<id>" so tea "g1" is reachable on this board as "p-g1". */
    private fun placement(id: String): Placement =
        Placement(placementId = "p-$id", tea = Tea(id = id, nameRu = id, type = TeaType.GREEN))

    /** board with tiers s -> [g1, g2], a -> [b1], and an unranked tray [u1]. */
    private fun board() = Board(
        id = "b",
        name = "B",
        tiers = listOf(Tier("s", "S", 0), Tier("a", "A", 1)),
        placements = mapOf(
            "s" to listOf(placement("g1"), placement("g2")),
            "a" to listOf(placement("b1")),
        ),
        unranked = listOf(placement("u1")),
    )

    /** placementId -> (tierId, position) for order-independent assertions. */
    private fun List<PlacementMove>.byPlacement() =
        associate { it.placementId to (it.tierId to it.position) }

    @Test
    fun `move across tiers renumbers both source and target`() {
        val result = computeMovePlacements(board(), placementId = "p-g1", targetTierId = "a", targetIndex = 1)
            .byPlacement()

        // source "s" collapses to just p-g2 at 0; target "a" becomes [p-b1, p-g1]
        assertEquals("s" to 0, result["p-g2"])
        assertEquals("a" to 0, result["p-b1"])
        assertEquals("a" to 1, result["p-g1"])
        assertEquals(3, result.size)
    }

    @Test
    fun `move into the unranked tray uses a null tier`() {
        val result = computeMovePlacements(board(), placementId = "p-b1", targetTierId = null, targetIndex = 0)
            .byPlacement()

        // p-b1 leads the tray; the previous tray placement follows it
        assertEquals(null to 0, result["p-b1"])
        assertEquals(null to 1, result["p-u1"])
    }

    @Test
    fun `an unknown target tier falls back to the tray`() {
        val result = computeMovePlacements(
            board(),
            placementId = "p-g1",
            targetTierId = "does-not-exist",
            targetIndex = 0,
        ).byPlacement()

        assertEquals(null, result["p-g1"]?.first)
        assertEquals(0, result["p-g1"]?.second)
    }

    @Test
    fun `reorder within a tier shifts only that tier`() {
        val wide = board().copy(
            placements = mapOf(
                "s" to listOf(placement("g1"), placement("g2"), placement("g3")),
                "a" to emptyList(),
            ),
        )

        val result = computeMovePlacements(wide, placementId = "p-g1", targetTierId = "s", targetIndex = 2)
            .byPlacement()

        assertEquals("s" to 0, result["p-g2"])
        assertEquals("s" to 1, result["p-g3"])
        assertEquals("s" to 2, result["p-g1"])
    }

    @Test
    fun `a no-op move returns no placements`() {
        // p-g1 is already first in "s"
        assertTrue(computeMovePlacements(board(), placementId = "p-g1", targetTierId = "s", targetIndex = 0).isEmpty())
    }

    @Test
    fun `an unknown placement returns no placements`() {
        assertTrue(computeMovePlacements(board(), placementId = "missing", targetTierId = "a", targetIndex = 0).isEmpty())
    }

    @Test
    fun `an out-of-range index is clamped to the end of the target`() {
        val result = computeMovePlacements(board(), placementId = "p-u1", targetTierId = "a", targetIndex = 99)

        // a had [p-b1]; p-u1 clamps to the end after it
        assertEquals(
            listOf(PlacementMove("p-b1", "a", 0), PlacementMove("p-u1", "a", 1)),
            result,
        )
    }
}
