package com.macsia.teatiers.dto

import com.macsia.teatiers.domain.FlavorDimension
import com.macsia.teatiers.domain.TeaType

/** One localized name (or alias) of a tea. */
data class TeaNameDto(
    val locale: String,
    val name: String,
    val isPrimary: Boolean,
)

/** Compact tea representation for search results: id + all names + light metadata. */
data class TeaSummaryDto(
    val id: Long,
    val type: TeaType,
    val originCountry: String?,
    val brand: String?,
    val verificationStatus: String,
    val names: List<TeaNameDto>,
)

/** Localized description; `full` text may be CC-BY-SA and is attributed via `source`/`license`. */
data class TeaDescriptionDto(
    val locale: String,
    val short: String?,
    val full: String?,
    val source: String?,
    val license: String?,
)

/** Reference flavor intensity (0-5) for one dimension. */
data class TeaFlavorDto(
    val dimension: FlavorDimension,
    val intensity: Int,
)

/** Optional reference image with its license/source for attribution. */
data class TeaImageDto(
    val url: String,
    val license: String?,
    val sourceUrl: String?,
)

/** Provenance + enrichment metadata so clients can show an "unverified" hint. */
data class TeaProvenanceDto(
    val source: String,
    val sourceUrl: String?,
    val license: String?,
    val verificationStatus: String,
    val confidence: Float?,
)

/** Full catalog detail for a single tea. */
data class TeaDetailDto(
    val id: Long,
    val wikidataQid: String?,
    val type: TeaType,
    val originCountry: String?,
    val region: String?,
    val cultivar: String?,
    val oxidationMin: Int?,
    val oxidationMax: Int?,
    val brand: String?,
    val image: TeaImageDto?,
    val names: List<TeaNameDto>,
    val descriptions: List<TeaDescriptionDto>,
    val flavors: List<TeaFlavorDto>,
    val provenance: TeaProvenanceDto,
    // Async LLM enrichment state (null = not LLM-enriched): PENDING while a job runs, DONE/FAILED after.
    // The client polls this endpoint after an ENRICHING resolve and can offer a retry on FAILED.
    val enrichmentState: String?,
)

/**
 * Cursor-paged result. `nextCursor` is the last item's id when a full page was returned (pass it
 * back as `cursor` for the next page); `null` means no more results.
 */
data class PageDto<T>(
    val items: List<T>,
    val nextCursor: Long?,
)

/** Distinct types/origins for client-side filter chips. */
data class FacetsDto(
    val types: List<TeaType>,
    val origins: List<String>,
)
