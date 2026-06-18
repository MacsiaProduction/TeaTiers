package com.macsia.teatiers.client

import org.springframework.boot.context.properties.ConfigurationProperties

/**
 * Tunables for the OCR tier (research run 10; decision #96/#94 — ru+en, server-side, opt-in). The
 * backend proxies a user-scanned packaging photo to a local RapidOCR sidecar and returns extracted
 * text for the client to review before it becomes `sourceText` (#25). [sidecarUrl] is injected at
 * deploy time; blank OR `enabled=false` => the tier is off and `/teas/ocr` returns 503 (mirrors how
 * the LLM tier degrades on a missing key). The image is processed in memory and never stored.
 */
@ConfigurationProperties(prefix = "teatiers.ocr")
data class OcrProperties(
    val enabled: Boolean = true,
    /** Base URL of the internal RapidOCR sidecar (compose network); blank => tier disabled. */
    val sidecarUrl: String = "",
    /** Reject uploads larger than this before reading them (also bounded by Spring multipart limits). */
    val maxImageBytes: Long = 8L * 1024 * 1024,
    /**
     * Per-client fixed-window cap for POST /ocr. Its own budget, independent of /resolve's, since a
     * scan triggers sidecar inference (heavier than a Wikidata/cache hit) — so the default is lower.
     */
    val ratePerMinute: Int = 10,
    /** Cap on returned text, mirroring the `sourceText` server cap so the review field can't overflow. */
    val maxTextLength: Int = 4_000,
    val connectTimeoutMs: Int = 3_000,
    val readTimeoutMs: Int = 20_000,
)
