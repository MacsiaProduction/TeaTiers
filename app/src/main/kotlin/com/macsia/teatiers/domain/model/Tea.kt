package com.macsia.teatiers.domain.model

/**
 * A tea as shown on a board. Names are multilingual (decisions.md #5): ru is the primary
 * display name, with optional pinyin + hanzi and an English alias. [catalogTeaId] links the
 * tea to its shared-catalog entry once resolved (#21), and [enrichmentState] tracks the
 * optimistic background enrichment so the card can show its status / a retry (#28).
 */
data class Tea(
    val id: String,
    val nameRu: String,
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
    /** Latin/han secondary line shown under the ru name: "Dà Hóng Páo · 大红袍". */
    val secondaryName: String
        get() = listOfNotNull(pinyin, nameZh).joinToString("  ·  ")
}
