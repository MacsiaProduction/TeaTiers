package com.macsia.teatiers.service

import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.macsia.teatiers.domain.SourceRecord
import com.macsia.teatiers.domain.SourceRecordRevision
import com.macsia.teatiers.domain.Tea
import com.macsia.teatiers.domain.TeaFieldProvenance
import com.macsia.teatiers.domain.TeaName
import com.macsia.teatiers.domain.TeaType
import com.macsia.teatiers.dto.ScrapedFacts
import com.macsia.teatiers.repository.ImportRunRepository
import com.macsia.teatiers.repository.SourceRecordRepository
import com.macsia.teatiers.repository.SourceSiteRepository
import com.macsia.teatiers.repository.TeaFieldProvenanceRepository
import com.macsia.teatiers.repository.TeaLegacyIdMapRepository
import com.macsia.teatiers.repository.TeaRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

/**
 * Writes the canonical catalog from an APPROVED match decision (decision #136 + #137-C6). This is the only
 * path that materializes scraped facts into a public tea, and it can NEVER mark a row verified. It always
 * works from the EXACT immutable revision the operator reviewed. Each field written records a value-bearing
 * CLAIM (the value + the source revision/decision/reviewer + whether it is the selected value); a conflict
 * (an existing value won) is kept as a non-selected claim rather than silently dropped. Approved names are
 * promoted to human-confirmed identity aliases so the human cross-script work is reused as Tier 0.
 */
@Service
class CanonicalUpsertService(
    private val teaRepository: TeaRepository,
    private val legacyIdMapRepository: TeaLegacyIdMapRepository,
    private val provenanceRepository: TeaFieldProvenanceRepository,
    private val identityAliasService: IdentityAliasService,
    private val sourceRecordRepository: SourceRecordRepository,
    private val sourceSiteRepository: SourceSiteRepository,
    private val importRunRepository: ImportRunRepository,
) {

    private val factsMapper = jacksonObjectMapper()
        .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)

    /** Create a brand-new canonical tea from an approved revision. Returns the new tea id. */
    @Transactional
    fun applyApprovedNew(sourceRecord: SourceRecord, revision: SourceRecordRevision, decisionId: Long?, reviewer: String): Long {
        requireApplyAllowed(revision)
        // A source record links to exactly one canonical tea; approving twice must never mint a duplicate.
        require(sourceRecord.teaId == null) {
            "source_record ${sourceRecord.id} is already linked to tea ${sourceRecord.teaId}"
        }
        val facts = parse(revision)
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
            // brand is NOT set from identity approval (decision #139-R4): a vendor/brand is observation-only
            // until an operator makes an explicit brand decision. It is recorded below as a proposal claim.
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

        val ctx = claimContext(sourceRecord, revision, decisionId, reviewer)
        // create-new: each non-null scalar fact is the SELECTED value for its field -- EXCEPT brand, which
        // is only ever a proposal (decision #139-R4: brand needs an explicit, separate field decision).
        if (facts.type != null) selectClaim(teaId, "type", type.name, ctx)
        if (facts.originCountry != null) selectClaim(teaId, "origin_country", facts.originCountry, ctx)
        if (facts.region != null) selectClaim(teaId, "region", facts.region, ctx)
        if (facts.cultivar != null) selectClaim(teaId, "cultivar", facts.cultivar, ctx)
        if (facts.brand != null) proposalClaim(teaId, "brand", facts.brand, ctx)
        oxidationValue(facts.oxidationMin?.toShort(), facts.oxidationMax?.toShort())
            ?.let { selectClaim(teaId, "oxidation", it, ctx) }
        writeNamesAndAliases(teaId, facts, ctx)

        link(sourceRecord, teaId)
        return teaId
    }

    /**
     * Merge an approved revision into an existing tea: fill only NULL scalar fields (never overwrite), add
     * names additively. A filled field gets a SELECTED claim; an incoming value that loses to an existing one
     * is recorded as a non-selected CONFLICT claim. The row's source becomes 'mixed'. Never sets 'verified'.
     */
    @Transactional
    fun applyApprovedMerge(
        sourceRecord: SourceRecord,
        revision: SourceRecordRevision,
        decisionId: Long?,
        reviewer: String,
        targetTeaId: Long,
    ): Long {
        requireApplyAllowed(revision)
        // Re-affirming the same link is fine; silently re-pointing a linked record elsewhere is not.
        require(sourceRecord.teaId == null || sourceRecord.teaId == targetTeaId) {
            "source_record ${sourceRecord.id} is already linked to tea ${sourceRecord.teaId}"
        }
        // Pessimistically lock the target (decision #139-R4): serialize concurrent applies to one tea so
        // the selected-claim swap (deselect-then-insert) and fill-null can't race.
        val tea = teaRepository.findByIdForUpdate(targetTeaId)
            ?: throw IllegalArgumentException("merge target tea $targetTeaId not found")
        // Never write into a tombstone (decision #137-C3 lifecycle): a 'retracted' or 'merged' target must
        // not be re-animated by a merge. The matcher can still propose a non-active target (FND-P1-1, P1) --
        // this is the apply-time backstop until the candidate queries themselves filter status.
        if (tea.status != "active") throw InactiveMergeTargetException(targetTeaId, tea.status)
        val facts = parse(revision)
        val ctx = claimContext(sourceRecord, revision, decisionId, reviewer)

        mergeScalar(targetTeaId, "origin_country", tea.originCountry, facts.originCountry, ctx) { tea.originCountry = it }
        mergeScalar(targetTeaId, "region", tea.region, facts.region, ctx) { tea.region = it }
        mergeScalar(targetTeaId, "cultivar", tea.cultivar, facts.cultivar, ctx) { tea.cultivar = it }
        mergeOxidation(targetTeaId, tea, facts, ctx)
        // type is identity (never changed by a merge), but corroboration/conflict is still recorded.
        facts.type?.let { recordCorroborationOrConflict(targetTeaId, "type", tea.type.name, teaType(it).name, ctx) }
        // brand is never auto-filled by a merge (decision #139-R4) -- only ever proposed for an explicit decision.
        facts.brand?.let { proposalClaim(targetTeaId, "brand", it, ctx) }

        // A tea assembled from more than one origin is 'mixed' (unless it was already scrape-only).
        if (tea.source != SOURCE_SCRAPE) tea.source = SOURCE_MIXED
        // Merge never touches verification_status -- a scrape can't promote a curated/verified row.

        facts.names.forEach { n ->
            if (tea.names.none { it.locale == n.locale && it.name == n.value }) {
                tea.addName(TeaName(locale = n.locale, name = n.value, isPrimary = false, source = SOURCE_SCRAPE))
            }
        }
        teaRepository.saveAndFlush(tea)

        writeNamesAndAliases(targetTeaId, facts, ctx)
        link(sourceRecord, targetTeaId)
        return targetTeaId
    }

    /**
     * Fill a null scalar field (SELECTED claim). If a value already exists, never overwrite -- but always
     * record the incoming value: a CORROBORATION claim when it agrees (an independent source confirms it,
     * decision #139-R4) or a CONFLICT claim when it differs (kept as evidence, not silently dropped).
     */
    private inline fun mergeScalar(
        teaId: Long,
        field: String,
        existing: String?,
        incoming: String?,
        ctx: ClaimContext,
        setter: (String) -> Unit,
    ) {
        if (incoming == null) return
        if (existing == null) {
            setter(incoming)
            selectClaim(teaId, field, incoming, ctx)
        } else {
            recordCorroborationOrConflict(teaId, field, existing, incoming, ctx)
        }
    }

    /** An incoming value for an already-set field: corroboration if it agrees, conflict if it differs. */
    private fun recordCorroborationOrConflict(teaId: Long, field: String, existing: String, incoming: String, ctx: ClaimContext) {
        if (existing == incoming) corroborationClaim(teaId, field, incoming, ctx) else conflictClaim(teaId, field, incoming, ctx)
    }

    /** Oxidation is a pair filled atomically (only when the combined bounds stay ordered, else kept). */
    private fun mergeOxidation(teaId: Long, tea: Tea, facts: ScrapedFacts, ctx: ClaimContext) {
        val incoming = oxidationValue(facts.oxidationMin?.toShort(), facts.oxidationMax?.toShort()) ?: return
        val mergedMin = tea.oxidationMin ?: facts.oxidationMin?.toShort()
        val mergedMax = tea.oxidationMax ?: facts.oxidationMax?.toShort()
        val ordered = mergedMin == null || mergedMax == null || mergedMin <= mergedMax
        val wouldChange = mergedMin != tea.oxidationMin || mergedMax != tea.oxidationMax
        if (ordered && wouldChange) {
            tea.oxidationMin = mergedMin
            tea.oxidationMax = mergedMax
            selectClaim(teaId, "oxidation", oxidationValue(mergedMin, mergedMax)!!, ctx)
        } else {
            // Existing bounds already cover it, or the combined bounds invert -> keep existing; record the
            // incoming value as corroboration (agrees) or conflict (differs).
            val existing = oxidationValue(tea.oxidationMin, tea.oxidationMax)
            if (existing != null) recordCorroborationOrConflict(teaId, "oxidation", existing, incoming, ctx)
        }
    }

    private fun writeNamesAndAliases(teaId: Long, facts: ScrapedFacts, ctx: ClaimContext) {
        facts.names.distinctBy { it.locale to it.value }.forEach { n ->
            // Name provenance is multi-valued (a tea has several aliases per locale) -- not single-selection.
            writeClaim(teaId, "name:${n.locale}", n.value, selected = true, replaceSelected = false, ctx)
            // The operator approved this identity decision -> promote the scraped name to a human-confirmed
            // alias so future runs reuse it as Tier 0 (decision #137-C6). Idempotent on (tea, locale, alias).
            identityAliasService.addAuthoritative(
                teaId = teaId,
                locale = n.locale,
                alias = n.value,
                romanizationSystem = if (n.locale == "pinyin") "pinyin" else null,
                origin = "human_confirmed",
                source = ctx.siteCode?.let { "scrape:$it" },
            )
        }
    }

    /** A selected scalar claim: at most one per (tea, field); deselect any prior selected one first. */
    private fun selectClaim(teaId: Long, field: String, value: String, ctx: ClaimContext) =
        writeClaim(teaId, field, value, selected = true, replaceSelected = true, ctx)

    /** A non-selected conflict claim: the incoming value lost to an existing one but is kept as evidence. */
    private fun conflictClaim(teaId: Long, field: String, value: String, ctx: ClaimContext) =
        writeClaim(teaId, field, value, selected = false, replaceSelected = false, ctx)

    /**
     * A non-selected corroboration claim: an independent source confirms the already-selected value
     * (decision #139-R4). Distinguishable from a conflict because its value equals the selected claim's.
     */
    private fun corroborationClaim(teaId: Long, field: String, value: String, ctx: ClaimContext) =
        writeClaim(teaId, field, value, selected = false, replaceSelected = false, ctx)

    /**
     * A non-selected proposal claim: a value (e.g. brand) that must NOT be auto-accepted from identity
     * approval (decision #139-R4) -- it awaits an explicit operator field decision before it can be selected.
     */
    private fun proposalClaim(teaId: Long, field: String, value: String, ctx: ClaimContext) =
        writeClaim(teaId, field, value, selected = false, replaceSelected = false, ctx)

    private fun writeClaim(
        teaId: Long,
        field: String,
        value: String,
        selected: Boolean,
        replaceSelected: Boolean,
        ctx: ClaimContext,
    ) {
        if (selected && replaceSelected) {
            // Move the current-selection pointer: flush the deselect BEFORE inserting so the partial-unique
            // (one selected per scalar field) never sees two selected rows mid-flush.
            val prior = provenanceRepository.findByTeaIdAndFieldName(teaId, field).filter { it.selected }
            if (prior.isNotEmpty()) {
                prior.forEach { it.selected = false }
                provenanceRepository.saveAll(prior)
                provenanceRepository.flush()
            }
        }
        provenanceRepository.save(
            TeaFieldProvenance(
                teaId = teaId,
                fieldName = field,
                claimedValue = value,
                selected = selected,
                sourceRecordId = ctx.recordId,
                sourceRecordRevisionId = ctx.revisionId,
                matchDecisionId = ctx.decisionId,
                reviewer = ctx.reviewer,
                sourceSiteId = ctx.siteId,
                sourceUrl = ctx.url,
                license = ctx.license,
            ),
        )
    }

    private fun claimContext(sourceRecord: SourceRecord, revision: SourceRecordRevision, decisionId: Long?, reviewer: String): ClaimContext {
        val site = sourceSiteRepository.findById(sourceRecord.sourceSiteId).orElse(null)
        return ClaimContext(
            recordId = sourceRecord.id,
            revisionId = revision.id,
            decisionId = decisionId,
            reviewer = reviewer,
            siteId = sourceRecord.sourceSiteId,
            url = sourceRecord.canonicalUrl,
            license = site?.licenseDefault,
            siteCode = site?.code,
        )
    }

    /**
     * The public catalog may be written ONLY from a real, non-dry, non-failed run (decision #137-C4):
     * a dry run may stage/match/render a patch but its records can never be approved into the catalog.
     */
    private fun requireApplyAllowed(revision: SourceRecordRevision) {
        val run = importRunRepository.findById(revision.importRunId).orElseThrow {
            CanonicalApplyForbiddenException("revision ${revision.id} references missing run ${revision.importRunId}")
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

    private fun parse(revision: SourceRecordRevision): ScrapedFacts =
        factsMapper.readValue(revision.rawFacts, ScrapedFacts::class.java)

    private fun teaType(value: String?): TeaType =
        value?.let { v -> TeaType.entries.firstOrNull { it.name == v.uppercase() } } ?: TeaType.OTHER

    private fun primaryName(facts: ScrapedFacts): String =
        facts.names.firstOrNull { it.locale == "en" && it.isPrimary }?.value
            ?: facts.names.firstOrNull { it.isPrimary }?.value
            ?: facts.names.first().value

    private fun oxidationValue(min: Short?, max: Short?): String? =
        if (min == null && max == null) null else "${min ?: ""}-${max ?: ""}"

    /** The shared facts about the approving decision, threaded onto every claim it produces. */
    private data class ClaimContext(
        val recordId: Long?,
        val revisionId: Long?,
        val decisionId: Long?,
        val reviewer: String,
        val siteId: Long,
        val url: String,
        val license: String?,
        val siteCode: String?,
    )

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

/** Approval tried to merge into a non-active (retracted/merged) tea (decision #137-C3) -- forbidden. */
class InactiveMergeTargetException(val targetTeaId: Long, val status: String) :
    RuntimeException("merge target tea $targetTeaId is '$status', not active; cannot write into a tombstone")
