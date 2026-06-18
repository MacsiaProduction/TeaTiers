package com.macsia.teatiers.client

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import java.time.Duration
import org.slf4j.LoggerFactory
import org.springframework.core.io.ByteArrayResource
import org.springframework.http.MediaType
import org.springframework.http.client.SimpleClientHttpRequestFactory
import org.springframework.stereotype.Component
import org.springframework.util.LinkedMultiValueMap
import org.springframework.web.client.RestClient
import org.springframework.web.client.RestClientException

/**
 * Posts a scanned packaging image to the internal RapidOCR sidecar (`POST {sidecarUrl}/ocr`,
 * multipart `file`) and returns the recognized text, or null on outage. Callers must check
 * [isEnabled] first so a missing [OcrProperties.sidecarUrl] degrades the tier (503) instead of
 * erroring. The sidecar is reachable only on the private compose network (never published).
 */
@Component
class OcrClient(
    private val props: OcrProperties,
) {

    private val log = LoggerFactory.getLogger(javaClass)

    init {
        requireHttpUrl(props.sidecarUrl, "teatiers.ocr.sidecar-url")
    }

    val isEnabled: Boolean get() = props.enabled && props.sidecarUrl.isNotBlank()

    private val restClient: RestClient = RestClient.builder()
        .requestFactory(
            SimpleClientHttpRequestFactory().apply {
                setConnectTimeout(Duration.ofMillis(props.connectTimeoutMs.toLong()))
                setReadTimeout(Duration.ofMillis(props.readTimeoutMs.toLong()))
            },
        )
        .build()

    /** Recognizes [image]; returns the raw concatenated text (unsanitized) or null on failure. */
    fun recognize(image: ByteArray, filename: String): String? {
        // A named ByteArrayResource makes Spring emit a proper multipart file part.
        val filePart = object : ByteArrayResource(image) {
            override fun getFilename() = filename
        }
        val body = LinkedMultiValueMap<String, Any>().apply { add("file", filePart) }
        return try {
            restClient.post()
                .uri("${props.sidecarUrl.trimEnd('/')}/ocr")
                .contentType(MediaType.MULTIPART_FORM_DATA)
                .body(body)
                .retrieve()
                .body(OcrSidecarResponse::class.java)
                ?.text
        } catch (ex: RestClientException) {
            log.warn("OCR sidecar call failed: {}", ex.message)
            null
        }
    }
}

@JsonIgnoreProperties(ignoreUnknown = true)
data class OcrSidecarResponse(val text: String?)
