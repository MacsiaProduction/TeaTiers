package com.macsia.teatiers.dto

import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Size

/**
 * Request for `POST /api/v1/teas/resolve` (plan.md section 6). The client sends the typed tea name
 * (any of ru/en/zh-Hans) and may attach a pasted product blurb as [sourceText]; the blurb is
 * reserved for the LLM enrichment tier and is ignored by the Wikidata-only backbone. Caps are hard
 * limits (rule 50-secure: validate + bound all inputs).
 */
data class ResolveRequestDto(
    @field:NotBlank
    @field:Size(max = MAX_NAME_LENGTH)
    val name: String,
    @field:Size(max = MAX_LOCALE_LENGTH)
    val locale: String? = null,
    @field:Size(max = MAX_SOURCE_TEXT_LENGTH)
    val sourceText: String? = null,
) {
    companion object {
        const val MAX_NAME_LENGTH = 200
        const val MAX_LOCALE_LENGTH = 16
        const val MAX_SOURCE_TEXT_LENGTH = 4_000
    }
}

/**
 * Outcome of a resolve call:
 * - [MATCHED]: returned an existing catalog row (dedup/cache hit or a prior Wikidata import).
 * - [ENRICHED]: created a new (unverified) row from a fresh Wikidata match.
 * - [UNRESOLVED]: no catalog or Wikidata match; nothing was created (the LLM tier, when enabled,
 *   will fill this gap).
 */
enum class ResolveStatus { MATCHED, ENRICHED, UNRESOLVED }

/** Resolve response: the status plus the resolved tea (null only for [ResolveStatus.UNRESOLVED]). */
data class ResolveResponseDto(
    val status: ResolveStatus,
    val tea: TeaDetailDto?,
)
