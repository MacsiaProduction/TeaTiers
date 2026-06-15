package com.macsia.teatiers.domain.model

/**
 * One tea sitting on one board (decisions.md #42). The same [Tea] can appear on N boards via
 * N different placements (each with its own [placementId]); the user-tea fields (notes,
 * flavor, purchases, photos) live on [Tea] and are shared across every board the tea is on.
 *
 * No position field on purpose: list order in [Board.placements]/[Board.unranked] is the
 * authoritative ordering in domain. Persistence carries a numeric position so reads can sort
 * deterministically; the mapper writes it from list index, the in-memory model never needs it.
 */
data class Placement(
    val placementId: String,
    val tea: Tea,
)
