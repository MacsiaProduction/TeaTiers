package com.macsia.teatiers.service

import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.macsia.teatiers.domain.SourceRecord
import com.macsia.teatiers.domain.Tea
import com.macsia.teatiers.domain.TeaFieldProvenance
import com.macsia.teatiers.domain.TeaIdentityAlias
import com.macsia.teatiers.domain.TeaName
import com.macsia.teatiers.domain.TeaType
import com.macsia.teatiers.dto.ScrapedFacts
import com.macsia.teatiers.repository.ImportRunRepository
import com.macsia.teatiers.repository.SourceRecordRepository
import com.macsia.teatiers.repository.SourceSiteRepository
import com.macsia.teatiers.repository.TeaFieldProvenanceRepository
import com.macsia.teatiers.repository.TeaIdentityAliasRepository
import com.macsia.teatiers.repository.TeaLegacyIdMapRepository
import com.macsia.teatiers.repository.TeaRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

/**
 * Writes the canonical catalog from an APPROVED match decision (decision #136). This is the only path
 * that materializes scraped facts into a public tea, and it can NEVER mark a row verified: scraped data
 * is always 'unverified' (a human curation pass later promotes it). create-new inserts a fresh tea;
 * merge fills only NULL scalar fields and adds names additively (never overwrites a curated value). Every
 * field written gets a tea_field_provenance row; every name gets an (unverified, library_derived) alias.
 */
@Service
class CanonicalUpsertService(
    private val teaRepository: TeaRepository,
    private val legacyIdMapRepository: TeaLegacyIdMapRepository,
    private val provenanceRepository: TeaFieldProvenanceRepository,
    private val aliasRepository: TeaIdentityAliasRepository,
    private val sourceRecordRepository: SourceRecordRepository,
    private val sourceSiteRepository: SourceSiteRepository,
    private val importRunRepository: ImportRunRepository,
) {

    private val factsMapper = jacksonObjectMapper()
        .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)

    /** Create a brand-new canonical tea from an approved source record. Returns the new tea id. */
    @Transactional
    fun applyApprovedNew(sourceRecord: SourceRecord): Long {
        requireApplyAllowed(sourceRecord)
        // A source record links to exactly one canonical tea; approving twice must never mint a duplicate.
        require(sourceRecord.teaId == null) {
            "source_record ${sourceRecord.id} is already linked to tea ${sourceRecord.teaId}"
        }
        val facts = parse(sourceRecord)
        val type = teaType(facts.type)
        val primary = primaryName(facts)
        val pinyin = facts.names.firstOrNull { it.locale == "pinyin" }?.value
        val dedupKey = DedupKeys.of(primary, pinyin, type)

        // create_new only matches on tea_name; dedup_key (primary+pinyin+type) can still collide. Surface a
        // clear conflict so the operator merges, instead of aborting the tx on the unique index.
        teaRepository.findByDedupKey(dedupKey)?.let {
            throw CanonicalUpsertConflictException(dedupKey, requireNotNull(it.id))
        }

        val tea = Tea(
            type = type,
            source = SOURCE_SCRAPE,
            dedupKey = dedupKey,
            originCountry = facts.originCountry,
            region = facts.region,
            cultivar = facts.cultivar,
            oxidationMin = facts.oxidationMin?.toShort(),
            oxidationMax = facts.oxidationMax?.toShort(),
            brand = facts.brand,
            // A scrape can NEVER self-certify; reserved 'verified' is unreachable from this path.
            verificationStatus = STATUS_UNVERIFIED,
            status = "active",
        )
        // At most one primary per (locale) -- the schema's tea_name_primary_uk -- and no dup (locale,name).
        val primaryLocales = mutableSetOf<String>()
        facts.names.distinctBy { it.locale to it.value }.forEach { n ->
            val isPrimary = n.isPrimary && primaryLocales.add(n.locale)
            tea.addName(TeaName(locale = n.locale, name = n.value, isPrimary = isPrimary, source = SOURCE_SCRAPE))
        }
        val saved = teaRepository.saveAndFlush(tea)
        val teaId = requireNotNull(saved.id)
        legacyIdMapRepository.recordOnce(teaId, saved.publicId)

        writeProvenanceAndAliases(teaId, facts, sourceRecord, includeScalarFields = true)
        link(sourceRecord, teaId)
        return teaId
    }

    /**
     * Merge an approved source record into an existing tea: fill only NULL scalar fields (COALESCE),
     * add new names additively, never overwrite. The row's source becomes 'mixed' once it carries facts
     * from more than one origin. Never sets 'verified'.
     */
    @Transactional
    fun applyApprovedMerge(sourceRecord: SourceRecord, targetTeaId: Long): Long {
        requireApplyAllowed(sourceRecord)
        // Re-affirming the same link is fine; silently re-pointing a linked record elsewhere is not.
        require(sourceRecord.teaId == null || sourceRecord.teaId == targetTeaId) {
            "source_record ${sourceRecord.id} is already linked to tea ${sourceRecord.teaId}"
        }
        val tea = teaRepository.findById(targetTeaId).orElseThrow {
            IllegalArgumentException("merge target tea $targetTeaId not found")
        }
        val facts = parse(sourceRecord)

        if (tea.originCountry == null) tea.originCountry = facts.originCountry
        if (tea.region == null) tea.region = facts.region
        if (tea.cultivar == null) tea.cultivar = facts.cultivar
        if (tea.brand == null) tea.brand = facts.brand
        // Fill the oxidation pair atomically: only when the COMBINED bounds stay ordered. Two individually
        // valid sources (e.g. target min=10, scrape max=5) could otherwise invert min>max and trip the
        // tea_oxidation_range CHECK; on conflict keep the existing row's bounds rather than crash.
        val mergedOxidationMin = tea.oxidationMin ?: facts.oxidationMin?.toShort()
        val mergedOxidationMax = tea.oxidationMax ?: facts.oxidationMax?.toShort()
        if (mergedOxidationMin == null || mergedOxidationMax == null || mergedOxidationMin <= mergedOxidationMax) {
            tea.oxidationMin = mergedOxidationMin
            tea.oxidationMax = mergedOxidationMax
        }
        // A tea assembled from more than one origin is 'mixed' (unless it was already scrape-only).
        if (tea.source != SOURCE_SCRAPE) tea.source = SOURCE_MIXED
        // Merge never touches verification_status -- a scrape can't promote a curated/verified row.

        facts.names.forEach { n ->
            if (tea.names.none { it.locale == n.locale && it.name == n.value }) {
                tea.addName(TeaName(locale = n.locale, name = n.value, isPrimary = false, source = SOURCE_SCRAPE))
            }
        }
        teaRepository.saveAndFlush(tea)

        // On merge, scalar provenance is recorded only for fields this source actually filled; names always.
        writeProvenanceAndAliases(targetTeaId, facts, sourceRecord, includeScalarFields = false)
        link(sourceRecord, targetTeaId)
        return targetTeaId
    }

    private fun writeProvenanceAndAliases(
        teaId: Long,
        facts: ScrapedFacts,
        sourceRecord: SourceRecord,
        includeScalarFields: Boolean,
    ) {
        val siteId = sourceRecord.sourceSiteId
        val url = sourceRecord.canonicalUrl
        val license = sourceSiteRepository.findById(siteId).orElse(null)?.licenseDefault
        val siteCode = sourceSiteRepository.findById(siteId).orElse(null)?.code

        fun prov(field: String) = provenanceRepository.save(
            TeaFieldProvenance(
                teaId = teaId,
                fieldName = field,
                sourceRecordId = sourceRecord.id,
                sourceSiteId = siteId,
                sourceUrl = url,
                license = license,
            ),
        )

        if (includeScalarFields) {
            if (facts.type != null) prov("type")
            if (facts.originCountry != null) prov("origin_country")
            if (facts.region != null) prov("region")
            if (facts.cultivar != null) prov("cultivar")
            if (facts.brand != null) prov("brand")
            if (facts.oxidationMin != null || facts.oxidationMax != null) prov("oxidation")
        }
        facts.names.forEach { n ->
            prov("name:${n.locale}")
            // Scraped names enter as unverified, library-derived aliases (NOT authoritative for identity).
            if (aliasRepository.findByTeaId(teaId).none { it.locale == n.locale && it.alias == n.value }) {
                aliasRepository.save(
                    TeaIdentityAlias(
                        teaId = teaId,
                        locale = n.locale,
                        alias = n.value,
                        origin = "library_derived",
                        romanizationSystem = if (n.locale == "pinyin") "pinyin" else null,
                        verified = false,
                        source = siteCode?.let { "scrape:$it" },
                    ),
                )
            }
        }
    }

    /**
     * The public catalog may be written ONLY from a real, non-dry, non-failed run (decision #137-C4):
     * a dry run may stage/match/render a patch but its records can never be approved into the catalog.
     */
    private fun requireApplyAllowed(sourceRecord: SourceRecord) {
        val run = importRunRepository.findById(sourceRecord.importRunId).orElseThrow {
            CanonicalApplyForbiddenException("source_record ${sourceRecord.id} references missing run ${sourceRecord.importRunId}")
        }
        if (run.dryRun) {
            throw CanonicalApplyForbiddenException("run ${run.id} is a dry run; its records cannot be applied to the catalog")
        }
        if (run.status == "blocked" || run.status == "failed") {
            throw CanonicalApplyForbiddenException("run ${run.id} is '${run.status}'; its records cannot be applied")
        }
    }

    private fun link(sourceRecord: SourceRecord, teaId: Long) {
        sourceRecord.teaId = teaId
        sourceRecord.status = "linked"
        sourceRecordRepository.save(sourceRecord)
    }

    private fun parse(sourceRecord: SourceRecord): ScrapedFacts =
        factsMapper.readValue(sourceRecord.rawFacts, ScrapedFacts::class.java)

    private fun teaType(value: String?): TeaType =
        value?.let { v -> TeaType.entries.firstOrNull { it.name == v.uppercase() } } ?: TeaType.OTHER

    private fun primaryName(facts: ScrapedFacts): String =
        facts.names.firstOrNull { it.locale == "en" && it.isPrimary }?.value
            ?: facts.names.firstOrNull { it.isPrimary }?.value
            ?: facts.names.first().value

    private companion object {
        const val SOURCE_SCRAPE = "scrape"
        const val SOURCE_MIXED = "mixed"
        const val STATUS_UNVERIFIED = "unverified"
    }
}

/**
 * An approved create-new collided with an existing tea on `dedup_key` -- it is really the same canonical
 * tea. The operator should merge into [existingTeaId] instead of creating a duplicate.
 */
class CanonicalUpsertConflictException(val dedupKey: String, val existingTeaId: Long) :
    RuntimeException("create_new collides with existing tea $existingTeaId on dedup_key '$dedupKey'; merge instead")

/** Approval tried to write the catalog from a dry/blocked/failed run (decision #137-C4) -- forbidden. */
class CanonicalApplyForbiddenException(message: String) : RuntimeException(message)
