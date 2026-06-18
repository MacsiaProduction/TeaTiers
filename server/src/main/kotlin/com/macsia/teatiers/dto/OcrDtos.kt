package com.macsia.teatiers.dto

/**
 * `POST /api/v1/teas/ocr` response: the recognized, sanitized packaging text. The client shows it in
 * an editable review field; on confirm the user routes it into the add-tea `sourceText` field (#25).
 * The scanned image itself is never returned or stored.
 */
data class OcrResponseDto(val text: String)
