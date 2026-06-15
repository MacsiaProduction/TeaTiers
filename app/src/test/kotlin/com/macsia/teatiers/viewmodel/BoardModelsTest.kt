package com.macsia.teatiers.viewmodel

import com.macsia.teatiers.domain.model.Board
import com.macsia.teatiers.domain.model.Tea
import com.macsia.teatiers.domain.model.TeaType
import com.macsia.teatiers.domain.model.Tier
import org.junit.Assert.assertEquals
import org.junit.Test

class BoardModelsTest {

    private fun tea(id: String, type: TeaType) = Tea(id = id, nameRu = id, type = type)

    @Test
    fun `toUiState orders tiers by position and attaches placed teas`() {
        val board = Board(
            id = "b",
            name = "Board",
            tiers = listOf(Tier(id = "a", label = "A", position = 1), Tier(id = "s", label = "S", position = 0)),
            placements = mapOf("s" to listOf(tea("t1", TeaType.GREEN))),
            unranked = emptyList(),
        )

        val ui = board.toUiState()

        assertEquals(listOf("s", "a"), ui.tiers.map { it.tier.id })
        assertEquals(listOf("t1"), ui.tiers.first().teas.map { it.id })
        assertEquals(emptyList<String>(), ui.tiers[1].teas.map { it.id })
    }

    @Test
    fun `toSummary counts every tea and keeps distinct types in encounter order`() {
        val board = Board(
            id = "b",
            name = "Board",
            tiers = listOf(Tier(id = "s", label = "S", position = 0)),
            placements = mapOf("s" to listOf(tea("t1", TeaType.GREEN), tea("t2", TeaType.GREEN))),
            unranked = listOf(tea("t3", TeaType.BLACK)),
        )

        val summary = board.toSummary()

        assertEquals(3, summary.teaCount)
        assertEquals(listOf(TeaType.GREEN, TeaType.BLACK), summary.signatureTypes)
    }
}
