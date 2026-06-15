package com.macsia.teatiers.ui.nav

import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.runtime.toMutableStateList

/**
 * App destinations. A deliberately tiny, dependency-free alternative to navigation-compose for
 * the handful of screens we have; revisit if the graph grows (deep links, nested graphs).
 * Ids are kebab/uuid slugs with no ':' so the back stack serializes safely for [BackStackSaver].
 */
sealed interface Destination {
    data object Boards : Destination
    data class Board(val boardId: String) : Destination
    data class TeaDetail(val boardId: String, val teaId: String) : Destination
    data class AddTea(val boardId: String) : Destination
}

private fun Destination.encode(): String = when (this) {
    Destination.Boards -> "boards"
    is Destination.Board -> "board:$boardId"
    is Destination.TeaDetail -> "tea:$boardId:$teaId"
    is Destination.AddTea -> "add:$boardId"
}

private fun String.decodeDestination(): Destination {
    val parts = split(":")
    return when (parts.first()) {
        "board" -> Destination.Board(parts[1])
        "tea" -> Destination.TeaDetail(parts[1], parts[2])
        "add" -> Destination.AddTea(parts[1])
        else -> Destination.Boards
    }
}

/** Persists the back stack across configuration change and process death. */
val BackStackSaver: Saver<SnapshotStateList<Destination>, Any> = listSaver(
    save = { stack -> stack.map { it.encode() } },
    restore = { saved -> saved.map { it.decodeDestination() }.toMutableStateList() },
)
