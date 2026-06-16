package com.macsia.teatiers.service

import com.macsia.teatiers.client.WikidataClient
import com.macsia.teatiers.dto.ResolveResponseDto
import com.macsia.teatiers.dto.ResolveStatus
import com.macsia.teatiers.repository.TeaRepository
import org.slf4j.LoggerFactory
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.stereotype.Service

/**
 * Orchestrates `POST /api/v1/teas/resolve` (plan.md section 6, Wikidata-first backbone):
 *
 *   1. cache/dedup hit — a known tea (by normalized name) is returned as-is, so the second user to
 *      type it never triggers enrichment;
 *   2. Wikidata match — a fresh hit is imported as a new unverified row (CC0, high confidence);
 *   3. miss — nothing is created; the LLM tier (deferred until credentials land) will fill this gap.
 *
 * `sourceText` is part of the request contract but reserved for the LLM tier, so it is ignored here.
 */
@Service
class ResolveService(
    private val teaRepository: TeaRepository,
    private val wikidataClient: WikidataClient,
    private val upsertService: WikidataUpsertService,
    private val catalogService: TeaCatalogService,
) {

    private val log = LoggerFactory.getLogger(javaClass)

    fun resolve(rawName: String, locale: String?): ResolveResponseDto {
        val name = rawName.trim()
        if (name.isEmpty()) return ResolveResponseDto(ResolveStatus.UNRESOLVED, null)

        teaRepository.findIdByNormalizedName(name)?.let { return matched(it) }

        val match = wikidataClient.findTea(name, locale)
        if (match == null || match.nameEn == null && match.nameRu == null && match.nameZhHans == null) {
            return ResolveResponseDto(ResolveStatus.UNRESOLVED, null)
        }

        val result = try {
            upsertService.createOrGet(match)
        } catch (ex: DataIntegrityViolationException) {
            // Lost an insert race with a concurrent resolve of the same new tea; the row exists now.
            val id = upsertService.findExisting(match)
                ?: run { log.warn("Resolve conflict for {} but no row found on re-read", match.qid); throw ex }
            return matched(id)
        }
        return respond(if (result.created) ResolveStatus.ENRICHED else ResolveStatus.MATCHED, result.id)
    }

    private fun matched(id: Long): ResolveResponseDto = respond(ResolveStatus.MATCHED, id)

    private fun respond(status: ResolveStatus, id: Long): ResolveResponseDto =
        ResolveResponseDto(status, catalogService.detail(id))
}
