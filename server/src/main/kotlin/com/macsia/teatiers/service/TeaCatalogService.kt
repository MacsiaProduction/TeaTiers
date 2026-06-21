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
import com.macsia.teatiers.repository.TeaLegacyIdMapRepository
import com.macsia.teatiers.repository.TeaRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

@Service
@Transactional(readOnly = true)
class TeaCatalogService(
    private val teaRepository: TeaRepository,
    private val legacyIdMapRepository: TeaLegacyIdMapRepository,
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

    /**
     * Direct detail by the current numeric id. INTERNAL only (e.g. [ResolveService] holds a freshly
     * resolved/created id) -- it deliberately does NOT apply the lifecycle/visibility rules so the resolve
     * poller can still observe a PENDING stub. The client-facing numeric route uses [detailByLegacyId].
     */
    fun detail(id: Long): TeaDetailDto? = teaRepository.findById(id).map { it.toDetail() }.orElse(null)

    /**
     * Client-facing numeric-id detail -- a COMPAT path for apps that cached the old BIGINT id (decision
     * #137-C1). It resolves through the immutable legacy map -> public_id, NEVER by a direct findById, so a
     * DB rebuild that renumbers tea.id can never point an old client at a different tea. An id that was
     * never issued returns null (404). Merged/retracted lifecycle is handled by [detailByPublicId].
     */
    fun detailByLegacyId(legacyId: Long): TeaDetailDto? {
        val publicId = legacyIdMapRepository.findById(legacyId).map { it.publicId }.orElse(null) ?: return null
        return detailByPublicId(publicId)
    }

    /** Compact summary by id; used by the operator review queue to show a proposed match candidate. */
    fun summary(id: Long): TeaSummaryDto? = teaRepository.findById(id).map { it.toSummary() }.orElse(null)

    /**
     * Resolve by the stable public id (V7, decision #136). A 'merged' tea resolves to the TERMINAL survivor
     * of its merge chain (bounded + cycle-guarded); the returned detail carries `supersededByPublicId` =
     * the survivor's id so the client knows to re-cache. A 'retracted' tea returns its own tombstone
     * (status = 'retracted') rather than a 404. Returns null only for a public_id that was never issued.
     */
    fun detailByPublicId(publicId: UUID): TeaDetailDto? {
        val tea = teaRepository.findByPublicId(publicId) ?: return null
        if (tea.status != "merged") return tea.toDetail()
        val survivor = resolveSurvivor(tea) ?: return tea.toDetail() // broken/cyclic chain -> own tombstone
        // Signal the redirect: the client requested a merged id; tell it the current canonical id.
        return survivor.toDetail().copy(supersededByPublicId = survivor.publicId)
    }

    /** Walk `merged_into_public_id` to the first non-merged row; null on a cycle or an over-long chain. */
    private fun resolveSurvivor(start: Tea): Tea? {
        var current = start
        val seen = mutableSetOf<UUID>(start.publicId)
        var hops = 0
        while (current.status == "merged" && hops < MAX_MERGE_HOPS) {
            val next = current.mergedIntoPublicId ?: return null
            if (!seen.add(next)) return null
            current = teaRepository.findByPublicId(next) ?: return null
            hops++
        }
        return current.takeIf { it.status != "merged" }
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
        private const val MAX_MERGE_HOPS = 16
    }
}
