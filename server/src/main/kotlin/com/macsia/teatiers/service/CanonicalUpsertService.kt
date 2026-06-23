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
import com.macsia.teatiers.repository.RawEvidenceRepository
import com.macsia.teatiers.repository.SourceRecordRepository
import com.macsia.teatiers.repository.SourceSiteRepository
import com.macsia.teatiers.repository.TeaFieldProvenanceRepository
import com.macsia.teatiers.repository.TeaLegacyIdMapRepository
import com.macsia.teatiers.repository.TeaRepository
import org.springframework.dao.DataIntegrityViolationException
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
    private val rawEvidenceRepository: RawEvidenceRepository,
    private val factsOnlyGuard: FactsOnlyGuard,
    private val factsValidator: FactsValidator,
) {

    // Strict at apply (decision #141, PR-3): a revision whose stored facts carry an unknown field fails
    // closed rather than silently dropping it.
    private val factsMapper = jacksonObjectMapper()
        .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, true)

    /** Create a brand-new canonical tea from an approved revision. Returns the new tea id. */
    @Transactional
    fun applyApprovedNew(
        sourceRecord: SourceRecord,
        revision: SourceRecordRevision,
        decisionId: Long?,
        reviewer: String,
        applyingRunId: Long,
    ): Long {
        requireApplyAllowed(applyingRunId, sourceRecord, revision)
        // A source record links to exactly one canonical tea; approving twice must never mint a duplicate.
        require(sourceRecord.teaId == null) {
            "source_record ${sourceRecord.id} is already linked to tea ${sourceRecord.teaId}"
        }
        val facts = parse(revision)
        val type = teaType(facts.type)
        val primary = primaryName(facts)
        val pinyin = facts.names.firstOrNull { it.locale == "pinyin" }?.value
        val dedupKey = DedupKeys.of(primary, pinyin, type)

        // create_new only matches on tea_name; dedup_key (primary+pinyin+type) can still collide with an
        // ACTIVE tea -> surface a clear conflict so the operator merges, instead of aborting the tx on the
        // unique index. Active-scoped (H2): a retracted/merged tea with the same dedup_key must NOT block this
        // (the matcher is active-only, so it wouldn't have proposed merging into it -- that would deadlock).
        teaRepository.findActiveByDedupKey(dedupKey)?.let {
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
        // The findActiveByDedupKey check above is racy: a concurrent apply of the SAME dedup_key can commit
        // between it and this flush, so the active-scoped partial-unique can still fire (SRV-P1-3). Surface
        // the domain conflict instead of a raw DataIntegrityViolationException. The flush has already doomed
        // this tx, so the run aborts and leaves 'reviewed' to retry; on retry the now-committed tea is visible
        // to applyRun's pre-check and the decision is quarantined cleanly.
        val saved = try {
            teaRepository.saveAndFlush(tea)
        } catch (e: DataIntegrityViolationException) {
            throw CanonicalUpsertConflictException(dedupKey, existingTeaId = null, cause = e)
        }
        val teaId = requireNotNull(saved.id)
        legacyIdMapRepository.recordOnce(teaId, saved.publicId)

        val ctx = claimContext(sourceRecord, revision, decisionId, reviewer)
        // create-new: each non-null scalar fact is the SELECTED value for its field -- EXCEPT brand, which
        // is only ever a proposal (decision #139-R4: brand needs an explicit, separate field decision).
        if (facts.type != null) selectClaim(teaId, "type", type.name, ctx)
        if (facts.originCountry != null) selectClaim(teaId, "origin_country", facts.originCountry, ctx)
        if (facts.region != null) selectClaim(teaId, "region", facts.region, ctx)
        if (facts.cultivar != null) selectClaim(teaId, "cultivar", facts.cultivar, ctx)
        if (facts.brand != null) nonSelectedClaim(teaId, "brand", facts.brand, ctx)
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
        applyingRunId: Long,
    ): Long {
        requireApplyAllowed(applyingRunId, sourceRecord, revision)
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
        facts.type?.let { recordCorroborationOrConflict(targetTeaId, "type", teaType(it).name, ctx) }
        // brand is never auto-filled by a merge (decision #139-R4) -- only ever proposed for an explicit decision.
        facts.brand?.let { nonSelectedClaim(targetTeaId, "brand", it, ctx) }

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
            recordCorroborationOrConflict(teaId, field, incoming, ctx)
        }
    }

    /**
     * An incoming value for an already-set field: kept as a non-selected claim either way -- a corroboration
     * if it agrees with the selected value, a conflict if it differs (both persist identically; the value
     * itself distinguishes them).
     */
    private fun recordCorroborationOrConflict(teaId: Long, field: String, incoming: String, ctx: ClaimContext) =
        nonSelectedClaim(teaId, field, incoming, ctx)

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
            if (existing != null) recordCorroborationOrConflict(teaId, "oxidation", incoming, ctx)
        }
    }

    /**
     * Apply-time collision pre-check (H3, decision #141 review): would materializing this approved decision
     * collide with an active identity the operator must resolve by MERGING -- a create_new dedup_key already
     * held, or a name already owned as an authoritative alias by another active tea? Returns a human reason if
     * so, else null. [ReviewService.applyRun] calls this BEFORE the write and quarantines + reports a non-null
     * result (skipping the write entirely), so one collision no longer rolls back the whole run -- and because
     * nothing is thrown across a @Transactional boundary, the shared apply tx is never marked rollback-only.
     * Runs inside that apply tx, so it also sees identities written by EARLIER decisions in the same batch.
     * The write methods keep their own guards as the backstop for the narrow concurrent-cross-run race this
     * pre-check can't see (which DOES throw -- correctly aborting the run, the prior all-or-nothing behavior).
     * [targetTeaId] is the merge target to exclude from the alias check (null for create_new).
     */
    fun applyCollisionReason(revision: SourceRecordRevision, kind: String, targetTeaId: Long?): String? {
        val facts = parse(revision)
        if (kind == "approved_new") {
            val pinyin = facts.names.firstOrNull { it.locale == "pinyin" }?.value
            val dedupKey = DedupKeys.of(primaryName(facts), pinyin, teaType(facts.type))
            teaRepository.findActiveByDedupKey(dedupKey)?.let {
                return "create_new collides with active tea ${it.id} on dedup_key '$dedupKey'; merge instead"
            }
        }
        facts.names.distinctBy { it.locale to it.value }.forEach { n ->
            identityAliasService.conflictingOwner(n.locale, n.value, targetTeaId)?.let { owner ->
                return "name '${n.value}' (${n.locale}) already belongs to active tea $owner; merge the identities"
            }
        }
        return null
    }

    private fun writeNamesAndAliases(teaId: Long, facts: ScrapedFacts, ctx: ClaimContext) {
        facts.names.distinctBy { it.locale to it.value }.forEach { n ->
            // Name provenance is multi-valued (a tea has several aliases per locale) -- not single-selection.
            writeClaim(teaId, "name:${n.locale}", n.value, selected = true, replaceSelected = false, ctx)
            // The operator approved this identity decision -> promote the scraped name to a human-confirmed
            // alias so future runs reuse it as Tier 0 (decision #137-C6). Idempotent on (tea, locale, alias).
            // Fail-closed (decision #141 / FND-P1-1): a name colliding with a DIFFERENT active tea's
            // authoritative alias throws DuplicateAuthoritativeAliasException (a duplicate identity is never
            // minted). applyRun's applyCollisionReason pre-check normally quarantines such a decision BEFORE
            // this write (H3); reaching here means a concurrent cross-run race the pre-check missed -- it still
            // throws and rolls the whole run back (the operator merges the identities and re-applies).
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

    /**
     * A non-selected claim: kept as evidence but never the current value. Covers all three non-selected
     * cases -- a CONFLICT (incoming value lost to an existing one), a CORROBORATION (an independent source
     * confirms the selected value; distinguishable because its value equals the selected claim's), and a
     * PROPOSAL (e.g. brand, which must NOT be auto-accepted from identity approval and awaits an explicit
     * operator field decision; decision #139-R4). All three persist identically.
     */
    private fun nonSelectedClaim(teaId: Long, field: String, value: String, ctx: ClaimContext) =
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
        // The FK guarantees the site exists; assert it rather than silently dropping CC-license + scrape:<site>
        // provenance to null on an "impossible" miss (SRV-P2-6).
        val site = sourceSiteRepository.findById(sourceRecord.sourceSiteId).orElseThrow {
            IllegalStateException("source_site ${sourceRecord.sourceSiteId} missing for source_record ${sourceRecord.id}")
        }
        return ClaimContext(
            recordId = sourceRecord.id,
            revisionId = revision.id,
            decisionId = decisionId,
            reviewer = reviewer,
            siteId = sourceRecord.sourceSiteId,
            url = sourceRecord.canonicalUrl,
            license = site.licenseDefault,
            siteCode = site.code,
        )
    }

    /**
     * The public catalog may be written ONLY from a real, non-dry run that is apply-authorized (decision
     * #137-C4 / refresh ING-P0-1): the APPLYING run (the one materializing this decision) must be in
     * 'reviewed' or 'applying' -- a state reached only after ingestion was sealed and every decision resolved.
     * Gating on the applying run (not [SourceRecordRevision.importRunId]) is deliberate: a revision can legitimately
     * be reviewed+applied in a LATER run than the one that produced it (e.g. a revert to an older revision
     * whose original run is already terminal). The evidence-chain check still binds to the revision's own run.
     *
     * A dry run is a rehearsal whose observations may NEVER reach the catalog: so BOTH the applying run AND
     * the revision's PRODUCING run must be non-dry -- otherwise a dry-staged revision could be laundered into
     * the catalog by re-proposing its decision into a later real run. The producing run must also not be
     * BLOCKED (H1, decision #141 review): blockRun is the compliance abort (a robots/ToS/host/SSRF gate
     * tripped), so its staged facts are tainted and must never publish, even via re-proposal into a clean run.
     * (FAILED is operational -- a runner/operator error -- and stays applyable so deferred review still works.)
     *
     * The applying run must also belong to the SAME source_site as the record it applies. A decision's
     * import_run_id can be re-pointed by the matching pipeline with no site check, so without this a record
     * scraped under site X could be applied via a run for site Y -- provenance confusion across sites.
     */
    private fun requireApplyAllowed(applyingRunId: Long, sourceRecord: SourceRecord, revision: SourceRecordRevision) {
        val run = importRunRepository.findById(applyingRunId).orElseThrow {
            CanonicalApplyForbiddenException("apply run $applyingRunId not found")
        }
        if (run.sourceSiteId != sourceRecord.sourceSiteId) {
            throw CanonicalApplyForbiddenException(
                "apply run ${run.id} is for site ${run.sourceSiteId}, but source_record ${sourceRecord.id} is from site ${sourceRecord.sourceSiteId}",
            )
        }
        if (run.dryRun) {
            throw CanonicalApplyForbiddenException("run ${run.id} is a dry run; its records cannot be applied to the catalog")
        }
        val state = ImportRunState.of(run.status)
        if (state != ImportRunState.REVIEWED && state != ImportRunState.APPLYING) {
            throw CanonicalApplyForbiddenException(
                "run ${run.id} is '${run.status}', not apply-authorized (must be reviewed/applying)",
            )
        }
        val producingRun = importRunRepository.findById(revision.importRunId).orElseThrow {
            CanonicalApplyForbiddenException("revision ${revision.id} references missing run ${revision.importRunId}")
        }
        if (producingRun.dryRun) {
            throw CanonicalApplyForbiddenException(
                "revision ${revision.id} was produced by dry run ${producingRun.id}; dry-run observations can never be applied",
            )
        }
        if (ImportRunState.of(producingRun.status) == ImportRunState.BLOCKED) {
            throw CanonicalApplyForbiddenException(
                "revision ${revision.id} was produced by BLOCKED run ${producingRun.id} (a compliance abort); " +
                    "its facts are tainted and can never be applied",
            )
        }
        requireEvidenceChain(revision)
    }

    /**
     * Fail closed (decision #141, PR-2) if the revision's immutable fetch-evidence chain is absent or
     * inconsistent: the bound RawEvidence must exist, belong to the SAME run that produced the revision, and
     * carry a non-blank body hash. Proves the published facts trace back to a real recorded fetch.
     */
    private fun requireEvidenceChain(revision: SourceRecordRevision) {
        val evidence = rawEvidenceRepository.findById(revision.rawEvidenceId).orElseThrow {
            CanonicalApplyForbiddenException(
                "revision ${revision.id} has no raw evidence (${revision.rawEvidenceId}); the fetch chain is absent",
            )
        }
        if (evidence.importRunId != revision.importRunId) {
            throw CanonicalApplyForbiddenException(
                "revision ${revision.id} evidence run ${evidence.importRunId} != revision run ${revision.importRunId}",
            )
        }
        if (evidence.contentHash.isBlank()) {
            throw CanonicalApplyForbiddenException("revision ${revision.id} evidence has a blank body hash")
        }
    }

    private fun link(sourceRecord: SourceRecord, teaId: Long) {
        sourceRecord.teaId = teaId
        sourceRecord.status = "linked"
        sourceRecordRepository.save(sourceRecord)
    }

    private fun parse(revision: SourceRecordRevision): ScrapedFacts {
        val facts = factsMapper.readValue(revision.rawFacts, ScrapedFacts::class.java)
        // Re-run the facts boundary + semantic validation at the WRITE boundary against the exact reviewed
        // revision (decision #141, PR-3): a revision can never publish content that fails the ingest gates.
        factsOnlyGuard.validate(facts)
        factsValidator.validate(facts)
        return facts
    }

    /** null type -> OTHER (unspecified); a non-null UNKNOWN type fails closed rather than coercing to OTHER. */
    private fun teaType(value: String?): TeaType {
        if (value == null) return TeaType.OTHER
        return TeaType.entries.firstOrNull { it.name == value.uppercase() }
            ?: throw FactsValidationException("unknown tea type '$value'")
    }

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
 * tea. The operator should merge into [existingTeaId] instead of creating a duplicate. [existingTeaId] is
 * null when the collision surfaced from a concurrent insert (the unique constraint, SRV-P1-3) rather than
 * the pre-check, so the colliding id isn't known (the tx is already doomed and can't be re-queried).
 */
class CanonicalUpsertConflictException(val dedupKey: String, val existingTeaId: Long?, cause: Throwable? = null) :
    RuntimeException(
        "create_new collides with ${existingTeaId?.let { "existing tea $it" } ?: "a concurrently-created tea"} " +
            "on dedup_key '$dedupKey'; merge instead",
        cause,
    )

/** Approval tried to write the catalog from a dry/blocked/failed run (decision #137-C4) -- forbidden. */
class CanonicalApplyForbiddenException(message: String) : RuntimeException(message)

/** Approval tried to merge into a non-active (retracted/merged) tea (decision #137-C3) -- forbidden. */
class InactiveMergeTargetException(val targetTeaId: Long, val status: String) :
    RuntimeException("merge target tea $targetTeaId is '$status', not active; cannot write into a tombstone")
