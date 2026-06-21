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
import java.util.UUID

@Service
@Transactional(readOnly = true)
class TeaCatalogService(
    private val teaRepository: TeaRepository,
) {

    /**
     * Catalog search. A blank query browses every tea (ordered by id, keyset-paginated); a non-blank
     * query is typo-tolerant (pg_trgm) and returns a single rank-ordered best-match page (decision
     * #79). The cursor only applies to browse.
     */
    fun search(
        query: String?,
        locale: String?,
        type: TeaType?,
        origin: String?,
        cursor: Long?,
        limit: Int,
    ): PageDto<TeaSummaryDto> {
        val capped = limit.coerceIn(1, MAX_LIMIT)
        val fuzzy = !query.isNullOrBlank()
        val ids = teaRepository.searchIds(
            q = query,
            locale = locale,
            type = type,
            origin = origin,
            cursor = if (fuzzy) null else cursor,
            limit = capped,
        )
        if (ids.isEmpty()) return PageDto(emptyList(), null)

        // findAllWithNames re-sorts by id, so re-impose the searchIds order (rank for fuzzy).
        val byId = teaRepository.findAllWithNames(ids).associateBy { requireNotNull(it.id) }
        val items = ids.mapNotNull { byId[it]?.toSummary() }
        val nextCursor = if (!fuzzy && items.size == capped) items.last().id else null
        return PageDto(items, nextCursor)
    }

    fun detail(id: Long): TeaDetailDto? = teaRepository.findById(id).map { it.toDetail() }.orElse(null)

    /**
     * Resolve by the stable public id (V7, decision #136). A 'merged' tea resolves to its survivor (the
     * client should re-cache the survivor's public_id); a 'retracted' tea returns its own tombstone detail
     * (status = 'retracted') rather than a 404, so a client holding the id can show "unavailable" instead of
     * silently losing the reference. Returns null only for a public_id that was never issued.
     */
    fun detailByPublicId(publicId: UUID): TeaDetailDto? {
        val tea = teaRepository.findByPublicId(publicId) ?: return null
        if (tea.status == "merged") {
            val survivor = tea.mergedIntoPublicId?.let { teaRepository.findByPublicId(it) }
            // Fall back to the merged row's own tombstone if the survivor is somehow missing.
            return (survivor ?: tea).toDetail()
        }
        return tea.toDetail()
    }

    fun facets(): FacetsDto = FacetsDto(
        types = teaRepository.distinctTypes(),
        origins = teaRepository.distinctOrigins(),
    )

    private fun Tea.toSummary() = TeaSummaryDto(
        id = requireNotNull(id),
        publicId = publicId,
        type = type,
        originCountry = originCountry,
        brand = brand,
        verificationStatus = verificationStatus,
        names = names.toNameDtos(),
    )

    private fun Tea.toDetail() = TeaDetailDto(
        id = requireNotNull(id),
        publicId = publicId,
        status = status,
        supersededByPublicId = mergedIntoPublicId,
        wikidataQid = wikidataQid,
        type = type,
        originCountry = originCountry,
        region = region,
        cultivar = cultivar,
        oxidationMin = oxidationMin?.toInt(),
        oxidationMax = oxidationMax?.toInt(),
        brand = brand,
        images = images
            .sortedBy { it.position }
            .map { TeaImageDto(url = it.url, license = it.license, sourceUrl = it.sourceUrl) },
        image = images.minByOrNull { it.position }
            ?.let { TeaImageDto(url = it.url, license = it.license, sourceUrl = it.sourceUrl) },
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
        enrichmentState = enrichmentState,
    )

    private fun List<TeaName>.toNameDtos() = this
        .sortedWith(compareByDescending<TeaName> { it.isPrimary }.thenBy { it.locale }.thenBy { it.name })
        .map { TeaNameDto(it.locale, it.name, it.isPrimary) }

    companion object {
        const val MAX_LIMIT = 50
        const val DEFAULT_LIMIT = 20
    }
}
