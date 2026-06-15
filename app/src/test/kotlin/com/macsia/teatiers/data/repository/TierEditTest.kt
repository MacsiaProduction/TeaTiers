package com.macsia.teatiers.data.repository

import com.macsia.teatiers.data.db.TeaPlacement
import com.macsia.teatiers.data.db.TierPosition
import com.macsia.teatiers.domain.model.Board
import com.macsia.teatiers.domain.model.Tea
import com.macsia.teatiers.domain.model.TeaType
import com.macsia.teatiers.domain.model.Tier
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class TierEditTest {

    private fun tea(id: String) = Tea(id = id, nameRu = id, type = TeaType.GREEN)

    /** board with tiers s -> [g1, g2], a -> [b1], b -> [], and an unranked tray [u1]. */
    private fun board() = Board(
        id = "b",
        name = "B",
        tiers = listOf(Tier("s", "S", 0), Tier("a", "A", 1), Tier("b", "B", 2)),
        placements = mapOf("s" to listOf(tea("g1"), tea("g2")), "a" to listOf(tea("b1")), "b" to emptyList()),
        unranked = listOf(tea("u1")),
    )

    @Test
    fun `reorder renumbers tiers to the requested order`() {
        val result = computeTierPositions(board(), orderedTierIds = listOf("a", "s", "b"))

        assertEquals(listOf(TierPosition("a", 0), TierPosition("s", 1), TierPosition("b", 2)), result)
    }

    @Test
    fun `an unchanged order writes nothing`() {
        assertTrue(computeTierPositions(board(), orderedTierIds = listOf("s", "a", "b")).isEmpty())
    }

    @Test
    fun `unknown ids are dropped and omitted tiers are appended in board order`() {
        val result = computeTierPositions(board(), orderedTierIds = listOf("b", "ghost"))

        // "b" leads; "s" and "a" follow in their existing order.
        assertEquals(listOf(TierPosition("b", 0), TierPosition("s", 1), TierPosition("a", 2)), result)
    }

    @Test
    fun `removing a tier moves its teas to the end of the tray`() {
        val result = computeTrayReassignment(board(), removedTierId = "s")

        // existing tray (u1) keeps the lead; the removed tier's teas follow, all tray-bound (null).
        assertEquals(
            listOf(TeaPlacement("u1", null, 0), TeaPlacement("g1", null, 1), TeaPlacement("g2", null, 2)),
            result,
        )
    }

    @Test
    fun `removing an empty tier reassigns nothing`() {
        assertTrue(computeTrayReassignment(board(), removedTierId = "b").isEmpty())
    }
}
