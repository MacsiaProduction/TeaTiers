package com.macsia.teatiers.dto

import com.macsia.teatiers.domain.FlavorDimension
import com.macsia.teatiers.domain.TeaType
import java.util.UUID

/** One localized name (or alias) of a tea. */
data class TeaNameDto(
    val locale: String,
    val name: String,
    val isPrimary: Boolean,
)

/**
 * Compact tea representation for search results. `publicId` (V7, decision #136) is the stable
 * client-facing id new clients should cache; `id` (the BIGINT) stays for back-compat with the shipped
 * APK and is resolvable via `tea_legacy_id_map` after a DB rebuild.
 */
data class TeaSummaryDto(
    val id: Long,
    val publicId: UUID,
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
    val publicId: UUID,
    // Soft-rollback lifecycle (V7): 'active' | 'retracted' (tombstone) | 'merged'. When a client resolves a
    // merged tea by its old public_id the API returns the survivor's detail, so this is normally 'active'.
    val status: String,
    // Set when this tea was merged into another; the survivor's public_id the client should re-cache.
    val supersededByPublicId: UUID?,
    val wikidataQid: String?,
    val type: TeaType,
    val originCountry: String?,
    val region: String?,
    val harvestYear: Int?,
    val cultivar: String?,
    val oxidationMin: Int?,
    val oxidationMax: Int?,
    val brand: String?,
    // [image] is the first of [images], kept for back-compat with clients that show one thumbnail (#70.2).
    val image: TeaImageDto?,
    val images: List<TeaImageDto>,
    val names: List<TeaNameDto>,
    val descriptions: List<TeaDescriptionDto>,
    val flavors: List<TeaFlavorDto>,
    val provenance: TeaProvenanceDto,
    // Async LLM enrichment state (null = not LLM-enriched): PENDING while a job runs, DONE/FAILED after.
    // The client polls this endpoint after an ENRICHING resolve and can offer a retry on FAILED.
    val enrichmentState: String?,
)

/**
 * Lifecycle-only response for a withdrawn (`retracted`) tea, or a `merged` tea whose survivor chain is
 * broken/cyclic (decision #137-C3 / #139-R2). It deliberately carries NO catalog content -- no names,
 * descriptions, images, flavor, origin, brand, or provenance -- so a retraction genuinely WITHDRAWS
 * content that may be incorrect, unsafe, or not licensed for publication. Returned with HTTP 410 Gone.
 */
data class TeaLifecycleDto(
    val publicId: UUID,
    val status: String, // 'retracted' | 'merged'
    val supersededByPublicId: UUID?, // the survivor to re-cache, when known
    val message: String? = null, // generic, content-free
)

/**
 * Result of a client-facing catalog detail lookup (`/by-public-id/{uuid}` and the numeric compat route):
 * full content for an active tea or a resolvable merge redirect, otherwise a content-free [TeaLifecycleDto]
 * tombstone. `null` (handled by the caller) means the id was never issued -> 404.
 */
sealed interface CatalogDetail {
    data class Full(val tea: TeaDetailDto) : CatalogDetail

    data class Tombstone(val lifecycle: TeaLifecycleDto) : CatalogDetail
}

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
