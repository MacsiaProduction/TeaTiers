package com.macsia.teatiers.domain.model

/**
 * The seeded tier set a brand-new board starts with (decisions.md #41). The user can rename,
 * recolor, reorder, add, or remove tiers post-creation via the tier editor; the template only
 * decides the initial shape.
 */
enum class TierTemplate {
    /** Six tier-list-culture labels, ordered S A B C D F (top = best). */
    F_TO_S,

    /** Ten numeric tiers ordered 10 down to 1 (10 = best, top). */
    ONE_TO_TEN,

    /** No tiers — the user lands in the unranked tray and builds their own set. */
    BLANK,
}

/**
 * Returns the [Tier]s a freshly created [boardId] starts with for this template. Tier ids are
 * namespaced by the board's id because [Tier.id] is a global primary key in the local store —
 * two boards on the same template would otherwise collide. Pure; no I/O. Tier labels are not
 * localised on purpose: "S/A/B/C/D/F" and Arabic numerals are universal across ru/en/zh.
 */
fun TierTemplate.seedTiers(boardId: String): List<Tier> = when (this) {
    TierTemplate.F_TO_S -> F_TO_S_LABELS.mapIndexed { index, label ->
        Tier(id = "$boardId-${label.lowercase()}", label = label, position = index)
    }
    TierTemplate.ONE_TO_TEN -> (10 downTo 1).toList().mapIndexed { index, value ->
        Tier(id = "$boardId-n$value", label = value.toString(), position = index)
    }
    TierTemplate.BLANK -> emptyList()
}

private val F_TO_S_LABELS = listOf("S", "A", "B", "C", "D", "F")
