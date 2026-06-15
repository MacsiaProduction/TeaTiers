package com.macsia.teatiers.viewmodel

import com.macsia.teatiers.domain.model.Board
import com.macsia.teatiers.domain.model.Tea
import com.macsia.teatiers.domain.model.TeaType
import com.macsia.teatiers.domain.model.Tier

/** A tier paired with its ranked teas, ready to render. */
data class TierWithTeas(val tier: Tier, val teas: List<Tea>)

/** Immutable UI state for the board screen. */
data class BoardUiState(
    val boardName: String,
    val tiers: List<TierWithTeas>,
    val unranked: List<Tea>,
)

/** Compact board entry for the boards list. */
data class BoardSummary(
    val id: String,
    val name: String,
    val teaCount: Int,
    val signatureTypes: List<TeaType>,
)

fun Board.toUiState(): BoardUiState =
    BoardUiState(
        boardName = name,
        tiers = tiers.sortedBy { it.position }.map { tier -> TierWithTeas(tier, placements[tier.id].orEmpty()) },
        unranked = unranked,
    )

fun Board.toSummary(): BoardSummary {
    val allTeas = placements.values.flatten() + unranked
    return BoardSummary(
        id = id,
        name = name,
        teaCount = allTeas.size,
        signatureTypes = allTeas.map { it.type }.distinct().take(4),
    )
}
