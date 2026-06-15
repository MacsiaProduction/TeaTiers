package com.macsia.teatiers.viewmodel

import com.macsia.teatiers.domain.model.Board
import com.macsia.teatiers.domain.model.Placement
import com.macsia.teatiers.domain.model.TeaType
import com.macsia.teatiers.domain.model.Tier

/** A tier paired with the placements ranked in it, ready to render. */
data class TierWithPlacements(val tier: Tier, val placements: List<Placement>)

/** Immutable UI state for the board screen. */
data class BoardUiState(
    val boardName: String,
    val tiers: List<TierWithPlacements>,
    val unranked: List<Placement>,
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
        tiers = tiers.sortedBy { it.position }.map { tier ->
            TierWithPlacements(tier, placements[tier.id].orEmpty())
        },
        unranked = unranked,
    )

fun Board.toSummary(): BoardSummary {
    val allPlacements = placements.values.flatten() + unranked
    return BoardSummary(
        id = id,
        name = name,
        teaCount = allPlacements.size,
        signatureTypes = allPlacements.map { it.tea.type }.distinct().take(4),
    )
}
