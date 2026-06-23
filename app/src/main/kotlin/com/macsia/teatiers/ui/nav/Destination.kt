package com.macsia.teatiers.ui.nav

import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.runtime.toMutableStateList

/**
 * App destinations. A deliberately tiny, dependency-free alternative to navigation-compose for
 * the handful of screens we have; revisit if the graph grows (deep links, nested graphs).
 * Ids are kebab/uuid slugs with no ':' so the back stack serializes safely for [BackStackSaver].
 *
 * After the shared-teas reopening (decisions.md #42) tea detail/edit are board-agnostic — the
 * tea is shared across boards, so the route only carries [teaId].
 */
sealed interface Destination {
    data object Boards : Destination
    data object MyTeas : Destination
    data object Settings : Destination
    data object Attributions : Destination

    /** Browse the whole shared catalog (#42 follow-up) — pick a tea to add to a board. */
    data object BrowseCatalog : Destination
    data class Board(val boardId: String) : Destination
    data class TeaDetail(val teaId: String) : Destination

    /**
     * Add a tea to [boardId]; [catalogTeaId] (set when entered from browse) pre-picks that tea.
     * [forceNew] is the P1-1 "add another sample" path (#132): create a NEW sample of the same catalog
     * ref instead of reusing the existing one. Only meaningful with a [catalogTeaId].
     */
    data class AddTea(val boardId: String, val catalogTeaId: Long? = null, val forceNew: Boolean = false) : Destination
    data class EditTea(val teaId: String) : Destination
    data class TierEditor(val boardId: String) : Destination
}

private fun Destination.encode(): String = when (this) {
    Destination.Boards -> "boards"
    Destination.MyTeas -> "my-teas"
    Destination.Settings -> "settings"
    Destination.Attributions -> "attributions"
    Destination.BrowseCatalog -> "browse-catalog"
    is Destination.Board -> "board:$boardId"
    is Destination.TeaDetail -> "tea:$teaId"
    // boardId is a ':'-free uuid slug, so '|' safely separates the optional catalog id (+ "|new" flag).
    is Destination.AddTea -> "add:$boardId" + (catalogTeaId?.let { "|$it" + (if (forceNew) "|new" else "") } ?: "")
    is Destination.EditTea -> "edit-tea:$teaId"
    is Destination.TierEditor -> "tiers:$boardId"
}

private fun String.decodeDestination(): Destination {
    // limit=2: an id never contains ':' (encode guarantees it), but splitting defensively keeps a
    // stray ':' from truncating the id. A route that's missing its arg (corrupt/old saved stack)
    // falls back to Boards instead of throwing IndexOutOfBounds and boot-looping the app (review P3).
    val parts = split(":", limit = 2)
    val arg = parts.getOrNull(1)
    return when (parts.first()) {
        "my-teas" -> Destination.MyTeas
        "settings" -> Destination.Settings
        "attributions" -> Destination.Attributions
        "browse-catalog" -> Destination.BrowseCatalog
        "board" -> arg?.let(Destination::Board) ?: Destination.Boards
        "tea" -> arg?.let(Destination::TeaDetail) ?: Destination.Boards
        "add" -> arg?.let { a ->
            // "boardId" | "boardId|catalogId" (browse) | "boardId|catalogId|new" (add-another); a
            // malformed id falls back to no pre-pick.
            val parts = a.split("|")
            Destination.AddTea(parts[0], parts.getOrNull(1)?.toLongOrNull(), forceNew = parts.getOrNull(2) == "new")
        } ?: Destination.Boards
        "edit-tea" -> arg?.let(Destination::EditTea) ?: Destination.Boards
        "tiers" -> arg?.let(Destination::TierEditor) ?: Destination.Boards
        else -> Destination.Boards
    }
}

/** Persists the back stack across configuration change and process death. */
val BackStackSaver: Saver<SnapshotStateList<Destination>, Any> = listSaver(
    save = { stack -> stack.map { it.encode() } },
    restore = { saved -> saved.map { it.decodeDestination() }.toMutableStateList() },
)
