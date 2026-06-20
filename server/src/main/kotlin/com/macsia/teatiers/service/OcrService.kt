package com.macsia.teatiers.service

import com.macsia.teatiers.client.OcrClient
import com.macsia.teatiers.client.OcrProperties
import com.macsia.teatiers.controller.OcrFailedException
import com.macsia.teatiers.controller.OcrUnavailableException
import com.macsia.teatiers.dto.OcrResponseDto
import org.springframework.stereotype.Service

/**
 * Orchestrates the OCR tier: rejects when the sidecar isn't configured (so the endpoint 503s instead
 * of erroring), runs recognition, and sanitizes the result. The image is held only for the duration
 * of the call and never persisted (decision #96).
 */
@Service
class OcrService(
    private val client: OcrClient,
    private val props: OcrProperties,
) {

    /** Recognizes [image] and returns cleaned, length-capped raw + dict-corrected text. */
    fun recognize(image: ByteArray, filename: String): OcrResponseDto {
        if (!client.isEnabled) throw OcrUnavailableException()
        val response = client.recognize(image, filename) ?: throw OcrFailedException()
        val text = OcrSanitizer.clean(response.text ?: "", props.maxTextLength)
        // Sidecar's dictionary-gated correction (decision #125); fall back to raw if a pre-#104
        // sidecar didn't send it. Sanitized the same way (length cap + control-char strip).
        val corrected = OcrSanitizer.clean(response.corrected ?: response.text ?: "", props.maxTextLength)
        return OcrResponseDto(text = text, corrected = corrected)
    }
}
