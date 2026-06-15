package com.macsia.teatiers.viewmodel

import com.macsia.teatiers.domain.model.Board
import com.macsia.teatiers.domain.model.Placement
import com.macsia.teatiers.domain.model.Tea
import com.macsia.teatiers.domain.model.TeaType
import com.macsia.teatiers.domain.model.Tier
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class BoardModelsTest {

    private fun place(boardId: String, id: String, type: TeaType): Placement =
        Placement(placementId = "$boardId-$id", tea = Tea(id = id, nameRu = id, type = type))

    @Test
    fun `toUiState orders tiers by position and attaches placements`() {
        val board = Board(
            id = "b",
            name = "Board",
            tiers = listOf(Tier(id = "a", label = "A", position = 1), Tier(id = "s", label = "S", position = 0)),
            placements = mapOf("s" to listOf(place("b", "t1", TeaType.GREEN))),
            unranked = emptyList(),
        )

        val ui = board.toUiState()

        assertEquals(listOf("s", "a"), ui.tiers.map { it.tier.id })
        assertEquals(listOf("t1"), ui.tiers.first().placements.map { it.tea.id })
        assertEquals(emptyList<String>(), ui.tiers[1].placements.map { it.tea.id })
    }

    @Test
    fun `toSummary counts every placement and keeps distinct tea types in encounter order`() {
        val board = Board(
            id = "b",
            name = "Board",
            tiers = listOf(Tier(id = "s", label = "S", position = 0)),
            placements = mapOf(
                "s" to listOf(place("b", "t1", TeaType.GREEN), place("b", "t2", TeaType.GREEN)),
            ),
            unranked = listOf(place("b", "t3", TeaType.BLACK)),
        )

        val summary = board.toSummary()

        assertEquals(3, summary.teaCount)
        assertEquals(listOf(TeaType.GREEN, TeaType.BLACK), summary.signatureTypes)
    }
}
