package com.macsia.teatiers.controller

import com.macsia.teatiers.domain.TeaType
import com.macsia.teatiers.dto.FacetsDto
import com.macsia.teatiers.dto.PageDto
import com.macsia.teatiers.dto.ResolveRequestDto
import com.macsia.teatiers.dto.ResolveResponseDto
import com.macsia.teatiers.dto.TeaDetailDto
import com.macsia.teatiers.dto.TeaSummaryDto
import com.macsia.teatiers.service.ResolveRateLimiter
import com.macsia.teatiers.service.ResolveService
import com.macsia.teatiers.service.TeaCatalogService
import jakarta.servlet.http.HttpServletRequest
import jakarta.validation.Valid
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

/** Catalog API (plan.md sections 5-6): read-only search/detail + the `/resolve` enrich-on-miss flow. */
@RestController
@RequestMapping("/api/v1/teas")
class TeaController(
    private val service: TeaCatalogService,
    private val resolveService: ResolveService,
    private val rateLimiter: ResolveRateLimiter,
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
     * Resolves a typed tea name to a catalog row, enriching from Wikidata on a cache miss. Per-client
     * rate-limited (cost protection, not auth) using the forwarded client IP.
     */
    @PostMapping("/resolve")
    fun resolve(@Valid @RequestBody request: ResolveRequestDto, servletRequest: HttpServletRequest): ResolveResponseDto {
        if (!rateLimiter.tryAcquire(clientId(servletRequest))) throw ResolveRateLimitException()
        return resolveService.resolve(request.name, request.locale)
    }

    /** Trust the first X-Forwarded-For hop set by our own reverse proxy (Caddy); fall back to the socket. */
    private fun clientId(request: HttpServletRequest): String =
        request.getHeader("X-Forwarded-For")?.substringBefore(',')?.trim()?.takeIf { it.isNotEmpty() }
            ?: request.remoteAddr
            ?: "unknown"
}

class TeaNotFoundException(val teaId: Long) :
    RuntimeException("No tea with id $teaId")

class ResolveRateLimitException :
    RuntimeException("Resolve rate limit exceeded")
