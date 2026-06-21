package com.macsia.teatiers.service

import com.fasterxml.jackson.databind.MapperFeature
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.macsia.teatiers.domain.ImportRun
import com.macsia.teatiers.domain.NormalizedCandidate
import com.macsia.teatiers.domain.SourceRecord
import com.macsia.teatiers.dto.RobotsEvidence
import com.macsia.teatiers.dto.ScrapedFacts
import com.macsia.teatiers.dto.SourceObservation
import com.macsia.teatiers.repository.ImportRunRepository
import com.macsia.teatiers.repository.NormalizedCandidateRepository
import com.macsia.teatiers.repository.SourceRecordRepository
import com.macsia.teatiers.repository.SourceSiteRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.security.MessageDigest
import java.time.Instant

/**
 * Stages source observations into the ingest layer (decision #136). This NEVER creates a canonical tea:
 * it gates on the source's ToS/active state, enforces the facts-only boundary, idempotently upserts the
 * source_record keyed by (source, external_id/canonical_url), and (re)builds the normalized_candidate.
 * Matching + the human-approved canonical write are separate steps (IdentityMatchService / review).
 */
@Service
class CatalogImportService(
    private val sourceSiteRepository: SourceSiteRepository,
    private val importRunRepository: ImportRunRepository,
    private val sourceRecordRepository: SourceRecordRepository,
    private val normalizedCandidateRepository: NormalizedCandidateRepository,
    private val factsOnlyGuard: FactsOnlyGuard,
) {

    // Deterministic serialization so the same facts always hash the same (drives reparse detection).
    private val factsMapper = jacksonObjectMapper()
        .configure(MapperFeature.SORT_PROPERTIES_ALPHABETICALLY, true)

    /**
     * Open a run for a site, enforcing BOTH preflight gates (decision #137-C4): the ToS owner sign-off /
     * active gate AND a fresh per-run robots snapshot. Fails closed -- a run cannot start unless [robots]
     * carries an explicit 'allow' decision, so no run can ever ingest without proven robots evidence.
     */
    @Transactional
    fun startRun(
        sourceSiteCode: String,
        operator: String,
        toolVersion: String,
        parserVersion: String,
        robots: RobotsEvidence,
        dryRun: Boolean = true,
    ): ImportRun {
        val site = requireSite(sourceSiteCode)
        if (!site.importAllowed()) throw ImportGateException(site.code)
        if (robots.decision != ROBOTS_ALLOW) throw RobotsGateException(site.code, robots.decision)
        return importRunRepository.save(
            ImportRun(
                sourceSiteId = requireNotNull(site.id),
                operator = operator,
                toolVersion = toolVersion,
                parserVersion = parserVersion,
                dryRun = dryRun,
                status = STATUS_RUNNING,
                robotsFetchedAt = robots.fetchedAt,
                robotsHttpStatus = robots.httpStatus,
                robotsHash = robots.hash,
                robotsDecision = robots.decision,
            ),
        )
    }

    /**
     * Stage one observation. Idempotent: re-ingesting the same (site, external_id/canonical_url) updates
     * the existing source_record; if the facts hash changed it is re-queued (status='reparse_pending'),
     * otherwise it is a no-op beyond touching last_seen. Returns the (created or updated) source_record.
     */
    @Transactional
    fun ingest(importRunId: Long, obs: SourceObservation): SourceRecord {
        val site = requireSite(obs.sourceSiteCode)
        if (!site.importAllowed()) throw ImportGateException(site.code)
        // The run is a transaction invariant, not an audit field (decision #137-C4): load+lock it and prove
        // it is THIS site's run, still running, robots-allowed, and on the same parser before staging.
        val run = importRunRepository.findByIdForUpdate(importRunId)
            ?: throw RunStateException("import run $importRunId does not exist")
        if (run.status != STATUS_RUNNING) {
            throw RunStateException("import run $importRunId is '${run.status}', not running")
        }
        if (run.robotsDecision != ROBOTS_ALLOW) {
            throw RunStateException("import run $importRunId has no 'allow' robots decision")
        }
        if (run.sourceSiteId != requireNotNull(site.id)) {
            throw RunStateException("observation site '${site.code}' does not match run $importRunId site ${run.sourceSiteId}")
        }
        if (obs.parserVersion != run.parserVersion) {
            throw RunStateException("observation parser '${obs.parserVersion}' != run parser '${run.parserVersion}'")
        }
        factsOnlyGuard.validate(obs.facts)

        val factsJson = factsMapper.writeValueAsString(obs.facts)
        val hash = sha256(factsJson)
        val siteId = requireNotNull(site.id)

        val existing = obs.externalId?.let { sourceRecordRepository.findBySourceSiteIdAndExternalId(siteId, it) }
            ?: sourceRecordRepository.findBySourceSiteIdAndCanonicalUrl(siteId, obs.canonicalUrl)

        val record = if (existing == null) {
            sourceRecordRepository.save(
                SourceRecord(
                    sourceSiteId = siteId,
                    canonicalUrl = obs.canonicalUrl,
                    externalId = obs.externalId,
                    importRunId = importRunId,
                    contentHash = hash,
                    parserVersion = obs.parserVersion,
                    retrievedAt = obs.retrievedAt,
                    rawFacts = factsJson,
                    status = "parsed",
                ),
            )
        } else {
            existing.lastSeenAt = Instant.now()
            if (existing.contentHash != hash) {
                existing.contentHash = hash
                existing.rawFacts = factsJson
                existing.parserVersion = obs.parserVersion
                existing.retrievedAt = obs.retrievedAt
                existing.importRunId = importRunId
                // Facts changed -> re-queue for review even if previously linked, so the correction flows.
                existing.status = "reparse_pending"
            }
            sourceRecordRepository.save(existing)
        }

        rebuildCandidate(record, obs.facts)
        return record
    }

    /** Mark a run finished. Rejects an unknown terminal status and a missing run (decision #137-C4). */
    @Transactional
    fun finishRun(importRunId: Long, status: String) {
        if (status !in TERMINAL_STATUSES) throw RunStateException("unknown finish status '$status'")
        val run = importRunRepository.findById(importRunId).orElse(null)
            ?: throw RunStateException("import run $importRunId does not exist")
        run.status = status
        run.finishedAt = Instant.now()
        importRunRepository.save(run)
    }

    private fun rebuildCandidate(record: SourceRecord, facts: ScrapedFacts) {
        val recordId = requireNotNull(record.id)
        val candidate = normalizedCandidateRepository.findBySourceRecordId(recordId)
            ?: NormalizedCandidate(sourceRecordId = recordId)

        val ru = facts.names.firstOrNull { it.locale == "ru" }?.value
        val en = facts.names.firstOrNull { it.locale == "en" }?.value
        val zh = facts.names.firstOrNull { it.locale == "zh-Hans" }?.value
        val pinyin = facts.names.firstOrNull { it.locale == "pinyin" }?.value

        candidate.nameRu = ru
        candidate.nameEn = en
        candidate.nameZh = zh
        candidate.namePinyin = pinyin
        candidate.nameRuNorm = ru?.let { CrossScriptKeys.normalizeHint(it) }
        candidate.namePinyinNorm = pinyin?.let { CrossScriptKeys.normalizeHint(it) }
        // The scraper derives Hanzi->pinyin via pypinyin and supplies it as the pinyin name; the server
        // only bridges Cyrillic->pinyin via the curated Palladius table (pypinyin can't).
        candidate.pinyinFromHanzi = pinyin
        candidate.palladiusBridge = ru?.let { CrossScriptKeys.palladiusToPinyin(it) }
        candidate.type = facts.type
        candidate.originCountry = facts.originCountry
        candidate.region = facts.region
        candidate.cultivar = facts.cultivar
        candidate.brand = facts.brand
        candidate.vendor = facts.vendor
        normalizedCandidateRepository.save(candidate)
    }

    private fun requireSite(code: String) =
        sourceSiteRepository.findByCode(code) ?: throw UnknownSourceSiteException(code)

    private fun sha256(value: String): String =
        MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }

    companion object {
        const val ROBOTS_ALLOW = "allow"
        const val STATUS_RUNNING = "running"
        const val STATUS_BLOCKED = "blocked"
        const val STATUS_FAILED = "failed"

        /** A run may finish only into one of these terminal states (decision #137-C4). */
        val TERMINAL_STATUSES = setOf("succeeded", STATUS_FAILED, STATUS_BLOCKED)
    }
}

/** The source's ToS owner sign-off / active gate is not satisfied -- no run may start. */
class ImportGateException(val sourceCode: String) :
    RuntimeException("Source '$sourceCode' is not import-eligible (needs ToS sign-off + active)")

/** The per-run robots snapshot does not 'allow' (decision #137-C4) -- the run fails closed. */
class RobotsGateException(val sourceCode: String, val decision: String) :
    RuntimeException("Source '$sourceCode' run blocked: robots decision is '$decision', not 'allow'")

/** A run/observation invariant was violated (missing/finished/cross-site/parser-mismatch run, etc.). */
class RunStateException(message: String) : RuntimeException(message)

/** No source_site is registered under the given code. */
class UnknownSourceSiteException(val sourceCode: String) :
    RuntimeException("No source site '$sourceCode'")
