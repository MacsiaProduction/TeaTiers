package com.macsia.teatiers.data.remote.dto

import kotlinx.serialization.Serializable

/**
 * Wire DTOs for the catalog API (plan §5), mirroring the server's `dto/CatalogDtos.kt`. `type` and
 * `dimension` are kept as raw [String]s (not the app enums) on purpose: an unknown value the client
 * doesn't model yet must not fail deserialization — the mappers fold it to a safe fallback.
 *
 * Unused-but-decoded fields are ignored by the lenient [kotlinx.serialization.json.Json] in the
 * network module, so adding server fields never breaks an older client.
 */
@Serializable
data class TeaNameDto(
    val locale: String,
    val name: String,
    val primary: Boolean = false,
)

@Serializable
data class TeaSummaryDto(
    val id: Long,
    // Stable client-facing id (server #136). Kept as the raw UUID string so an unparseable/absent
    // value (older server) never fails decoding; the app treats it as an opaque durable key.
    val publicId: String? = null,
    val type: String,
    val originCountry: String? = null,
    val brand: String? = null,
    val verificationStatus: String = "unverified",
    val names: List<TeaNameDto> = emptyList(),
)

@Serializable
data class PageDto<T>(
    val items: List<T> = emptyList(),
    val nextCursor: Long? = null,
)

@Serializable
data class TeaDescriptionDto(
    val locale: String,
    val short: String? = null,
    val full: String? = null,
    val source: String? = null,
    val license: String? = null,
)

@Serializable
data class TeaFlavorDto(
    val dimension: String,
    val intensity: Int,
)

@Serializable
data class TeaImageDto(
    val url: String,
    val license: String? = null,
    val sourceUrl: String? = null,
)

@Serializable
data class TeaProvenanceDto(
    val source: String,
    val sourceUrl: String? = null,
    val license: String? = null,
    val verificationStatus: String = "unverified",
    val confidence: Float? = null,
)

@Serializable
data class TeaDetailDto(
    val id: Long,
    val publicId: String? = null,
    // Soft-rollback lifecycle (server #136): "active" | "merged". A "retracted"/broken-merge tea comes
    // back as a 410 [TeaLifecycleDto] instead. Defaults keep an older server (no field) decoding as active.
    val status: String = "active",
    val supersededByPublicId: String? = null,
    val wikidataQid: String? = null,
    val type: String,
    val originCountry: String? = null,
    val region: String? = null,
    val cultivar: String? = null,
    val harvestYear: Int? = null,
    val oxidationMin: Int? = null,
    val oxidationMax: Int? = null,
    val brand: String? = null,
    // [image] is the first image, kept for back-compat; [images] is the full list (server #70.2).
    val image: TeaImageDto? = null,
    val images: List<TeaImageDto> = emptyList(),
    val names: List<TeaNameDto> = emptyList(),
    val descriptions: List<TeaDescriptionDto> = emptyList(),
    val flavors: List<TeaFlavorDto> = emptyList(),
    val provenance: TeaProvenanceDto? = null,
    // Async LLM enrichment state (null = not LLM-managed): PENDING while a job runs, DONE/FAILED after.
    val enrichmentState: String? = null,
)

@Serializable
data class FacetsDto(
    val types: List<String> = emptyList(),
    val origins: List<String> = emptyList(),
)

/**
 * Content-free lifecycle body returned with HTTP 410 for a withdrawn (`retracted`) tea, or a `merged`
 * tea whose survivor chain is broken (server #137-C3). Carries no catalog content by design — a
 * retraction genuinely withdraws data. [supersededByPublicId] points at the survivor when known.
 */
@Serializable
data class TeaLifecycleDto(
    val publicId: String? = null,
    val status: String = "retracted",
    val supersededByPublicId: String? = null,
    val message: String? = null,
)

/**
 * `POST /teas/resolve` body (plan §6): the typed tea name (any of ru/en/zh-Hans) plus an optional
 * pasted vendor blurb that grounds the flavor profile (#25). Mirrors the server `ResolveRequestDto`.
 */
@Serializable
data class ResolveRequestDto(
    val name: String,
    val locale: String? = null,
    val sourceText: String? = null,
)

/**
 * `POST /teas/resolve` response: [status] is one of MATCHED / ENRICHED / ENRICHING / UNRESOLVED;
 * [tea] is the resolved row (null only for UNRESOLVED). On ENRICHING the row is a PENDING stub the
 * client polls via `GET /teas/{id}` until it flips to DONE/FAILED.
 */
@Serializable
data class ResolveResponseDto(
    val status: String,
    val tea: TeaDetailDto? = null,
)

/**
 * `POST /teas/ocr` response: [text] is the raw recognized packaging text; [corrected] is the
 * dictionary-gated description cleanup (decision #125). Null/blank means nothing legible was found.
 * Mirrors the server `OcrResponseDto`; `corrected` is optional so an older server still binds.
 */
@Serializable
data class OcrResponseDto(
    val text: String? = null,
    val corrected: String? = null,
)
