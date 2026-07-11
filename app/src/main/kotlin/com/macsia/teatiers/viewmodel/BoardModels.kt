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

/**
 * Load state for the board screen (R4-REG-2), mirroring [TeaDetailUiState]. Distinguishes "still
 * loading" from "this board couldn't be resolved" — a deleted board, or the boards flow terminally
 * failing its read retries and settling on an empty list — so the screen shows a message instead of
 * an indefinite spinner that can never resolve (before this the null state rendered a forever-spinner).
 */
sealed interface BoardScreenState {
    data object Loading : BoardScreenState
    data object NotFound : BoardScreenState
    data class Loaded(val board: BoardUiState) : BoardScreenState
}

/** Compact board entry for the boards list. */
data class BoardSummary(
    val id: String,
    val name: String,
    val teaCount: Int,
    val signatureTypes: List<TeaType>,
)

/**
 * Plain-text rendering of a board for sharing (one tier per line, "S: a, b"). Empty tiers are
 * skipped; the unranked tray is appended last when non-empty. [unrankedLabel] is passed in so this
 * stays pure (no Android resources) and unit-testable. Text — not an image — because it renders for
 * a board of any length without scroll-capture quirks (audit #1, lazy first cut).
 */
fun BoardUiState.toShareText(unrankedLabel: String): String {
    val lines = mutableListOf(boardName)
    tiers.forEach { row ->
        if (row.placements.isNotEmpty()) {
            lines += "${row.tier.label}: " + row.placements.joinToString(", ") { it.tea.displayName }
        }
    }
    if (unranked.isNotEmpty()) {
        lines += "$unrankedLabel: " + unranked.joinToString(", ") { it.tea.displayName }
    }
    return lines.joinToString("\n")
}

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
