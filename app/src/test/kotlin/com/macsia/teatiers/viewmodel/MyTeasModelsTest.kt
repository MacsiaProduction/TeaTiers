package com.macsia.teatiers.viewmodel

import com.macsia.teatiers.domain.model.Board
import com.macsia.teatiers.domain.model.Placement
import com.macsia.teatiers.domain.model.Tea
import com.macsia.teatiers.domain.model.TeaType
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class MyTeasModelsTest {

    private fun tea(
        id: String,
        nameRu: String,
        type: TeaType = TeaType.GREEN,
        nameEn: String? = null,
        pinyin: String? = null,
        nameZh: String? = null,
    ) = Tea(id = id, nameRu = nameRu, nameEn = nameEn, pinyin = pinyin, nameZh = nameZh, type = type)

    private fun board(id: String, tray: List<Tea>) = Board(
        id = id,
        name = id,
        tiers = emptyList(),
        placements = emptyMap(),
        unranked = tray.mapIndexed { i, t -> Placement(placementId = "$id-p$i", tea = t) },
    )

    @Test
    fun `filter sorts by ru name case-insensitively including Russian uppercase`() {
        val teas = listOf(
            tea("1", "Юньнань"),
            tea("2", "алишань"),
            tea("3", "Да Хун Пао"),
        )

        val result = filterMyTeas(teas, query = "", type = null).map { it.nameRu }

        assertEquals(listOf("алишань", "Да Хун Пао", "Юньнань"), result)
    }

    @Test
    fun `query matches across ru, en, pinyin, and zh, case-insensitively`() {
        val teas = listOf(
            tea("ru", "Лунцзин"),
            tea("en", "Чай", nameEn = "Dragonwell"),
            tea("py", "Чай2", pinyin = "Bì Luó Chūn"),
            tea("zh", "Чай3", nameZh = "大红袍"),
            tea("none", "Пуэр"),
        )

        assertEquals(listOf("ru"), filterMyTeas(teas, "лунц", null).map { it.id })
        assertEquals(listOf("en"), filterMyTeas(teas, "DRAGON", null).map { it.id })
        assertEquals(listOf("py"), filterMyTeas(teas, "luó", null).map { it.id })
        assertEquals(listOf("zh"), filterMyTeas(teas, "大红袍", null).map { it.id })
    }

    @Test
    fun `query matches a Latin string stored in the zh name case-insensitively`() {
        // Some users store romanized/Latin text in the zh field; it must match case-insensitively
        // like every other field (zh was previously the only one compared without lowercasing).
        val teas = listOf(
            tea("zh-latin", "Чай-x", nameZh = "DaHongPao"),
            tea("none", "Пуэр"),
        )

        assertEquals(listOf("zh-latin"), filterMyTeas(teas, "dahongpao", null).map { it.id })
    }

    @Test
    fun `query also matches origin, vendor, product, and harvest year`() {
        // These are the sample-identity fields shown on the card; a user must be able to find a
        // sample by what distinguishes it from another of the same tea (audit).
        val teas = listOf(
            Tea(id = "origin", nameRu = "Чай-о", type = TeaType.GREEN, origin = "Юньнань"),
            Tea(id = "vendor", nameRu = "Чай-в", type = TeaType.GREEN, vendor = "Лавка Чая"),
            Tea(id = "product", nameRu = "Чай-п", type = TeaType.GREEN, product = "Зимний сбор"),
            Tea(id = "year", nameRu = "Чай-г", type = TeaType.GREEN, harvestYear = 2019),
            Tea(id = "none", nameRu = "Пуэр", type = TeaType.GREEN),
        )

        assertEquals(listOf("origin"), filterMyTeas(teas, "юньнань", null).map { it.id })
        assertEquals(listOf("vendor"), filterMyTeas(teas, "лавка", null).map { it.id })
        assertEquals(listOf("product"), filterMyTeas(teas, "зимний", null).map { it.id })
        assertEquals(listOf("year"), filterMyTeas(teas, "2019", null).map { it.id })
    }

    @Test
    fun `type filter narrows to one category`() {
        val teas = listOf(
            tea("g", "Зелёный", type = TeaType.GREEN),
            tea("o", "Улун", type = TeaType.OOLONG),
            tea("o2", "Улун2", type = TeaType.OOLONG),
        )

        val result = filterMyTeas(teas, query = "", type = TeaType.OOLONG).map { it.id }

        assertEquals(listOf("o", "o2"), result)
    }

    @Test
    fun `query and type filter combine`() {
        val teas = listOf(
            tea("a", "Те Гуань Инь", type = TeaType.OOLONG),
            tea("b", "Да Хун Пао", type = TeaType.OOLONG),
            tea("c", "Да Хун Пао", type = TeaType.GREEN), // wrong type
        )

        val result = filterMyTeas(teas, query = "да хун", type = TeaType.OOLONG).map { it.id }

        assertEquals(listOf("b"), result)
    }

    @Test
    fun `a one-character typo still matches (UX-F-3)`() {
        val teas = listOf(tea("1", "Лунцзин"), tea("2", "Пуэр"))

        // "лунцзын" is one substitution away from "лунцзин" — a plain .contains() would find nothing.
        val result = filterMyTeas(teas, query = "лунцзын", type = null).map { it.id }

        assertEquals(listOf("1"), result)
    }

    @Test
    fun `a short query does not fuzzy-match unrelated short words (UX-F-3)`() {
        val teas = listOf(tea("1", "Чай Пуэр"), tea("2", "Чай Улун"))

        // Below the 3-char fuzzy floor: only an exact substring counts, not a 1-edit-distance guess.
        val result = filterMyTeas(teas, query = "пу", type = null).map { it.id }

        assertEquals(listOf("1"), result)
    }

    @Test
    fun `an unrelated query does not fuzzy-match by coincidence (UX-F-3)`() {
        val teas = listOf(tea("1", "Лунцзин"))

        assertTrue(filterMyTeas(teas, query = "пуэр", type = null).isEmpty())
    }

    @Test
    fun `sort by type groups the collection by category, then by name (UX-F-2)`() {
        val teas = listOf(
            tea("g2", "Сенча", type = TeaType.GREEN),
            tea("o", "Улун", type = TeaType.OOLONG),
            tea("g1", "Лунцзин", type = TeaType.GREEN),
        )

        val result = filterMyTeas(teas, query = "", type = null, sort = MyTeasSortOption.TYPE).map { it.id }

        // GREEN sorts before OOLONG (TeaType's declared GB/T category order); within GREEN, alphabetical.
        assertEquals(listOf("g1", "g2", "o"), result)
    }

    @Test
    fun `placementCounts tallies a shared tea across boards and omits zero-placement teas`() {
        val shared = tea("shared", "Да Хун Пао")
        val onlyA = tea("onlyA", "Лунцзин")
        val boards = listOf(
            board("A", tray = listOf(shared, onlyA)),
            board("B", tray = listOf(shared)),
        )

        val counts = placementCounts(boards)

        assertEquals(2, counts["shared"])
        assertEquals(1, counts["onlyA"])
        assertEquals(null, counts["never-placed"]) // a tea on no board is absent (count 0)
    }
}
