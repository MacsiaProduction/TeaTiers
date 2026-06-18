package com.macsia.teatiers.controller

import com.macsia.teatiers.client.OcrProperties
import com.macsia.teatiers.domain.TeaType
import com.macsia.teatiers.dto.FacetsDto
import com.macsia.teatiers.dto.OcrResponseDto
import com.macsia.teatiers.dto.PageDto
import com.macsia.teatiers.dto.ResolveRequestDto
import com.macsia.teatiers.dto.ResolveResponseDto
import com.macsia.teatiers.dto.TeaDetailDto
import com.macsia.teatiers.dto.TeaSummaryDto
import com.macsia.teatiers.service.FixedWindowRateLimiter
import com.macsia.teatiers.service.OcrService
import com.macsia.teatiers.service.ResolveService
import com.macsia.teatiers.service.TeaCatalogService
import jakarta.servlet.http.HttpServletRequest
import jakarta.validation.Valid
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.multipart.MultipartFile

/** Catalog API (plan.md sections 5-6): read-only search/detail + the `/resolve` enrich-on-miss flow. */
@RestController
@RequestMapping("/api/v1/teas")
class TeaController(
    private val service: TeaCatalogService,
    private val resolveService: ResolveService,
    @Qualifier("resolveRateLimiter") private val resolveRateLimiter: FixedWindowRateLimiter,
    @Qualifier("ocrRateLimiter") private val ocrRateLimiter: FixedWindowRateLimiter,
    private val ocrService: OcrService,
    private val ocrProperties: OcrProperties,
) {

    @GetMapping("/search")
    fun search(
        @RequestParam(required = false) q: String?,
        @RequestParam(required = false) locale: String?,
        @RequestParam(required = false) type: TeaType?,
        @RequestParam(required = false) origin: String?,
        @RequestParam(required = false) cursor: Long?,
        @RequestParam(defaultValue = "${TeaCatalogService.DEFAULT_LIMIT}") limit: Int,
    ): PageDto<TeaSummaryDto> = service.search(q, locale, type, origin, cursor, limit)

    @GetMapping("/facets")
    fun facets(): FacetsDto = service.facets()

    @GetMapping("/{id}")
    fun detail(@PathVariable id: Long): TeaDetailDto =
        service.detail(id) ?: throw TeaNotFoundException(id)

    /**
     * Resolves a typed tea name to a catalog row: Wikidata on a cache miss, then a background LLM
     * profile on a Wikidata miss (status ENRICHING + a PENDING row the client polls). Per-client
     * rate-limited (cost protection, not auth) using the forwarded client IP.
     */
    @PostMapping("/resolve")
    fun resolve(@Valid @RequestBody request: ResolveRequestDto, servletRequest: HttpServletRequest): ResolveResponseDto {
        if (!resolveRateLimiter.tryAcquire(clientId(servletRequest))) throw RateLimitException()
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
        return OcrResponseDto(ocrService.recognize(file.bytes, file.originalFilename ?: "image"))
    }

    /** Trust the first X-Forwarded-For hop set by our own reverse proxy (Caddy); fall back to the socket. */
    private fun clientId(request: HttpServletRequest): String =
        request.getHeader("X-Forwarded-For")?.substringBefore(',')?.trim()?.takeIf { it.isNotEmpty() }
            ?: request.remoteAddr
            ?: "unknown"
}

class TeaNotFoundException(val teaId: Long) :
    RuntimeException("No tea with id $teaId")

/** Per-client window budget exhausted on `/resolve` or `/ocr` — maps to 429. */
class RateLimitException :
    RuntimeException("Rate limit exceeded")

/** The OCR tier is not configured (no sidecar URL) — `/teas/ocr` returns 503. */
class OcrUnavailableException :
    RuntimeException("OCR tier is not available")

/** OCR sidecar call failed (outage/timeout) — `/teas/ocr` returns 502. */
class OcrFailedException :
    RuntimeException("OCR recognition failed")

/** The uploaded image exceeds the configured size cap — `/teas/ocr` returns 413. */
class OcrImageTooLargeException :
    RuntimeException("OCR image too large")

/** The OCR request is malformed (empty file) — `/teas/ocr` returns 400. */
class OcrBadRequestException(message: String) :
    RuntimeException(message)
