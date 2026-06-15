package com.macsia.teatiers.data.db

import com.macsia.teatiers.domain.model.Board
import com.macsia.teatiers.domain.model.FlavorDimension
import com.macsia.teatiers.domain.model.FlavorScore
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

    @Test
    fun `toSeedEntities assigns board-unique ids and placement-then-tray positions`() {
        val board = Board(
            id = "b1",
            name = "Board One",
            // deliberately out of order to prove the seed sorts tiers by position
            tiers = listOf(Tier("a", "A", 1), Tier("s", "S", 0)),
            placements = mapOf(
                "s" to listOf(tea("green", TeaType.GREEN)),
                "a" to listOf(tea("black", TeaType.BLACK)),
            ),
            unranked = listOf(tea("white", TeaType.WHITE)),
        )

        val seed = listOf(board).toSeedEntities()

        assertEquals(1, seed.boards.size)
        assertEquals(0, seed.boards.first().position)
        assertEquals(listOf("b1-green", "b1-black", "b1-white"), seed.teas.map { it.id })
        assertEquals(listOf("s", "a", null), seed.teas.map { it.tierId })
        assertEquals(listOf(0, 1, 2), seed.teas.map { it.position })
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
        val board = Board("b", "B", listOf(Tier("s", "S", 0)), mapOf("s" to listOf(tea)), emptyList())

        val seed = listOf(board).toSeedEntities()

        assertEquals(listOf("ROASTED", "SWEETNESS"), seed.flavors.map { it.dimension })
        assertEquals(listOf(0, 1), seed.flavors.map { it.position })
        assertEquals(listOf(5, 3), seed.flavors.map { it.intensity })
        assertEquals(listOf("TEXT", "URL"), seed.purchases.map { it.kind })
        assertEquals(listOf("Рынок", "https://shop.example"), seed.purchases.map { it.value })
        assertEquals(listOf("b-t-p0", "b-t-p1"), seed.purchases.map { it.id })
    }

    @Test
    fun `BoardWithChildren toDomain sorts tiers, groups teas, and converts enums`() {
        val board = BoardEntity("b", "B", 0)
        val tierS = TierEntity("s", "b", "S", 0, null)
        val tierA = TierEntity("a", "b", "A", 1, 0xFF00FF00L)
        val green = TeaWithChildren(
            tea = TeaEntity("b-green", "b", "s", 0, "Зелёный", null, null, null, "GREEN", null, null, null),
            flavors = listOf(FlavorEntity("b-green", "GRASSY", 4, 0)),
            purchases = emptyList(),
        )
        val tray = TeaWithChildren(
            tea = TeaEntity("b-x", "b", null, 2, "Без тира", null, null, null, "WHITE", null, null, null),
            flavors = emptyList(),
            purchases = listOf(PurchaseLocationEntity("p", "b-x", 0, "URL", "магазин", "https://y.example")),
        )
        // tiers + teas supplied out of order to prove sorting by position
        val aggregate = BoardWithChildren(board, tiers = listOf(tierA, tierS), teas = listOf(tray, green))

        val domain = aggregate.toDomain()

        assertEquals(listOf("s", "a"), domain.tiers.map { it.id })
        assertEquals(listOf("Зелёный"), domain.placements.getValue("s").map { it.nameRu })
        assertEquals(emptyList<String>(), domain.placements.getValue("a").map { it.nameRu })
        assertEquals(listOf("Без тира"), domain.unranked.map { it.nameRu })
        assertEquals(TeaType.GREEN, domain.placements.getValue("s").first().type)
        assertEquals(FlavorDimension.GRASSY, domain.placements.getValue("s").first().flavor.first().dimension)
        assertEquals(0xFF00FF00L, domain.tiers.first { it.id == "a" }.colorArgb)

        val purchase = domain.unranked.first().purchaseLocations.first()
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
    fun `tea entity maps a null tier to the unranked tray`() {
        val board = BoardEntity("b", "B", 0)
        val tray = TeaWithChildren(
            tea = TeaEntity("b-t", "b", null, 0, "Чай", null, null, null, "BLACK", null, null, null),
            flavors = emptyList(),
            purchases = emptyList(),
        )

        val domain = BoardWithChildren(board, tiers = emptyList(), teas = listOf(tray)).toDomain()

        assertEquals(listOf("Чай"), domain.unranked.map { it.nameRu })
        assertNull(domain.placements["missing"])
    }
}
