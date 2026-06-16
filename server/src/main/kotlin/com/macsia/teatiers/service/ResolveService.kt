package com.macsia.teatiers.service

import com.macsia.teatiers.client.FoundationModelsClient
import com.macsia.teatiers.client.WikidataClient
import com.macsia.teatiers.dto.ResolveResponseDto
import com.macsia.teatiers.dto.ResolveStatus
import com.macsia.teatiers.repository.TeaRepository
import org.slf4j.LoggerFactory
import org.springframework.core.task.TaskRejectedException
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.stereotype.Service

/**
 * Orchestrates `POST /api/v1/teas/resolve` (plan.md section 6):
 *
 *   1. cache/dedup hit — a known tea (by normalized name) is returned as-is, so the second user to
 *      type it never re-enriches; a previously FAILED LLM stub is re-armed and retried here;
 *   2. Wikidata match — a fresh hit is imported as a new unverified row (CC0, high confidence);
 *   3. miss — when the LLM tier is enabled, a minimal PENDING stub is created and enriched in the
 *      background (status ENRICHING); otherwise the call returns UNRESOLVED.
 *
 * `sourceText` is an optional pasted vendor blurb; it is grounding evidence for the LLM tier only and
 * is ignored by the Wikidata backbone.
 */
@Service
class ResolveService(
    private val teaRepository: TeaRepository,
    private val wikidataClient: WikidataClient,
    private val upsertService: WikidataUpsertService,
    private val catalogService: TeaCatalogService,
    private val foundationModelsClient: FoundationModelsClient,
    private val enrichmentStubService: EnrichmentStubService,
    private val llmEnrichmentService: LlmEnrichmentService,
    private val llmDailyBudget: LlmDailyBudget,
) {

    private val log = LoggerFactory.getLogger(javaClass)

    fun resolve(rawName: String, locale: String?, sourceText: String?): ResolveResponseDto {
        val name = rawName.trim()
        if (name.isEmpty()) return ResolveResponseDto(ResolveStatus.UNRESOLVED, null)

        teaRepository.findIdByNormalizedName(name)?.let { return cacheHit(it, name, sourceText) }

        val match = wikidataClient.findTea(name, locale)
        if (match != null && (match.nameEn != null || match.nameRu != null || match.nameZhHans != null)) {
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

        if (!foundationModelsClient.isEnabled) return ResolveResponseDto(ResolveStatus.UNRESOLVED, null)
        // Global daily cost ceiling: once exhausted, a miss fails closed to Wikidata-only (no stub,
        // no LLM call) rather than queuing unbounded paid work behind a shared IP.
        if (!llmDailyBudget.tryAcquire()) return ResolveResponseDto(ResolveStatus.UNRESOLVED, null)
        return startEnrichment(name, locale, sourceText)
    }

    /** A known row: re-arm + retry a previously FAILED LLM stub, else report MATCHED/ENRICHING. */
    private fun cacheHit(id: Long, name: String, sourceText: String?): ResolveResponseDto {
        // detail() loads the row we need anyway, so read the state off the DTO (no extra query).
        val dto = catalogService.detail(id) ?: return ResolveResponseDto(ResolveStatus.UNRESOLVED, null)
        // A FAILED stub is retryable, but the daily ceiling also gates the retry: when it is exhausted
        // we leave the row FAILED rather than spend on a re-enrich.
        if (dto.enrichmentState == STATE_FAILED && foundationModelsClient.isEnabled && llmDailyBudget.tryAcquire()) {
            enrichmentStubService.resetToPending(id)
            dispatch(id, name, sourceText)
            return respond(ResolveStatus.ENRICHING, id)
        }
        return ResolveResponseDto(statusFor(dto.enrichmentState), dto)
    }

    private fun startEnrichment(name: String, locale: String?, sourceText: String?): ResolveResponseDto {
        val stub = try {
            enrichmentStubService.createOrGetStub(name, locale)
        } catch (ex: DataIntegrityViolationException) {
            // Lost a stub-insert race; the row exists now — treat it as a cache hit.
            val id = teaRepository.findIdByNormalizedName(name)
                ?: run { log.warn("Stub conflict for '{}' but no row found on re-read", name); throw ex }
            return cacheHit(id, name, sourceText)
        }
        if (stub.created) {
            dispatch(stub.id, name, sourceText)
            return respond(ResolveStatus.ENRICHING, stub.id)
        }
        return respond(statusFor(stub.state), stub.id)
    }

    private fun dispatch(id: Long, name: String, sourceText: String?) {
        try {
            llmEnrichmentService.enrich(id, name, sourceText)
        } catch (ex: TaskRejectedException) {
            // Enrichment pool saturated: fail this row fast so the client sees FAILED and can retry.
            log.warn("Enrichment queue full; marking tea {} FAILED", id)
            enrichmentStubService.markFailed(id, "enrichment queue full")
        }
    }

    private fun statusFor(state: String?): ResolveStatus =
        if (state == STATE_PENDING) ResolveStatus.ENRICHING else ResolveStatus.MATCHED

    private fun matched(id: Long): ResolveResponseDto = respond(ResolveStatus.MATCHED, id)

    private fun respond(status: ResolveStatus, id: Long): ResolveResponseDto =
        ResolveResponseDto(status, catalogService.detail(id))

    private companion object {
        const val STATE_PENDING = "PENDING"
        const val STATE_FAILED = "FAILED"
    }
}
