package com.macsia.teatiers.controller

import com.macsia.teatiers.client.OcrProperties
import com.macsia.teatiers.domain.TeaType
import com.macsia.teatiers.dto.CatalogDetail
import com.macsia.teatiers.dto.FacetsDto
import com.macsia.teatiers.dto.OcrResponseDto
import com.macsia.teatiers.dto.PageDto
import com.macsia.teatiers.dto.ResolveRequestDto
import com.macsia.teatiers.dto.ResolveResponseDto
import com.macsia.teatiers.dto.TeaSummaryDto
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import com.macsia.teatiers.service.ClientRateLimiter
import com.macsia.teatiers.service.OcrService
import com.macsia.teatiers.service.ResolveService
import com.macsia.teatiers.service.TeaCatalogService
import io.github.bucket4j.Bucket
import jakarta.servlet.http.HttpServletRequest
import jakarta.validation.Valid
import jakarta.validation.constraints.Size
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.validation.annotation.Validated
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.multipart.MultipartFile
import java.util.UUID
import java.util.concurrent.Semaphore

/** Catalog API (plan.md sections 5-6): read-only search/detail + the `/resolve` enrich-on-miss flow. */
@RestController
// @Validated enables the @Size param caps below; without it Spring skips method-param validation and the
// caps on q/locale/origin silently never run (SRV-P2-2).
@Validated
@RequestMapping("/api/v1/teas")
class TeaController(
    private val service: TeaCatalogService,
    private val resolveService: ResolveService,
    @Qualifier("resolveRateLimiter") private val resolveRateLimiter: ClientRateLimiter,
    @Qualifier("ocrRateLimiter") private val ocrRateLimiter: ClientRateLimiter,
    @Qualifier("searchRateLimiter") private val searchRateLimiter: ClientRateLimiter,
    private val edgeRateBucket: Bucket,
    private val ocrConcurrencyGate: Semaphore,
    private val ocrService: OcrService,
    private val ocrProperties: OcrProperties,
) {

    /**
     * Catalog search/browse. Free-text params are length-capped (review P2 — bound an
     * unauthenticated public endpoint's inputs) and the endpoint is per-client rate-limited
     * (generously — an anti-abuse floor, not a UX limit). `limit` is additionally coerced to
     * [TeaCatalogService.MAX_LIMIT] in the service.
     */
    @GetMapping("/search")
    fun search(
        @RequestParam(required = false) @Size(max = MAX_QUERY_LEN) q: String?,
        @RequestParam(required = false) @Size(max = MAX_PARAM_LEN) locale: String?,
        @RequestParam(required = false) type: TeaType?,
        @RequestParam(required = false) @Size(max = MAX_PARAM_LEN) origin: String?,
        @RequestParam(required = false) cursor: Long?,
        @RequestParam(defaultValue = "${TeaCatalogService.DEFAULT_LIMIT}") limit: Int,
        servletRequest: HttpServletRequest,
    ): PageDto<TeaSummaryDto> {
        if (!searchRateLimiter.tryAcquire(clientId(servletRequest))) throw RateLimitException()
        if (!edgeRateBucket.tryConsume(1)) throw EdgeOverloadException() // global ceiling no key churn can reset
        return service.search(q, locale, type, origin, cursor, limit)
    }

    @GetMapping("/facets")
    fun facets(): FacetsDto = service.facets()

    /**
     * Detail by the legacy numeric id (compat for apps shipped before the public_id cutover, decision
     * #137-C1). Resolves through the immutable legacy map, so a renumbering rebuild never returns a
     * different tea; an id that was never issued 404s. New clients should use `/by-public-id/{uuid}`.
     */
    @GetMapping("/{id}")
    fun detail(@PathVariable id: Long): ResponseEntity<Any> =
        respond(service.detailByLegacyId(id)) { throw TeaNotFoundException(id) }

    /**
     * Detail by the stable public id (V7, decision #136 + #139-R2). 'active' / resolvable-merge returns full
     * detail (200); a retracted tea or a broken merge chain returns a content-free lifecycle tombstone
     * (410 Gone); only a public_id that was never issued 404s.
     */
    @GetMapping("/by-public-id/{publicId}")
    fun detailByPublicId(@PathVariable publicId: UUID): ResponseEntity<Any> =
        respond(service.detailByPublicId(publicId)) { throw TeaPublicNotFoundException(publicId) }

    /** Full content -> 200; a lifecycle tombstone (retracted / broken merge) -> 410 Gone; null -> 404. */
    private inline fun respond(result: CatalogDetail?, onMissing: () -> Nothing): ResponseEntity<Any> =
        when (result) {
            is CatalogDetail.Full -> ResponseEntity.ok(result.tea)
            is CatalogDetail.Tombstone -> ResponseEntity.status(HttpStatus.GONE).body(result.lifecycle)
            null -> onMissing()
        }

    /**
     * Resolves a typed tea name to a catalog row: Wikidata on a cache miss, then a background LLM
     * profile on a Wikidata miss (status ENRICHING + a PENDING row the client polls). Per-client
     * rate-limited (cost protection, not auth) using the forwarded client IP.
     */
    @PostMapping("/resolve")
    fun resolve(@Valid @RequestBody request: ResolveRequestDto, servletRequest: HttpServletRequest): ResolveResponseDto {
        if (!resolveRateLimiter.tryAcquire(clientId(servletRequest))) throw RateLimitException()
        if (!edgeRateBucket.tryConsume(1)) throw EdgeOverloadException() // global ceiling no key churn can reset
        return resolveService.resolve(request.name, request.locale, request.sourceText)
    }

    /**
     * Recognizes text from a user-scanned packaging photo (research run 10, decision #96) and returns
     * it for client-side review before it becomes `sourceText` (#25). Opt-in per scan: the image is
     * processed in memory and never stored. Has its OWN per-client cost window, separate from
     * `/resolve` (decision #103). Returns 503 until the OCR sidecar is configured (`teatiers.ocr.sidecar-url`).
     */
    @PostMapping("/ocr")
    fun ocr(
        @RequestParam("file") file: MultipartFile,
        servletRequest: HttpServletRequest,
    ): OcrResponseDto {
        // Validate the cheap, request-shape failures (empty / oversized) BEFORE spending a rate-limit
        // token, so a malformed upload can't burn the caller's OCR budget (review P2).
        if (file.isEmpty) throw OcrBadRequestException("Empty image")
        if (file.size > ocrProperties.maxImageBytes) throw OcrImageTooLargeException()
        if (!ocrRateLimiter.tryAcquire(clientId(servletRequest))) throw RateLimitException()
        // Global ceiling on the expensive sidecar path too (like /search and /resolve): the per-client
        // limiter keys on a spoofable X-Forwarded-For, so without this a fabricated-XFF-per-request
        // caller bypasses cost protection entirely (review finding). Key-churn can't reset this bucket.
        if (!edgeRateBucket.tryConsume(1)) throw EdgeOverloadException()
        // Global concurrency gate (review F4): the sidecar serializes inference, so fast-fail 503
        // when it's saturated rather than blocking a Tomcat worker behind it. Released in finally.
        if (!ocrConcurrencyGate.tryAcquire()) throw OcrBusyException()
        return try {
            ocrService.recognize(file.bytes, file.originalFilename ?: "image")
        } finally {
            ocrConcurrencyGate.release()
        }
    }

    /** Trust the first X-Forwarded-For hop set by our own reverse proxy (Caddy); fall back to the socket. */
    private fun clientId(request: HttpServletRequest): String =
        request.getHeader("X-Forwarded-For")?.substringBefore(',')?.trim()?.takeIf { it.isNotEmpty() }
            ?: request.remoteAddr
            ?: "unknown"

    private companion object {
        // Generous bounds on the public search params: a real query/locale/origin is far shorter, so
        // these only reject pathological inputs (long strings hitting pg_trgm / the DB).
        const val MAX_QUERY_LEN = 100
        const val MAX_PARAM_LEN = 64
    }
}

class TeaNotFoundException(val teaId: Long) :
    RuntimeException("No tea with id $teaId")

/** No tea was ever issued the given public id — `/teas/by-public-id/{publicId}` returns 404. */
class TeaPublicNotFoundException(val publicId: UUID) :
    RuntimeException("No tea with public id $publicId")

/** Per-client window budget exhausted on `/resolve` or `/ocr` — maps to 429. */
class RateLimitException :
    RuntimeException("Rate limit exceeded")

/**
 * The GLOBAL edge ceiling for `/search` + `/resolve` is saturated (decision #141 / PRIV-P1-1) — the service
 * is shedding total load (not a per-client fault), so this maps to 503 (transient, retryable).
 */
class EdgeOverloadException :
    RuntimeException("Service is shedding load")

/** The OCR tier is not configured (no sidecar URL) — `/teas/ocr` returns 503. */
class OcrUnavailableException :
    RuntimeException("OCR tier is not available")

/** The global OCR concurrency gate is saturated — `/teas/ocr` fast-fails 503 (transient, retryable). */
class OcrBusyException :
    RuntimeException("OCR is busy")

/** OCR sidecar call failed (outage/timeout) — `/teas/ocr` returns 502. */
class OcrFailedException :
    RuntimeException("OCR recognition failed")

/** The uploaded image exceeds the configured size cap — `/teas/ocr` returns 413. */
class OcrImageTooLargeException :
    RuntimeException("OCR image too large")

/** The OCR request is malformed (empty file) — `/teas/ocr` returns 400. */
class OcrBadRequestException(message: String) :
    RuntimeException(message)
