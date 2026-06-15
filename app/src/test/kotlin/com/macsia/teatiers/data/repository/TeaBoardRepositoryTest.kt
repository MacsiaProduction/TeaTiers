package com.macsia.teatiers.data.repository

import com.macsia.teatiers.data.db.toSeedEntities
import com.macsia.teatiers.data.sample.SampleBoardProvider
import com.macsia.teatiers.domain.model.Board
import com.macsia.teatiers.domain.model.FlavorDimension
import com.macsia.teatiers.domain.model.FlavorScore
import com.macsia.teatiers.domain.model.PurchaseLocation
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

    @Test
    fun `moveTea re-ranks a tea into another tier`() = runTest(UnconfinedTestDispatcher()) {
        val repository = repositoryWithSeed()
        advanceUntilIdle()

        repository.moveTea("b", teaId = "b-green", targetTierId = "a", targetIndex = 0)
        advanceUntilIdle()

        val board = repository.boards.value.single()
        assertEquals(emptyList<String>(), board.placements.getValue("s").map { it.nameRu })
        assertEquals(listOf("green"), board.placements.getValue("a").map { it.nameRu })
    }

    @Test
    fun `moveTea can drop a tea back into the unranked tray`() = runTest(UnconfinedTestDispatcher()) {
        val repository = repositoryWithSeed()
        advanceUntilIdle()

        repository.moveTea("b", teaId = "b-green", targetTierId = null, targetIndex = 0)
        advanceUntilIdle()

        val board = repository.boards.value.single()
        assertEquals(emptyList<String>(), board.placements.getValue("s").map { it.nameRu })
        assertTrue(board.unranked.any { it.nameRu == "green" })
    }

    @Test
    fun `addTier appends a new tier to the board`() = runTest(UnconfinedTestDispatcher()) {
        val repository = repositoryWithSeed()
        advanceUntilIdle()

        repository.addTier("b", label = "  Эксперимент  ")
        advanceUntilIdle()

        val tiers = repository.boards.value.single().tiers
        assertEquals(listOf("S", "A", "Эксперимент"), tiers.map { it.label })
        assertEquals(2, tiers.last().position)
    }

    @Test
    fun `renameTier trims the label and ignores blanks`() = runTest(UnconfinedTestDispatcher()) {
        val repository = repositoryWithSeed()
        advanceUntilIdle()

        repository.renameTier("b", tierId = "s", label = "  Топ  ")
        repository.renameTier("b", tierId = "a", label = "   ")
        advanceUntilIdle()

        val tiers = repository.boards.value.single().tiers.associateBy { it.id }
        assertEquals("Топ", tiers.getValue("s").label)
        assertEquals("A", tiers.getValue("a").label)
    }

    @Test
    fun `setTierColor sets then clears the override`() = runTest(UnconfinedTestDispatcher()) {
        val repository = repositoryWithSeed()
        advanceUntilIdle()

        repository.setTierColor("b", tierId = "s", colorArgb = 0xFF356A4BL)
        advanceUntilIdle()
        assertEquals(0xFF356A4BL, repository.boards.value.single().tiers.first { it.id == "s" }.colorArgb)

        repository.setTierColor("b", tierId = "s", colorArgb = null)
        advanceUntilIdle()
        assertEquals(null, repository.boards.value.single().tiers.first { it.id == "s" }.colorArgb)
    }

    @Test
    fun `reorderTiers moves a tier to the front`() = runTest(UnconfinedTestDispatcher()) {
        val repository = repositoryWithSeed()
        advanceUntilIdle()

        repository.reorderTiers("b", orderedTierIds = listOf("a", "s"))
        advanceUntilIdle()

        assertEquals(listOf("a", "s"), repository.boards.value.single().tiers.map { it.id })
    }

    @Test
    fun `removeTier deletes the tier and drops its teas into the tray`() = runTest(UnconfinedTestDispatcher()) {
        val repository = repositoryWithSeed()
        advanceUntilIdle()

        repository.removeTier("b", tierId = "s")
        advanceUntilIdle()

        val board = repository.boards.value.single()
        assertEquals(listOf("a"), board.tiers.map { it.id })
        assertTrue(board.unranked.any { it.nameRu == "green" })
    }

    @Test
    fun `updateTea rewrites editable fields and replaces children, preserving placement`() =
        runTest(UnconfinedTestDispatcher()) {
            val repository = repositoryWithSeed()
            advanceUntilIdle()

            val edited = Tea(
                id = "ignored-on-update",
                nameRu = "Зелёный (новое)",
                nameEn = "Green (new)",
                type = TeaType.OOLONG,
                origin = "Фуцзянь",
                flavor = listOf(
                    FlavorScore(FlavorDimension.FLORAL, 4),
                    FlavorScore(FlavorDimension.SWEETNESS, 2),
                ),
                notes = "ред.",
                purchaseLocations = listOf(
                    PurchaseLocation.FreeText("Лавка", "поездка"),
                    PurchaseLocation.Marketplace("https://shop.example", "интернет"),
                ),
            )

            repository.updateTea("b", teaId = "b-green", tea = edited)
            advanceUntilIdle()

            val board = repository.boards.value.single()
            // tier + position are preserved (still in tier "s") even though the form rewrote everything else
            assertEquals(listOf("Зелёный (новое)"), board.placements.getValue("s").map { it.nameRu })
            val updated = board.placements.getValue("s").single()
            assertEquals(TeaType.OOLONG, updated.type)
            assertEquals("Фуцзянь", updated.origin)
            assertEquals("ред.", updated.notes)
            assertEquals(listOf(FlavorDimension.FLORAL, FlavorDimension.SWEETNESS), updated.flavor.map { it.dimension })
            assertEquals(listOf(4, 2), updated.flavor.map { it.intensity })
            assertEquals(2, updated.purchaseLocations.size)
            assertTrue(updated.purchaseLocations[0] is PurchaseLocation.FreeText)
            assertTrue(updated.purchaseLocations[1] is PurchaseLocation.Marketplace)
            // tray and the other tier are unaffected
            assertEquals(listOf("tray"), board.unranked.map { it.nameRu })
            assertTrue(board.placements.getValue("a").isEmpty())
        }

    @Test
    fun `updateTea on an unknown tea is a no-op`() = runTest(UnconfinedTestDispatcher()) {
        val repository = repositoryWithSeed()
        advanceUntilIdle()
        val before = repository.boards.value

        repository.updateTea("b", teaId = "missing", tea = tea("ghost"))
        advanceUntilIdle()

        assertEquals(before, repository.boards.value)
    }
}
