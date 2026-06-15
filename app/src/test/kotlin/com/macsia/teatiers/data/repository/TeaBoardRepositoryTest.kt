package com.macsia.teatiers.data.repository

import com.macsia.teatiers.data.db.toSeedEntities
import com.macsia.teatiers.data.sample.SampleBoardProvider
import com.macsia.teatiers.domain.model.Board
import com.macsia.teatiers.domain.model.Tea
import com.macsia.teatiers.domain.model.TeaType
import com.macsia.teatiers.domain.model.Tier
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

@OptIn(ExperimentalCoroutinesApi::class)
class TeaBoardRepositoryTest {

    private fun tea(id: String, type: TeaType = TeaType.GREEN) = Tea(id = id, nameRu = id, type = type)

    private val seededBoard = Board(
        id = "b",
        name = "Доска",
        tiers = listOf(Tier("s", "S", 0), Tier("a", "A", 1)),
        placements = mapOf("s" to listOf(tea("green")), "a" to emptyList()),
        unranked = listOf(tea("tray")),
    )

    /** Builds a repository over a fake DAO already holding [seededBoard], so init does not re-seed. */
    private suspend fun TestScope.repositoryWithSeed(): TeaBoardRepository {
        val dao = FakeTeaDao()
        val seed = listOf(seededBoard).toSeedEntities()
        dao.seed(seed.boards, seed.tiers, seed.teas, seed.flavors, seed.purchases)
        return TeaBoardRepository(dao, backgroundScope, SampleBoardProvider())
    }

    @Test
    fun `boards exposes the seeded aggregate`() = runTest(UnconfinedTestDispatcher()) {
        val repository = repositoryWithSeed()
        advanceUntilIdle()

        val boards = repository.boards.value
        assertEquals(listOf("b"), boards.map { it.id })
        val board = boards.single()
        assertEquals(listOf("s", "a"), board.tiers.map { it.id })
        assertEquals(listOf("green"), board.placements.getValue("s").map { it.nameRu })
        assertEquals(emptyList<String>(), board.placements.getValue("a").map { it.nameRu })
        assertEquals(listOf("tray"), board.unranked.map { it.nameRu })
    }

    @Test
    fun `addTea places a tea in a known tier`() = runTest(UnconfinedTestDispatcher()) {
        val repository = repositoryWithSeed()
        advanceUntilIdle()

        repository.addTea("b", tea("oolong", TeaType.OOLONG), tierId = "a")
        advanceUntilIdle()

        val board = repository.boards.value.single()
        assertEquals(listOf("oolong"), board.placements.getValue("a").map { it.nameRu })
    }

    @Test
    fun `addTea with an unknown tier falls back to the unranked tray`() = runTest(UnconfinedTestDispatcher()) {
        val repository = repositoryWithSeed()
        advanceUntilIdle()

        repository.addTea("b", tea("ghost"), tierId = "does-not-exist")
        advanceUntilIdle()

        val board = repository.boards.value.single()
        assertTrue(board.unranked.any { it.nameRu == "ghost" })
        assertTrue(board.placements.values.flatten().none { it.nameRu == "ghost" })
    }

    @Test
    fun `tea lookup resolves a tea by board and id`() = runTest(UnconfinedTestDispatcher()) {
        val repository = repositoryWithSeed()
        advanceUntilIdle()

        // Seeded teas carry a board-unique id ("<boardId>-<teaId>").
        assertEquals("green", repository.tea("b", "b-green")?.nameRu)
        assertEquals(null, repository.tea("b", "missing"))
    }
}
