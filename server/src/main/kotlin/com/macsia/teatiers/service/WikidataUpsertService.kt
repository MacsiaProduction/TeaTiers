package com.macsia.teatiers.service

import com.macsia.teatiers.client.WikidataTea
import com.macsia.teatiers.domain.Tea
import com.macsia.teatiers.domain.TeaDescription
import com.macsia.teatiers.domain.TeaName
import com.macsia.teatiers.repository.TeaLegacyIdMapRepository
import com.macsia.teatiers.repository.TeaRepository
import java.time.Instant
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

/**
 * Persists Wikidata matches as catalog rows for the `/resolve` flow (plan.md section 6). Kept in its
 * own bean so the insert runs in a dedicated transaction: a lost insert race surfaces as a unique
 * violation on `dedup_key`/`wikidata_qid` (V1 schema), which the caller recovers from by re-reading.
 */
@Service
class WikidataUpsertService(
    private val teaRepository: TeaRepository,
    private val legacyIdMapRepository: TeaLegacyIdMapRepository,
) {

    data class CreateResult(val id: Long, val created: Boolean)

    /** Returns the existing row if this tea is already known, otherwise inserts a new unverified row. */
    @Transactional
    fun createOrGet(match: WikidataTea): CreateResult {
        teaRepository.findByWikidataQid(match.qid)?.let { return CreateResult(requireNotNull(it.id), false) }
        val dedupKey = dedupKeyFor(match)
        teaRepository.findByDedupKey(dedupKey)?.let { return CreateResult(requireNotNull(it.id), false) }

        // saveAndFlush so a concurrent insert's unique violation throws here (inside this tx) rather
        // than at an outer commit, letting the caller fall back to a read.
        val saved = teaRepository.saveAndFlush(buildTea(match, dedupKey))
        // Pin the issued numeric id to the stable public_id so a later DB rebuild can't orphan it (V7).
        legacyIdMapRepository.recordOnce(requireNotNull(saved.id), saved.publicId)
        return CreateResult(requireNotNull(saved.id), true)
    }

    /** Re-read after a lost insert race; null only if the row vanished between the conflict and now. */
    @Transactional(readOnly = true)
    fun findExisting(match: WikidataTea): Long? =
        teaRepository.findByWikidataQid(match.qid)?.id ?: teaRepository.findByDedupKey(dedupKeyFor(match))?.id

    private fun dedupKeyFor(match: WikidataTea): String =
        DedupKeys.of(primaryName(match), pinyin = null, type = match.type)

    /** Primary name follows the seed convention: prefer en, then ru, then zh-Hans. */
    private fun primaryName(match: WikidataTea): String =
        match.nameEn ?: match.nameRu ?: match.nameZhHans
            ?: error("Wikidata match ${match.qid} has no usable label")

    private fun buildTea(match: WikidataTea, dedupKey: String): Tea {
        val now = Instant.now()
        val tea = Tea(
            type = match.type,
            source = SOURCE,
            dedupKey = dedupKey,
            wikidataQid = match.qid,
            originCountry = match.originCountry,
            sourceUrl = "https://www.wikidata.org/wiki/${match.qid}",
            license = LICENSE,
            retrievedAt = now,
            // High confidence, but still 'unverified': the schema reserves 'verified' for a human
            // curation pass (V1 schema comment), so auto-imports never claim it.
            verificationStatus = "unverified",
            confidence = WIKIDATA_CONFIDENCE,
            enrichedBy = SOURCE,
            enrichedAt = now,
        )
        addName(tea, "en", match.nameEn)
        addName(tea, "ru", match.nameRu)
        addName(tea, "zh-Hans", match.nameZhHans)
        match.descriptionEn?.let {
            tea.addDescription(TeaDescription(locale = "en", shortText = it, fullText = null, source = SOURCE, license = LICENSE))
        }
        return tea
    }

    private fun addName(tea: Tea, locale: String, value: String?) {
        if (value.isNullOrBlank()) return
        // One label per locale, so each is that locale's primary (schema allows one primary/locale).
        tea.addName(TeaName(locale = locale, name = value, isPrimary = true, source = SOURCE).also { it.license = LICENSE })
    }

    private companion object {
        const val SOURCE = "wikidata"
        const val LICENSE = "CC0-1.0"
        const val WIKIDATA_CONFIDENCE = 0.9f
    }
}
