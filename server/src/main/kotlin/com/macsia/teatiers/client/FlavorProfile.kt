package com.macsia.teatiers.client

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.annotation.JsonProperty

/**
 * Parsed LLM flavor-profiling output (research 07 schema). Everything is nullable on the wire: the
 * Kotlin validator in the enrichment service is the source of truth, not the model's contract
 * (no vendor guarantees schema conformance — run 07). Unknown fields are ignored.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
data class FlavorProfile(
    val names: LlmNames? = null,
    val type: String? = null,
    val dimensions: Map<String, LlmDim>? = null,
    @JsonProperty("short_blurb_ru") val shortBlurbRu: String? = null,
    @JsonProperty("overall_confidence") val overallConfidence: Double? = null,
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class LlmNames(
    @JsonProperty("display_ru") val displayRu: String? = null,
    val original: String? = null,
    val pinyin: String? = null,
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class LlmDim(
    val value: Int? = null,
    val confidence: Double? = null,
    val evidence: Boolean? = null,
)
