package com.macsia.teatiers.data.repository

import com.macsia.teatiers.data.db.toSeedEntities
import com.macsia.teatiers.data.sample.SampleBoardProvider
import com.macsia.teatiers.domain.model.Board
import com.macsia.teatiers.domain.model.FlavorDimension
import com.macsia.teatiers.domain.model.FlavorScore
import com.macsia.teatiers.domain.model.Placement
import com.macsia.teatiers.domain.model.PurchaseLocation
import com.macsia.teatiers.domain.model.Tea
import com.macsia.teatiers.domain.model.TeaType
import com.macsia.teatiers.domain.model.Tier
import com.macsia.teatiers.domain.model.TierTemplate
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

@OptIn(ExperimentalCoroutinesApi::class)
class TeaBoardRepositoryTest {

    private fun tea(id: String, type: TeaType = TeaType.GREEN, nameRu: String = id) =
        Tea(id = id, nameRu = nameRu, type = type)

    private fun place(boardId: String, tea: Tea): Placement =
        Placement(placementId = "$boardId-${tea.id}", tea = tea)

    /** A tiny seed board used by most tests; tiers s + a, one ranked tea, one tray tea. */
    private val seededBoard = Board(
        id = "b",
        name = "Доска",
        tiers = listOf(Tier("s", "S", 0), Tier("a", "A", 1)),
        placements = mapOf(
            "s" to listOf(place("b", tea("green"))),
            "a" to emptyList(),
        ),
        unranked = listOf(place("b", tea("tray"))),
    )

    /** Builds a repository over a fake DAO already holding [seededBoard], so init does not re-seed. */
    private suspend fun TestScope.repositoryWithSeed(boards: List<Board> = listOf(seededBoard)): TeaBoardRepository {
        val dao = FakeTeaDao()
        val seed = boards.toSeedEntities()
        dao.seed(seed.boards, seed.tiers, seed.teas, seed.placements, seed.flavors, seed.purchases)
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
        assertEquals(listOf("green"), board.placements.getValue("s").map { it.tea.nameRu })
        assertEquals(emptyList<String>(), board.placements.getValue("a").map { it.tea.nameRu })
        assertEquals(listOf("tray"), board.unranked.map { it.tea.nameRu })
    }

    @Test
    fun `addTea places a brand-new tea in a known tier`() = runTest(UnconfinedTestDispatcher()) {
        val repository = repositoryWithSeed()
        advanceUntilIdle()

        repository.addTea("b", tea("oolong", TeaType.OOLONG, nameRu = "Улун"), tierId = "a")
        advanceUntilIdle()

        val board = repository.boards.value.single()
        assertEquals(listOf("Улун"), board.placements.getValue("a").map { it.tea.nameRu })
    }

    @Test
    fun `addTea with an unknown tier falls back to the unranked tray`() = runTest(UnconfinedTestDispatcher()) {
        val repository = repositoryWithSeed()
        advanceUntilIdle()

        repository.addTea("b", tea("ghost", nameRu = "Призрак"), tierId = "does-not-exist")
        advanceUntilIdle()

        val board = repository.boards.value.single()
        assertTrue(board.unranked.any { it.tea.nameRu == "Призрак" })
        assertTrue(board.placements.values.flatten().none { it.tea.nameRu == "Призрак" })
    }

    @Test
    fun `addTea auto-links to an existing user-tea by Russian name match`() = runTest(UnconfinedTestDispatcher()) {
        // Two boards so the dedup is observable: adding a same-named tea on board2 must reuse
        // the user-tea row introduced by board1's seed.
        val board1 = seededBoard
        val board2 = Board(
            id = "b2",
            name = "Доска 2",
            tiers = listOf(Tier("b2-s", "S", 0)),
            placements = emptyMap(),
            unranked = emptyList(),
        )
        val repository = repositoryWithSeed(listOf(board1, board2))
        advanceUntilIdle()

        // Same name (case + whitespace different on purpose) as the seeded "green" tea.
        repository.addTea("b2", tea("brand-new-id", nameRu = "  GREEN  "), tierId = "b2-s")
        advanceUntilIdle()

        val placedOnBoard2 = repository.boards.value.first { it.id == "b2" }
            .placements.getValue("b2-s").single()
        // The auto-link reused the original tea id ("green"), not the throwaway candidate id.
        assertEquals("green", placedOnBoard2.tea.id)
        // Editing one ripples to the other (verified separately) — for now just confirm both
        // boards reference the same user-tea id.
        val onBoard1 = repository.boards.value.first { it.id == "b" }
            .placements.getValue("s").single()
        assertEquals(onBoard1.tea.id, placedOnBoard2.tea.id)
    }

    @Test
    fun `tea lookup resolves a user-tea by id from any board`() = runTest(UnconfinedTestDispatcher()) {
        val repository = repositoryWithSeed()
        advanceUntilIdle()

        assertEquals("green", repository.tea("green")?.nameRu)
        assertNull(repository.tea("missing"))
    }

    @Test
    fun `moveTea re-ranks a placement into another tier`() = runTest(UnconfinedTestDispatcher()) {
        val repository = repositoryWithSeed()
        advanceUntilIdle()

        repository.moveTea("b", placementId = "b-green", targetTierId = "a", targetIndex = 0)
        advanceUntilIdle()

        val board = repository.boards.value.single()
        assertEquals(emptyList<String>(), board.placements.getValue("s").map { it.tea.nameRu })
        assertEquals(listOf("green"), board.placements.getValue("a").map { it.tea.nameRu })
    }

    @Test
    fun `moveTea can drop a placement back into the unranked tray`() = runTest(UnconfinedTestDispatcher()) {
        val repository = repositoryWithSeed()
        advanceUntilIdle()

        repository.moveTea("b", placementId = "b-green", targetTierId = null, targetIndex = 0)
        advanceUntilIdle()

        val board = repository.boards.value.single()
        assertEquals(emptyList<String>(), board.placements.getValue("s").map { it.tea.nameRu })
        assertTrue(board.unranked.any { it.tea.nameRu == "green" })
    }

    @Test
    fun `removePlacement drops one placement but keeps the user-tea everywhere else`() =
        runTest(UnconfinedTestDispatcher()) {
            val board1 = seededBoard
            val board2 = Board(
                id = "b2",
                name = "Доска 2",
                tiers = listOf(Tier("b2-s", "S", 0)),
                placements = mapOf("b2-s" to listOf(place("b2", tea("green")))),
                unranked = emptyList(),
            )
            val repository = repositoryWithSeed(listOf(board1, board2))
            advanceUntilIdle()

            repository.removePlacement("b-green")
            advanceUntilIdle()

            val onBoard1 = repository.boards.value.first { it.id == "b" }
            val onBoard2 = repository.boards.value.first { it.id == "b2" }
            // Board 1 lost the placement; board 2 kept its own.
            assertTrue(onBoard1.placements.getValue("s").isEmpty())
            assertTrue(onBoard1.unranked.none { it.tea.id == "green" })
            assertEquals(listOf("green"), onBoard2.placements.getValue("b2-s").map { it.tea.id })
            // The user-tea row is still present.
            assertNotNull(repository.tea("green"))
        }

    @Test
    fun `deleteTea cascades to every placement on every board`() = runTest(UnconfinedTestDispatcher()) {
        val board1 = seededBoard
        val board2 = Board(
            id = "b2",
            name = "Доска 2",
            tiers = listOf(Tier("b2-s", "S", 0)),
            placements = mapOf("b2-s" to listOf(place("b2", tea("green")))),
            unranked = emptyList(),
        )
        val repository = repositoryWithSeed(listOf(board1, board2))
        advanceUntilIdle()

        repository.deleteTea("green")
        advanceUntilIdle()

        repository.boards.value.forEach { board ->
            assertTrue(board.placements.values.flatten().none { it.tea.id == "green" })
            assertTrue(board.unranked.none { it.tea.id == "green" })
        }
        assertNull(repository.tea("green"))
    }

    @Test
    fun `placementCountForTea reports how many boards a tea sits on`() = runTest(UnconfinedTestDispatcher()) {
        val board1 = seededBoard
        val board2 = Board(
            id = "b2",
            name = "Доска 2",
            tiers = listOf(Tier("b2-s", "S", 0)),
            placements = mapOf("b2-s" to listOf(place("b2", tea("green")))),
            unranked = emptyList(),
        )
        val repository = repositoryWithSeed(listOf(board1, board2))
        advanceUntilIdle()

        assertEquals(2, repository.placementCountForTea("green"))
        assertEquals(1, repository.placementCountForTea("tray"))
        assertEquals(0, repository.placementCountForTea("missing"))
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
    fun `removeTier deletes the tier and drops its placements into the tray`() = runTest(UnconfinedTestDispatcher()) {
        val repository = repositoryWithSeed()
        advanceUntilIdle()

        repository.removeTier("b", tierId = "s")
        advanceUntilIdle()

        val board = repository.boards.value.single()
        assertEquals(listOf("a"), board.tiers.map { it.id })
        assertTrue(board.unranked.any { it.tea.nameRu == "green" })
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

            repository.updateTea(teaId = "green", tea = edited)
            advanceUntilIdle()

            val board = repository.boards.value.single()
            // tier + position are preserved (still in tier "s") even though the form rewrote everything else
            assertEquals(listOf("Зелёный (новое)"), board.placements.getValue("s").map { it.tea.nameRu })
            val updated = board.placements.getValue("s").single().tea
            assertEquals(TeaType.OOLONG, updated.type)
            assertEquals("Фуцзянь", updated.origin)
            assertEquals("ред.", updated.notes)
            assertEquals(listOf(FlavorDimension.FLORAL, FlavorDimension.SWEETNESS), updated.flavor.map { it.dimension })
            assertEquals(listOf(4, 2), updated.flavor.map { it.intensity })
            assertEquals(2, updated.purchaseLocations.size)
            assertTrue(updated.purchaseLocations[0] is PurchaseLocation.FreeText)
            assertTrue(updated.purchaseLocations[1] is PurchaseLocation.Marketplace)
            assertEquals(listOf("tray"), board.unranked.map { it.tea.nameRu })
            assertTrue(board.placements.getValue("a").isEmpty())
        }

    @Test
    fun `updateTea ripples to every board the tea sits on`() = runTest(UnconfinedTestDispatcher()) {
        val board1 = seededBoard
        val board2 = Board(
            id = "b2",
            name = "Доска 2",
            tiers = listOf(Tier("b2-s", "S", 0)),
            placements = mapOf("b2-s" to listOf(place("b2", tea("green")))),
            unranked = emptyList(),
        )
        val repository = repositoryWithSeed(listOf(board1, board2))
        advanceUntilIdle()

        val edited = tea("green", nameRu = "Зелёный (новое)")
        repository.updateTea(teaId = "green", tea = edited)
        advanceUntilIdle()

        // Both boards now show the new name on their respective placements.
        val onBoard1 = repository.boards.value.first { it.id == "b" }
            .placements.getValue("s").single().tea.nameRu
        val onBoard2 = repository.boards.value.first { it.id == "b2" }
            .placements.getValue("b2-s").single().tea.nameRu
        assertEquals("Зелёный (новое)", onBoard1)
        assertEquals("Зелёный (новое)", onBoard2)
    }

    @Test
    fun `updateTea on an unknown tea is a no-op`() = runTest(UnconfinedTestDispatcher()) {
        val repository = repositoryWithSeed()
        advanceUntilIdle()
        val before = repository.boards.value

        repository.updateTea(teaId = "missing", tea = tea("ghost"))
        advanceUntilIdle()

        assertEquals(before, repository.boards.value)
    }

    @Test
    fun `createBoard with F_TO_S seeds six tiers in S to F order`() = runTest(UnconfinedTestDispatcher()) {
        val repository = repositoryWithSeed()
        advanceUntilIdle()
        val sizeBefore = repository.boards.value.size

        val newId = repository.createBoard(label = "  Утренние пуэры  ", template = TierTemplate.F_TO_S)
        advanceUntilIdle()

        assertNotNull(newId)
        val created = repository.boards.value.first { it.id == newId }
        assertEquals("Утренние пуэры", created.name)
        assertEquals(listOf("S", "A", "B", "C", "D", "F"), created.tiers.map { it.label })
        assertEquals((0..5).toList(), created.tiers.map { it.position })
        assertTrue(created.unranked.isEmpty())
        assertEquals(sizeBefore + 1, repository.boards.value.size)
    }

    @Test
    fun `createBoard with ONE_TO_TEN seeds ten tiers from 10 down to 1`() = runTest(UnconfinedTestDispatcher()) {
        val repository = repositoryWithSeed()
        advanceUntilIdle()

        val newId = repository.createBoard(label = "Дегустация", template = TierTemplate.ONE_TO_TEN)
        advanceUntilIdle()

        val created = repository.boards.value.first { it.id == newId }
        assertEquals((10 downTo 1).map { it.toString() }, created.tiers.map { it.label })
        assertEquals((0..9).toList(), created.tiers.map { it.position })
    }

    @Test
    fun `createBoard with BLANK seeds no tiers`() = runTest(UnconfinedTestDispatcher()) {
        val repository = repositoryWithSeed()
        advanceUntilIdle()

        val newId = repository.createBoard(label = "Эксперимент", template = TierTemplate.BLANK)
        advanceUntilIdle()

        val created = repository.boards.value.first { it.id == newId }
        assertTrue(created.tiers.isEmpty())
    }

    @Test
    fun `createBoard with a blank label is a no-op`() = runTest(UnconfinedTestDispatcher()) {
        val repository = repositoryWithSeed()
        advanceUntilIdle()
        val before = repository.boards.value

        val resultBlank = repository.createBoard(label = "", template = TierTemplate.F_TO_S)
        val resultWhitespace = repository.createBoard(label = "   ", template = TierTemplate.F_TO_S)
        advanceUntilIdle()

        assertNull(resultBlank)
        assertNull(resultWhitespace)
        assertEquals(before, repository.boards.value)
    }

    @Test
    fun `createBoard tier ids are namespaced so two F_TO_S boards do not collide`() =
        runTest(UnconfinedTestDispatcher()) {
            val repository = repositoryWithSeed()
            advanceUntilIdle()

            val firstId = repository.createBoard("Подборка 1", TierTemplate.F_TO_S)
            val secondId = repository.createBoard("Подборка 2", TierTemplate.F_TO_S)
            advanceUntilIdle()

            val first = repository.boards.value.first { it.id == firstId }
            val second = repository.boards.value.first { it.id == secondId }
            assertEquals(first.tiers.map { it.label }, second.tiers.map { it.label })
            assertTrue(first.tiers.map { it.id }.intersect(second.tiers.map { it.id }.toSet()).isEmpty())
        }
}
