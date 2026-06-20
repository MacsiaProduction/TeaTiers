package com.macsia.teatiers.dto

/**
 * `POST /api/v1/teas/ocr` response: the recognized, sanitized packaging text. [text] is the raw OCR;
 * [corrected] is the sidecar's dictionary-gated description cleanup (decision #125 — homoglyphs fixed
 * to real RU words, un-validatable tokens kept raw). The client shows [corrected] in an editable
 * review field; on confirm the user routes it into the add-tea `sourceText` field (#25), which feeds
 * enrichment. The scanned image itself is never returned or stored.
 */
data class OcrResponseDto(val text: String, val corrected: String)
