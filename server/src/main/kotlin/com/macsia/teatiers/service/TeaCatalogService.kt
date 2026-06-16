package com.macsia.teatiers.service

import com.macsia.teatiers.domain.Tea
import com.macsia.teatiers.domain.TeaName
import com.macsia.teatiers.domain.TeaType
import com.macsia.teatiers.dto.FacetsDto
import com.macsia.teatiers.dto.PageDto
import com.macsia.teatiers.dto.TeaDescriptionDto
import com.macsia.teatiers.dto.TeaDetailDto
import com.macsia.teatiers.dto.TeaFlavorDto
import com.macsia.teatiers.dto.TeaImageDto
import com.macsia.teatiers.dto.TeaNameDto
import com.macsia.teatiers.dto.TeaProvenanceDto
import com.macsia.teatiers.dto.TeaSummaryDto
import com.macsia.teatiers.repository.TeaRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

@Service
@Transactional(readOnly = true)
class TeaCatalogService(
    private val teaRepository: TeaRepository,
) {

    /** Cursor-paged catalog search. A blank query returns every tea (ordered by id). */
    fun search(
        query: String?,
        locale: String?,
        type: TeaType?,
        origin: String?,
        cursor: Long?,
        limit: Int,
    ): PageDto<TeaSummaryDto> {
        val capped = limit.coerceIn(1, MAX_LIMIT)
        val ids = teaRepository.searchIds(
            q = query,
            locale = locale,
            type = type,
            origin = origin,
            cursor = cursor,
            limit = capped,
        )
        if (ids.isEmpty()) return PageDto(emptyList(), null)

        val items = teaRepository.findAllWithNames(ids).map { it.toSummary() }
        val nextCursor = if (items.size == capped) items.last().id else null
        return PageDto(items, nextCursor)
    }

    fun detail(id: Long): TeaDetailDto? = teaRepository.findById(id).map { it.toDetail() }.orElse(null)

    fun facets(): FacetsDto = FacetsDto(
        types = teaRepository.distinctTypes(),
        origins = teaRepository.distinctOrigins(),
    )

    private fun Tea.toSummary() = TeaSummaryDto(
        id = requireNotNull(id),
        type = type,
        originCountry = originCountry,
        brand = brand,
        verificationStatus = verificationStatus,
        names = names.toNameDtos(),
    )

    private fun Tea.toDetail() = TeaDetailDto(
        id = requireNotNull(id),
        wikidataQid = wikidataQid,
        type = type,
        originCountry = originCountry,
        region = region,
        cultivar = cultivar,
        oxidationMin = oxidationMin?.toInt(),
        oxidationMax = oxidationMax?.toInt(),
        brand = brand,
        image = imageUrl?.let { TeaImageDto(url = it, license = imageLicense, sourceUrl = imageSourceUrl) },
        names = names.toNameDtos(),
        descriptions = descriptions
            .sortedBy { it.locale }
            .map { TeaDescriptionDto(it.locale, it.shortText, it.fullText, it.source, it.license) },
        flavors = flavors
            .sortedBy { it.dimension.ordinal }
            .map { TeaFlavorDto(it.dimension, it.intensity.toInt()) },
        provenance = TeaProvenanceDto(
            source = source,
            sourceUrl = sourceUrl,
            license = license,
            verificationStatus = verificationStatus,
            confidence = confidence,
        ),
    )

    private fun List<TeaName>.toNameDtos() = this
        .sortedWith(compareByDescending<TeaName> { it.isPrimary }.thenBy { it.locale }.thenBy { it.name })
        .map { TeaNameDto(it.locale, it.name, it.isPrimary) }

    companion object {
        const val MAX_LIMIT = 50
        const val DEFAULT_LIMIT = 20
    }
}
