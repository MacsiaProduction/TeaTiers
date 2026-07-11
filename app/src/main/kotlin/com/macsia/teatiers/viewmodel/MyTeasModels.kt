package com.macsia.teatiers.viewmodel

import com.macsia.teatiers.domain.model.Board
import com.macsia.teatiers.domain.model.Tea
import com.macsia.teatiers.domain.model.TeaType
import java.text.Collator
import java.util.Locale

/** One row in the cross-board "my teas" list: the shared user-tea plus how many boards it sits on. */
data class MyTeaItem(val tea: Tea, val boardCount: Int)

/**
 * How to order the "my teas" list (UX-F-2). [NAME] (the existing default) and [TYPE] (grouped by
 * the GB/T 30766-2014 category order [TeaType] already declares, then by name within a group), and
 * [CREATED] (most recently added first, R4-F-1) are the orderings the current data supports cleanly.
 * Sorting by tier still isn't well-defined here (a shared tea can sit at a different tier on every
 * board it's placed on, decisions.md #42), and "rating" stays a product decision (flavor is
 * multi-axis, not a single scalar).
 */
enum class MyTeasSortOption { NAME, TYPE, CREATED }

/** Immutable UI state for the "my teas" screen (decisions.md #27). */
data class MyTeasUiState(
    val query: String = "",
    val typeFilter: TeaType? = null,
    val sort: MyTeasSortOption = MyTeasSortOption.NAME,
    val items: List<MyTeaItem> = emptyList(),
    /** Types present in the whole collection (drives the filter chips); independent of the query. */
    val availableTypes: List<TeaType> = emptyList(),
    /** Whether the user has any teas at all — distinguishes "empty collection" from "no matches". */
    val collectionEmpty: Boolean = true,
    /** True until Room's first emission (UX3-P2-3) so the screen shows a spinner instead of flashing
     *  the "no teas yet" empty state on a cold start (indistinguishable from a real empty collection). */
    val loading: Boolean = true,
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
 * name, origin, or sample identity (vendor / product / harvest year) fuzzy-matches [query] (UX-F-3;
 * exact substring, or a close typo — those are the fields shown on the card, so two samples of the
 * same tea are findable by what distinguishes them, audit). Then orders by [sort] (UX-F-2) via a
 * [Collator] (UX-P2-5) so mixed Cyrillic/Latin/CJK names order correctly — SQLite's ASCII-only
 * collation already ruled out sorting in the DB, and a plain `.lowercase()` compare only fixes case,
 * still falling back to raw UTF-16 codepoint order across scripts. Pure so it is unit-testable
 * without Room or Compose.
 */
fun filterMyTeas(
    teas: List<Tea>,
    query: String,
    type: TeaType?,
    sort: MyTeasSortOption = MyTeasSortOption.NAME,
): List<Tea> {
    val needle = query.trim().lowercase()
    // ru-first (decisions.md #12): a Russian collator still orders Latin/CJK sensibly, just not by a
    // script-specific rule of its own — recreated per call since Collator is not guaranteed thread-safe.
    val collator = Collator.getInstance(Locale("ru")).apply { strength = Collator.SECONDARY }
    val byName: Comparator<Tea> = compareBy(collator) { it.displayName }
    val comparator = when (sort) {
        MyTeasSortOption.NAME -> byName
        // TeaType's declaration order is the GB/T 30766-2014 category order, not arbitrary — grouping
        // by it, then alphabetically within a group, is a meaningful "by type" ordering for free.
        MyTeasSortOption.TYPE -> compareBy<Tea> { it.type.ordinal }.then(byName)
        // Most recently added first (R4-F-1). Rows with no timestamp (pre-v9 / seed) sort last, then
        // by name, so the ordering stays stable and deterministic instead of clumping arbitrarily.
        MyTeasSortOption.CREATED ->
            compareByDescending<Tea> { it.createdAtEpochMs ?: Long.MIN_VALUE }.then(byName)
    }
    return teas
        .asSequence()
        .filter { type == null || it.type == type }
        .filter { needle.isEmpty() || it.matchesQuery(needle) }
        .sortedWith(comparator)
        .toList()
}

private fun Tea.matchesQuery(needle: String): Boolean =
    fuzzyContains(nameRu, needle) ||
        fuzzyContains(nameEn, needle) ||
        fuzzyContains(pinyin, needle) ||
        fuzzyContains(nameZh, needle) ||
        fuzzyContains(origin, needle) ||
        fuzzyContains(vendor, needle) ||
        fuzzyContains(product, needle) ||
        harvestYear?.toString()?.contains(needle) == true

private val Whitespace = Regex("\\s+")

/**
 * Case-insensitive substring match, with a word-level typo-tolerance fallback (UX-F-3): the catalog
 * search is server-side trigram-fuzzy, but a personal collection is small enough that a plain
 * Levenshtein check per word is plenty — no trigram index needed. Only engages for a 3+ character
 * [needle] (a 1-2 char query is either an exact prefix or, for CJK, already a complete meaningful
 * token per [isSearchableQuery]'s reasoning — fuzzy noise isn't worth it at that length).
 */
private fun fuzzyContains(field: String?, needle: String): Boolean {
    if (field.isNullOrBlank()) return false
    val haystack = field.lowercase()
    if (haystack.contains(needle)) return true
    if (needle.length < 3) return false
    val maxDistance = if (needle.length <= 5) 1 else 2
    return haystack.split(Whitespace).any { word -> levenshtein(needle, word) <= maxDistance }
}

/** Classic O(n·m) edit distance; fine at the word lengths tea-name fields actually have. */
private fun levenshtein(a: String, b: String): Int {
    if (a.isEmpty()) return b.length
    if (b.isEmpty()) return a.length
    val dp = Array(a.length + 1) { IntArray(b.length + 1) }
    for (i in 0..a.length) dp[i][0] = i
    for (j in 0..b.length) dp[0][j] = j
    for (i in 1..a.length) {
        for (j in 1..b.length) {
            dp[i][j] = if (a[i - 1] == b[j - 1]) {
                dp[i - 1][j - 1]
            } else {
                1 + minOf(dp[i - 1][j], dp[i][j - 1], dp[i - 1][j - 1])
            }
        }
    }
    return dp[a.length][b.length]
}
