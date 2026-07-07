package com.macsia.teatiers.client

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import java.time.Duration
import org.slf4j.LoggerFactory
import org.springframework.core.io.ByteArrayResource
import org.springframework.http.MediaType
import org.springframework.http.client.SimpleClientHttpRequestFactory
import org.springframework.stereotype.Component
import org.springframework.util.LinkedMultiValueMap
import org.springframework.web.client.HttpClientErrorException
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

    /** Recognizes [image]; returns the sidecar response (raw + dict-corrected text) or null on failure. */
    fun recognize(image: ByteArray, filename: String): OcrSidecarResponse? {
        // A named ByteArrayResource makes Spring emit a proper multipart file part.
        val filePart = object : ByteArrayResource(image) {
            override fun getFilename() = filename
        }
        val body = LinkedMultiValueMap<String, Any>().apply { add("file", filePart) }
        var lastError: String? = null
        repeat(MAX_ATTEMPTS) {
            try {
                return restClient.post()
                    .uri("${props.sidecarUrl.trimEnd('/')}/ocr")
                    .contentType(MediaType.MULTIPART_FORM_DATA)
                    .body(body)
                    .retrieve()
                    .body(OcrSidecarResponse::class.java)
                    // A 200 missing `text` is a sidecar malfunction → null → 502; not a transient error,
                    // so it is returned, NOT retried.
                    ?.takeIf { it.text != null }
            } catch (ex: HttpClientErrorException) {
                // UX2-P1-7: 422 means the sidecar decoded the request fine but rejected the IMAGE ITSELF
                // (corrupt/unsupported bytes) — the client's fault, not a transport/engine failure.
                // Retrying the same bytes can't help, and it must not report the same "try later" as a
                // real outage. Any other 4xx falls through to the generic transient-retry path below.
                if (ex.statusCode.value() == 422) throw SidecarUnreadableImageException()
                lastError = ex.message
            } catch (ex: RestClientException) {
                // Transient transport / 5xx (e.g. a sidecar restart) → retry, then degrade. Mirrors the
                // sibling outbound clients. No Thread.sleep: the read timeout + the controller's
                // concurrency gate bound the worker hold, and the sidecar is on the private compose net.
                lastError = ex.message
            }
        }
        log.warn("OCR sidecar call failed after {} attempts: {}", MAX_ATTEMPTS, lastError)
        return null
    }

    private companion object {
        const val MAX_ATTEMPTS = 2
    }
}

@JsonIgnoreProperties(ignoreUnknown = true)
data class OcrSidecarResponse(val text: String?, val corrected: String? = null)

/** See [OcrClient.recognize]'s 422 handling (UX2-P1-7). Caught + translated by [com.macsia.teatiers.service.OcrService]. */
class SidecarUnreadableImageException : RuntimeException("sidecar could not decode the image")
