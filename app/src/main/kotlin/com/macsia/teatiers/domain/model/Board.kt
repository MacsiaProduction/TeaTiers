package com.macsia.teatiers.domain.model

/**
 * A tier list. [placements] maps a [Tier.id] to the teas ranked in it; [unranked] holds
 * teas not yet placed (the tray). Persisted in Room from M1; here it is in-memory only.
 */
data class Board(
    val id: String,
    val name: String,
    val tiers: List<Tier>,
    val placements: Map<String, List<Tea>>,
    val unranked: List<Tea>,
)
