package com.macsia.teatiers.domain.model

/**
 * A shared-catalog tea as returned by search (plan §5). Distinct from the user's local [Tea]:
 * this is read-only reference data the user may pick to prefill the add form. `verificationStatus`
 * lets the UI flag an `unverified` (AI-enriched) entry (#23). The `type` is already mapped to the
 * app enum (unknown server values fall to [TeaType.OTHER] in the mapper).
 */
data class CatalogTea(
    val id: Long,
    val type: TeaType,
    val originCountry: String?,
    val brand: String?,
    val verificationStatus: String,
    val names: List<CatalogName>,
) {
    val isUnverified: Boolean get() = verificationStatus.equals("unverified", ignoreCase = true)

    private fun primary(locale: String): String? =
        names.firstOrNull { it.locale == locale && it.isPrimary }?.name
            ?: names.firstOrNull { it.locale == locale }?.name

    val nameRu: String? get() = primary(CatalogLocale.RU)
    val nameEn: String? get() = primary(CatalogLocale.EN)
    val pinyin: String? get() = primary(CatalogLocale.PINYIN)
    val nameZh: String? get() = primary(CatalogLocale.ZH_HANS)

    /** Primary display line: ru, then en, then pinyin, then any name. */
    val displayName: String
        get() = nameRu ?: nameEn ?: pinyin ?: names.firstOrNull()?.name.orEmpty()

    /** Latin/han secondary line under the title: "lóngjǐng · 龙井". */
    val secondaryName: String
        get() = listOfNotNull(pinyin, nameZh)
            .filter { it != displayName }
            .joinToString("  ·  ")
}

data class CatalogName(
    val locale: String,
    val name: String,
    val isPrimary: Boolean,
)

/** Locale tags used by the catalog (plan §4a/§5). */
object CatalogLocale {
    const val EN = "en"
    const val RU = "ru"
    const val ZH_HANS = "zh-Hans"
    const val PINYIN = "pinyin"
}
