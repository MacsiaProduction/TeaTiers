package com.macsia.teatiers.controller

import com.macsia.teatiers.domain.TeaType
import com.macsia.teatiers.dto.FacetsDto
import com.macsia.teatiers.dto.PageDto
import com.macsia.teatiers.dto.TeaDetailDto
import com.macsia.teatiers.dto.TeaSummaryDto
import com.macsia.teatiers.service.TeaCatalogService
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

/** Read-only catalog API (plan.md section 5). Write/enrich endpoints arrive in M4. */
@RestController
@RequestMapping("/api/v1/teas")
class TeaController(
    private val service: TeaCatalogService,
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
}

class TeaNotFoundException(val teaId: Long) :
    RuntimeException("No tea with id $teaId")
