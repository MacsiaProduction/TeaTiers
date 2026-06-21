package com.macsia.teatiers.service

import com.macsia.teatiers.domain.Tea
import com.macsia.teatiers.domain.TeaDescription
import com.macsia.teatiers.domain.TeaFlavor
import com.macsia.teatiers.domain.TeaName
import com.macsia.teatiers.domain.TeaType
import com.macsia.teatiers.repository.TeaLegacyIdMapRepository
import com.macsia.teatiers.repository.TeaRepository
import java.time.Instant
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

/**
 * Owns every DB write of the async LLM enrichment tier (plan.md section 6 step 3) in its own short
 * transactions, so the slow network call in [LlmEnrichmentService] never holds a row lock:
 *
 *   1. [createOrGetStub] — on a Wikidata miss, insert a minimal `PENDING` row from the user's name;
 *   2. [applyResult] — fill the validated profile and flip to `DONE`;
 *   3. [markFailed] / [resetToPending] — record a failure / re-arm a retry.
 */
@Service
class EnrichmentStubService(
    private val teaRepository: TeaRepository,
    private val legacyIdMapRepository: TeaLegacyIdMapRepository,
) {

    data class StubResult(val id: Long, val created: Boolean, val state: String?)

    @Transactional
    fun createOrGetStub(rawName: String, requestLocale: String?): StubResult {
        val name = rawName.trim()
        val dedupKey = DedupKeys.of(name, pinyin = null, type = TeaType.OTHER)
        teaRepository.findByDedupKey(dedupKey)?.let {
            return StubResult(requireNotNull(it.id), created = false, state = it.enrichmentState)
        }
        val tea = Tea(
            // Type is a placeholder until the LLM returns; the dedup key stays pinned to OTHER so the
            // row keeps a stable unique key even after the model reclassifies it.
            type = TeaType.OTHER,
            source = SOURCE_AI,
            dedupKey = dedupKey,
            verificationStatus = "unverified",
            enrichmentState = STATE_PENDING,
        )
        tea.addName(TeaName(locale = localeFor(name, requestLocale), name = name, isPrimary = true, source = SOURCE_USER))
        // saveAndFlush so a concurrent miss's dedup-key violation throws here (caller re-reads).
        val saved = teaRepository.saveAndFlush(tea)
        // Pin the issued numeric id to the stable public_id so a later DB rebuild can't orphan it (V7).
        legacyIdMapRepository.recordOnce(requireNotNull(saved.id), saved.publicId)
        return StubResult(requireNotNull(saved.id), created = true, state = STATE_PENDING)
    }

    @Transactional
    fun applyResult(teaId: Long, result: EnrichmentResult.Success) {
        val tea = teaRepository.findById(teaId).orElse(null) ?: return
        tea.type = result.type
        tea.flavors.clear()
        result.flavors.forEach { (dim, value) -> tea.addFlavor(TeaFlavor(dimension = dim, intensity = value.toShort())) }
        result.blurbRu?.let { blurb ->
            tea.descriptions.removeIf { it.locale == "ru" }
            tea.addDescription(TeaDescription(locale = "ru", shortText = blurb, source = SOURCE_AI))
        }
        addAlias(tea, "ru", result.names?.displayRu)
        addAlias(tea, "zh-Hans", result.names?.original?.takeIf { EnrichmentText.containsHan(it) })
        addAlias(tea, "pinyin", result.names?.pinyin)
        tea.enrichedBy = result.model
        tea.enrichedAt = Instant.now()
        tea.confidence = result.confidence
        tea.enrichmentState = STATE_DONE
        tea.enrichmentError = null
        tea.updatedAt = Instant.now()
    }

    @Transactional
    fun markFailed(teaId: Long, reason: String) {
        teaRepository.findById(teaId).orElse(null)?.let {
            it.enrichmentState = STATE_FAILED
            it.enrichmentError = reason.take(MAX_ERROR_LENGTH)
            it.updatedAt = Instant.now()
        }
    }

    @Transactional
    fun resetToPending(teaId: Long) {
        teaRepository.findById(teaId).orElse(null)?.let {
            it.enrichmentState = STATE_PENDING
            it.enrichmentError = null
            it.updatedAt = Instant.now()
        }
    }

    /** Adds an LLM-derived alias if non-blank and not already present (never primary; one primary/locale). */
    private fun addAlias(tea: Tea, locale: String, value: String?) {
        val name = value?.trim()
        if (name.isNullOrEmpty()) return
        if (tea.names.any { it.locale == locale && it.name == name }) return
        tea.addName(TeaName(locale = locale, name = name, isPrimary = false, source = SOURCE_AI))
    }

    /** Map the request/declared locale to a schema-allowed value, else detect from the script. */
    private fun localeFor(name: String, requestLocale: String?): String {
        requestLocale?.lowercase()?.let { req ->
            ALLOWED_NAME_LOCALES.firstOrNull { it.lowercase() == req }?.let { return it }
        }
        return when {
            EnrichmentText.containsHan(name) -> "zh-Hans"
            name.any { it in 'а'..'я' || it in 'А'..'Я' || it == 'ё' || it == 'Ё' } -> "ru"
            else -> "en"
        }
    }

    private companion object {
        const val SOURCE_AI = "ai"
        const val SOURCE_USER = "user"
        const val STATE_PENDING = "PENDING"
        const val STATE_DONE = "DONE"
        const val STATE_FAILED = "FAILED"
        const val MAX_ERROR_LENGTH = 200
        val ALLOWED_NAME_LOCALES = listOf("en", "ru", "zh-Hans", "pinyin")
    }
}
