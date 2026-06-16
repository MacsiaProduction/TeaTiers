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
    val wikidataQid: String? = null,
    val type: String,
    val originCountry: String? = null,
    val region: String? = null,
    val cultivar: String? = null,
    val oxidationMin: Int? = null,
    val oxidationMax: Int? = null,
    val brand: String? = null,
    val image: TeaImageDto? = null,
    val names: List<TeaNameDto> = emptyList(),
    val descriptions: List<TeaDescriptionDto> = emptyList(),
    val flavors: List<TeaFlavorDto> = emptyList(),
    val provenance: TeaProvenanceDto? = null,
)

@Serializable
data class FacetsDto(
    val types: List<String> = emptyList(),
    val origins: List<String> = emptyList(),
)
