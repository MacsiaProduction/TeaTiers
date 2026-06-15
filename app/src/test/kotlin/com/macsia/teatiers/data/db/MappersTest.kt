package com.macsia.teatiers.data.db

import com.macsia.teatiers.domain.model.Board
import com.macsia.teatiers.domain.model.FlavorDimension
import com.macsia.teatiers.domain.model.FlavorScore
import com.macsia.teatiers.domain.model.Placement
import com.macsia.teatiers.domain.model.PurchaseLocation
import com.macsia.teatiers.domain.model.Tea
import com.macsia.teatiers.domain.model.TeaType
import com.macsia.teatiers.domain.model.Tier
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class MappersTest {

    private fun tea(
        id: String,
        type: TeaType = TeaType.GREEN,
        flavor: List<FlavorScore> = emptyList(),
        purchases: List<PurchaseLocation> = emptyList(),
    ) = Tea(id = id, nameRu = id, type = type, flavor = flavor, purchaseLocations = purchases)

    private fun place(boardId: String, tea: Tea): Placement =
        Placement(placementId = "$boardId-${tea.id}", tea = tea)

    @Test
    fun `toSeedEntities emits one tea row + one placement per board with placement-then-tray positions`() {
        val board = Board(
            id = "b1",
            name = "Board One",
            // deliberately out of order to prove the seed sorts tiers by position
            tiers = listOf(Tier("a", "A", 1), Tier("s", "S", 0)),
            placements = mapOf(
                "s" to listOf(place("b1", tea("green", TeaType.GREEN))),
                "a" to listOf(place("b1", tea("black", TeaType.BLACK))),
            ),
            unranked = listOf(place("b1", tea("white", TeaType.WHITE))),
        )

        val seed = listOf(board).toSeedEntities()

        assertEquals(1, seed.boards.size)
        assertEquals(0, seed.boards.first().position)
        // user-tea rows (board-agnostic, decisions.md #42)
        assertEquals(listOf("green", "black", "white"), seed.teas.map { it.id })
        // placements carry the per-board tier + ordered position (s, a, then tray)
        assertEquals(listOf("b1-green", "b1-black", "b1-white"), seed.placements.map { it.id })
        assertEquals(listOf("s", "a", null), seed.placements.map { it.tierId })
        assertEquals(listOf(0, 1, 2), seed.placements.map { it.position })
        assertEquals(listOf("b1", "b1", "b1"), seed.placements.map { it.boardId })
    }

    @Test
    fun `toSeedEntities deduplicates teas across boards but emits one placement per board`() {
        // Same tea (same id) referenced by two different boards.
        val shared = tea("shared", TeaType.OOLONG)
        val first = Board(
            id = "b1",
            name = "B1",
            tiers = listOf(Tier("b1-s", "S", 0)),
            placements = mapOf("b1-s" to listOf(place("b1", shared))),
            unranked = emptyList(),
        )
        val second = Board(
            id = "b2",
            name = "B2",
            tiers = listOf(Tier("b2-s", "S", 0)),
            placements = mapOf("b2-s" to listOf(place("b2", shared))),
            unranked = emptyList(),
        )

        val seed = listOf(first, second).toSeedEntities()

        // Only one user-tea row...
        assertEquals(listOf("shared"), seed.teas.map { it.id })
        // ...but two placements, one per board.
        assertEquals(setOf("b1-shared", "b2-shared"), seed.placements.map { it.id }.toSet())
        assertEquals(setOf("b1", "b2"), seed.placements.map { it.boardId }.toSet())
    }

    @Test
    fun `toSeedEntities orders flavors by position and tags purchase kinds`() {
        val tea = tea(
            id = "t",
            type = TeaType.OOLONG,
            flavor = listOf(
                FlavorScore(FlavorDimension.ROASTED, 5),
                FlavorScore(FlavorDimension.SWEETNESS, 3),
            ),
            purchases = listOf(
                PurchaseLocation.FreeText("Рынок", label = "метка"),
                PurchaseLocation.Marketplace("https://shop.example", label = "магазин"),
            ),
        )
        val board = Board(
            "b",
            "B",
            listOf(Tier("s", "S", 0)),
            mapOf("s" to listOf(place("b", tea))),
            emptyList(),
        )

        val seed = listOf(board).toSeedEntities()

        assertEquals(listOf("ROASTED", "SWEETNESS"), seed.flavors.map { it.dimension })
        assertEquals(listOf(0, 1), seed.flavors.map { it.position })
        assertEquals(listOf(5, 3), seed.flavors.map { it.intensity })
        assertEquals(listOf("TEXT", "URL"), seed.purchases.map { it.kind })
        assertEquals(listOf("Рынок", "https://shop.example"), seed.purchases.map { it.value })
        // Purchase ids are derived from the user-tea id (no longer the board-prefixed row id).
        assertEquals(listOf("t-p0", "t-p1"), seed.purchases.map { it.id })
    }

    @Test
    fun `BoardWithChildren toDomain sorts tiers, groups placements, and converts enums`() {
        val board = BoardEntity("b", "B", 0)
        val tierS = TierEntity("s", "b", "S", 0, null)
        val tierA = TierEntity("a", "b", "A", 1, 0xFF00FF00L)

        val greenTea = TeaEntity("green", "Зелёный", null, null, null, "GREEN", null, null, null)
        val tray = TeaEntity("x", "Без тира", null, null, null, "WHITE", null, null, null)

        val greenPlacement = PlacementWithTea(
            placement = PlacementEntity(id = "b-green", boardId = "b", teaId = "green", tierId = "s", position = 0),
            tea = listOf(
                TeaWithChildren(
                    tea = greenTea,
                    flavors = listOf(FlavorEntity("green", "GRASSY", 4, 0)),
                    purchases = emptyList(),
                    photos = emptyList(),
                ),
            ),
        )
        val trayPlacement = PlacementWithTea(
            placement = PlacementEntity(id = "b-x", boardId = "b", teaId = "x", tierId = null, position = 2),
            tea = listOf(
                TeaWithChildren(
                    tea = tray,
                    flavors = emptyList(),
                    purchases = listOf(PurchaseLocationEntity("p", "x", 0, "URL", "магазин", "https://y.example")),
                    photos = emptyList(),
                ),
            ),
        )

        // Tiers + placements out of order to prove sorting.
        val aggregate = BoardWithChildren(
            board = board,
            tiers = listOf(tierA, tierS),
            placements = listOf(trayPlacement, greenPlacement),
        )

        val domain = aggregate.toDomain()

        assertEquals(listOf("s", "a"), domain.tiers.map { it.id })
        assertEquals(listOf("Зелёный"), domain.placements.getValue("s").map { it.tea.nameRu })
        assertEquals(emptyList<String>(), domain.placements.getValue("a").map { it.tea.nameRu })
        assertEquals(listOf("Без тира"), domain.unranked.map { it.tea.nameRu })
        assertEquals(TeaType.GREEN, domain.placements.getValue("s").first().tea.type)
        assertEquals(
            FlavorDimension.GRASSY,
            domain.placements.getValue("s").first().tea.flavor.first().dimension,
        )
        assertEquals(0xFF00FF00L, domain.tiers.first { it.id == "a" }.colorArgb)

        val purchase = domain.unranked.first().tea.purchaseLocations.first()
        assertTrue(purchase is PurchaseLocation.Marketplace)
        assertEquals("https://y.example", (purchase as PurchaseLocation.Marketplace).url)
        assertEquals("магазин", purchase.label)
    }

    @Test
    fun `purchase entity maps TEXT to free text and keeps the label`() {
        val entity = PurchaseLocationEntity("p", "t", 0, "TEXT", "метка", "Описание места")

        val domain = entity.toDomain()

        assertTrue(domain is PurchaseLocation.FreeText)
        assertEquals("Описание места", (domain as PurchaseLocation.FreeText).text)
        assertEquals("метка", domain.label)
    }

    @Test
    fun `placement with a null tier maps to the unranked tray`() {
        val board = BoardEntity("b", "B", 0)
        val trayTea = TeaEntity("t", "Чай", null, null, null, "BLACK", null, null, null)
        val tray = PlacementWithTea(
            placement = PlacementEntity(id = "b-t", boardId = "b", teaId = "t", tierId = null, position = 0),
            tea = listOf(
                TeaWithChildren(tea = trayTea, flavors = emptyList(), purchases = emptyList(), photos = emptyList()),
            ),
        )

        val domain = BoardWithChildren(board, tiers = emptyList(), placements = listOf(tray)).toDomain()

        assertEquals(listOf("Чай"), domain.unranked.map { it.tea.nameRu })
        assertNull(domain.placements["missing"])
    }
}
