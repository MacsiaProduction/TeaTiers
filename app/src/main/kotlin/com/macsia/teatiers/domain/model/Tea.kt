package com.macsia.teatiers.domain.model

/**
 * A tea as shown on a board. Names are multilingual (decisions.md #5): ru is the primary
 * display name, with optional pinyin + hanzi and an English alias. In later milestones a
 * tea may link to a shared catalog entry; here it is a plain in-memory model.
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
) {
    /** Latin/han secondary line shown under the ru name: "Dà Hóng Páo · 大红袍". */
    val secondaryName: String
        get() = listOfNotNull(pinyin, nameZh).joinToString("  ·  ")
}
