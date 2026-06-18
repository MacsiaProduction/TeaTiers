package com.macsia.teatiers.service

import com.macsia.teatiers.client.OcrClient
import com.macsia.teatiers.client.OcrProperties
import com.macsia.teatiers.controller.OcrFailedException
import com.macsia.teatiers.controller.OcrUnavailableException
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

    /** Recognizes [image] and returns cleaned, length-capped text. */
    fun recognize(image: ByteArray, filename: String): String {
        if (!client.isEnabled) throw OcrUnavailableException()
        val raw = client.recognize(image, filename) ?: throw OcrFailedException()
        return OcrSanitizer.clean(raw, props.maxTextLength)
    }
}
