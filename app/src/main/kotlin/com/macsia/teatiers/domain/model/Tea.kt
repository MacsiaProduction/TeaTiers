package com.macsia.teatiers.domain.model

/**
 * A tea sample as shown on a board. Names are multilingual (decisions.md #5); since v7 (P1-2) a
 * sample is valid with ≥1 non-blank name in ANY locale — [nameRu] is no longer required — and the
 * display title is resolved by [displayName]. [catalogTeaId] links the sample to its cached catalog
 * ref once resolved (#21/#132), and [enrichmentState] tracks the optimistic background enrichment so
 * the card can show its status / a retry (#28).
 */
data class Tea(
    val id: String,
    val nameRu: String? = null,
    val nameZh: String? = null,
    val pinyin: String? = null,
    val nameEn: String? = null,
    val type: TeaType,
    val origin: String? = null,
    val shortBlurb: String? = null,
    val flavor: List<FlavorScore> = emptyList(),
    val notes: String? = null,
    val purchaseLocations: List<PurchaseLocation> = emptyList(),
    val photos: List<TeaPhoto> = emptyList(),
    val catalogTeaId: Long? = null,
    val enrichmentState: EnrichmentState = EnrichmentState.NONE,
) {
    /**
     * The resolved display title, used at every title site (P1-2). Deterministic priority
     * ru → en → pinyin → zh-Hans (first non-blank); never blank for a valid sample (≥1 name
     * enforced on add/edit). The device-locale-aware refinement + a per-sample pin ride a later slice.
     */
    val displayName: String
        get() = listOf(nameRu, nameEn, pinyin, nameZh).firstOrNull { !it.isNullOrBlank() }.orEmpty()

    /** Latin/han secondary line shown under the title, excluding whatever became the primary. */
    val secondaryName: String
        get() = listOfNotNull(pinyin, nameZh).filter { it != displayName }.joinToString("  ·  ")
}
