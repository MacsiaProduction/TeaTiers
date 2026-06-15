package com.macsia.teatiers.domain.model

/**
 * A tier list. [placements] maps a [Tier.id] to the placements ranked in it; [unranked] holds
 * placements not yet placed (the tray). After the shared-teas reopening (decisions.md #42)
 * each leaf is a [Placement] (one tea on one board), not a [Tea] directly: the same [Tea] can
 * appear on multiple boards through multiple placements, while user-tea fields stay shared.
 */
data class Board(
    val id: String,
    val name: String,
    val tiers: List<Tier>,
    val placements: Map<String, List<Placement>>,
    val unranked: List<Placement>,
)
