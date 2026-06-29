package com.macsia.teatiers.viewmodel

import com.macsia.teatiers.domain.model.Board
import com.macsia.teatiers.domain.model.Tea
import com.macsia.teatiers.domain.model.TeaType

/** One row in the cross-board "my teas" list: the shared user-tea plus how many boards it sits on. */
data class MyTeaItem(val tea: Tea, val boardCount: Int)

/** Immutable UI state for the "my teas" screen (decisions.md #27). */
data class MyTeasUiState(
    val query: String = "",
    val typeFilter: TeaType? = null,
    val items: List<MyTeaItem> = emptyList(),
    /** Types present in the whole collection (drives the filter chips); independent of the query. */
    val availableTypes: List<TeaType> = emptyList(),
    /** Whether the user has any teas at all — distinguishes "empty collection" from "no matches". */
    val collectionEmpty: Boolean = true,
)

/**
 * Counts how many boards each user-tea sits on, keyed by `tea.id`. A tea removed from every board
 * (but not deleted) is simply absent from the map (count 0). Pure so it is unit-testable.
 */
fun placementCounts(boards: List<Board>): Map<String, Int> {
    val counts = HashMap<String, Int>()
    boards.forEach { board ->
        val placements = board.placements.values.flatten() + board.unranked
        placements.forEach { placement ->
            counts[placement.tea.id] = (counts[placement.tea.id] ?: 0) + 1
        }
    }
    return counts
}

/**
 * Filters + sorts the collection for display. Keeps teas matching [type] (when non-null) and whose
 * name, origin, or sample identity (vendor / product / harvest year) contains [query] — those are
 * the fields shown on the card, so two samples of the same tea are findable by what distinguishes
 * them (audit). Case-insensitive substring. Then sorts by the resolved display title (ru → en →
 * pinyin → zh) lower-cased so Russian uppercase orders correctly (SQLite's ASCII-only collation
 * cannot). Pure so it is unit-testable without Room or Compose.
 */
fun filterMyTeas(teas: List<Tea>, query: String, type: TeaType?): List<Tea> {
    val needle = query.trim().lowercase()
    return teas
        .asSequence()
        .filter { type == null || it.type == type }
        .filter { needle.isEmpty() || it.matchesQuery(needle) }
        .sortedBy { it.displayName.lowercase() }
        .toList()
}

private fun Tea.matchesQuery(needle: String): Boolean =
    nameRu?.lowercase()?.contains(needle) == true ||
        nameEn?.lowercase()?.contains(needle) == true ||
        pinyin?.lowercase()?.contains(needle) == true ||
        nameZh?.lowercase()?.contains(needle) == true ||
        origin?.lowercase()?.contains(needle) == true ||
        vendor?.lowercase()?.contains(needle) == true ||
        product?.lowercase()?.contains(needle) == true ||
        harvestYear?.toString()?.contains(needle) == true
