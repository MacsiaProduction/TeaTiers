package com.macsia.teatiers.domain.model

/**
 * Full reference detail for a shared-catalog tea (plan §5, `GET /teas/{id}`). Read-only suggestion
 * data: the user may pull it into their own [Tea] via the add form, but never owns it (#21). Adds
 * descriptions, a reference flavor profile, curated CC images, and provenance on top of the
 * search-time [CatalogTea]. Localized-name helpers mirror [CatalogTea] so both render the same way.
 */
data class CatalogTeaDetail(
    val id: Long,
    val type: TeaType,
    val originCountry: String?,
    val region: String?,
    val cultivar: String?,
    val oxidationMin: Int?,
    val oxidationMax: Int?,
    val brand: String?,
    // [image] is the first of [images], kept for back-compat; [images] is the full ordered list (#70.2).
    val image: CatalogImage?,
    val images: List<CatalogImage> = emptyList(),
    val names: List<CatalogName>,
    val descriptions: List<CatalogDescription>,
    val flavors: List<FlavorScore>,
    val provenance: CatalogProvenance,
    // Async LLM enrichment state of the catalog row (null = not LLM-managed): the client polls
    // this after an ENRICHING resolve until it flips to DONE/FAILED (decisions.md #66/#28).
    val enrichmentState: EnrichmentState? = null,
) {
    val isUnverified: Boolean get() = provenance.verificationStatus.equals("unverified", ignoreCase = true)

    private fun primary(locale: String): String? =
        names.firstOrNull { it.locale == locale && it.isPrimary }?.name
            ?: names.firstOrNull { it.locale == locale }?.name

    val nameRu: String? get() = primary(CatalogLocale.RU)
    val nameEn: String? get() = primary(CatalogLocale.EN)
    val pinyin: String? get() = primary(CatalogLocale.PINYIN)
    val nameZh: String? get() = primary(CatalogLocale.ZH_HANS)

    val displayName: String
        get() = nameRu ?: nameEn ?: pinyin ?: names.firstOrNull()?.name.orEmpty()

    val secondaryName: String
        get() = listOfNotNull(pinyin, nameZh)
            .filter { it != displayName }
            .joinToString("  ·  ")

    /** Region if known, otherwise the country code — the single "where from" line under the title. */
    val origin: String? get() = region ?: originCountry

    /** Best description for [preferred] locale, falling back to ru, then en, then the first present. */
    fun descriptionFor(preferred: String): CatalogDescription? =
        descriptions.firstOrNull { it.locale == preferred }
            ?: descriptions.firstOrNull { it.locale == CatalogLocale.RU }
            ?: descriptions.firstOrNull { it.locale == CatalogLocale.EN }
            ?: descriptions.firstOrNull()

    /** Narrows the detail back to the search-shaped [CatalogTea] so the add form prefill reuses one path. */
    fun toCatalogTea(): CatalogTea = CatalogTea(
        id = id,
        type = type,
        originCountry = originCountry,
        brand = brand,
        verificationStatus = provenance.verificationStatus,
        names = names,
    )
}

/** Localized blurb. `full` may be CC-BY-SA and is attributed via [source] / [license]. */
data class CatalogDescription(
    val locale: String,
    val short: String?,
    val full: String?,
    val source: String?,
    val license: String?,
)

/** Optional reference image plus the attribution the UI must show next to it. */
data class CatalogImage(
    val url: String,
    val license: String?,
    val sourceUrl: String?,
)

/** Where the entry came from and how trusted it is (drives the "unverified" hint, #23). */
data class CatalogProvenance(
    val source: String,
    val sourceUrl: String?,
    val license: String?,
    val verificationStatus: String,
    val confidence: Float?,
)
