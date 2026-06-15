package com.macsia.teatiers.viewmodel

import com.macsia.teatiers.domain.model.Board
import com.macsia.teatiers.domain.model.Placement
import com.macsia.teatiers.domain.model.Tea
import com.macsia.teatiers.domain.model.TeaType
import org.junit.jupiter.api.Assertions.assertEquals
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
