package com.macsia.teatiers.service

import com.fasterxml.jackson.databind.MapperFeature
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.macsia.teatiers.domain.ImportRun
import com.macsia.teatiers.domain.NormalizedCandidate
import com.macsia.teatiers.domain.RawEvidence
import com.macsia.teatiers.domain.SourceRecord
import com.macsia.teatiers.domain.SourceRecordRevision
import com.macsia.teatiers.domain.SourceRecordUrlHistory
import com.macsia.teatiers.dto.FetchEvidence
import com.macsia.teatiers.dto.RobotsEvidence
import com.macsia.teatiers.dto.ScrapedFacts
import com.macsia.teatiers.dto.SourceObservation
import com.macsia.teatiers.repository.ImportRunRepository
import com.macsia.teatiers.repository.NormalizedCandidateRepository
import com.macsia.teatiers.repository.RawEvidenceRepository
import com.macsia.teatiers.repository.SourceRecordRepository
import com.macsia.teatiers.repository.SourceRecordRevisionRepository
import com.macsia.teatiers.repository.SourceRecordUrlHistoryRepository
import com.macsia.teatiers.repository.SourceSiteRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.security.MessageDigest
import java.time.Duration
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
    private val revisionRepository: SourceRecordRevisionRepository,
    private val urlHistoryRepository: SourceRecordUrlHistoryRepository,
    private val normalizedCandidateRepository: NormalizedCandidateRepository,
    private val rawEvidenceRepository: RawEvidenceRepository,
    private val factsOnlyGuard: FactsOnlyGuard,
    private val urlSafety: UrlSafety,
    private val importRunStateMachine: ImportRunStateMachine,
) {

    // Deterministic serialization so the same facts always hash the same (drives reparse detection).
    private val factsMapper = jacksonObjectMapper()
        .configure(MapperFeature.SORT_PROPERTIES_ALPHABETICALLY, true)

    /**
     * Open a run for a site, enforcing the preflight gates (decision #137-C4 + #139-R3): the ToS owner
     * sign-off / active gate, a COMPLETE + FRESH 'allow' robots snapshot, and at most one active run per
     * source. Fails closed -- no run can ever start (hence ingest) without proven, current robots evidence.
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
        validateRobots(site.code, robots)
        // The robots URL is a fetch URL too -- it must be on the site allowlist and SSRF-safe (decision #141).
        urlSafety.validate(robots.robotsUrl, site.allowedHosts.toSet())
        val siteId = requireNotNull(site.id)
        // One active run per source (decision #137-C4): a friendly pre-check before the DB partial-unique.
        if (importRunRepository.existsBySourceSiteIdAndStatusIn(siteId, ImportRunState.ACTIVE_CODES)) {
            throw RunStateException("source '${site.code}' already has an active run; finish it before starting another")
        }
        // Preflight (ToS + robots + host) passed synchronously above -> persist directly at preflight_allowed
        // (decision #137-C4). The 'created' state is reserved for a future async preflight.
        return importRunRepository.save(
            ImportRun(
                sourceSiteId = siteId,
                operator = operator,
                toolVersion = toolVersion,
                parserVersion = parserVersion,
                dryRun = dryRun,
                status = ImportRunState.PREFLIGHT_ALLOWED.code,
                robotsFetchedAt = robots.fetchedAt,
                robotsHttpStatus = robots.httpStatus,
                robotsHash = robots.hash,
                robotsDecision = robots.decision,
                robotsUrl = robots.robotsUrl,
                robotsUserAgent = robots.userAgent,
            ),
        )
    }

    /** A run is ingestible only with a fresh, complete 'allow' snapshot (decision #139-R3); else fail closed. */
    private fun validateRobots(siteCode: String, robots: RobotsEvidence) {
        if (robots.decision != ROBOTS_ALLOW) throw RobotsGateException(siteCode, robots.decision)
        val complete = robots.robotsUrl.isNotBlank() && robots.userAgent.isNotBlank() &&
            (robots.httpStatus ?: 0) in 200..299 && !robots.hash.isNullOrBlank()
        if (!complete) throw RobotsGateException(siteCode, "incomplete (need robotsUrl, userAgent, 2xx status, body hash)")
        val age = Duration.between(robots.fetchedAt, Instant.now())
        if (age > ROBOTS_MAX_AGE || age < ROBOTS_FUTURE_SKEW.negated()) {
            throw RobotsGateException(siteCode, "stale robots snapshot (fetchedAt ${robots.fetchedAt})")
        }
    }

    /**
     * Stage one observation. Identity is reconciled across slug renames / newly-discovered external ids
     * (decision #137-C5/SCR-P0-5). Each distinct facts hash becomes an immutable revision (#137-C5): a new
     * revision re-queues review (status='reparse_pending') so a correction flows even after an earlier
     * approval; identical facts are a no-op beyond touching last_seen. Returns the source_record.
     */
    @Transactional
    fun ingest(importRunId: Long, obs: SourceObservation): SourceRecord {
        val site = requireSite(obs.sourceSiteCode)
        if (!site.importAllowed()) throw ImportGateException(site.code)
        // The run is a transaction invariant, not an audit field (decision #137-C4): load+lock it and prove
        // it is THIS site's run, still accepting observations, robots-allowed, and on the same parser.
        val run = importRunRepository.findByIdForUpdate(importRunId)
            ?: throw RunStateException("import run $importRunId does not exist")
        val state = ImportRunState.of(run.status)
        if (state != ImportRunState.PREFLIGHT_ALLOWED && state != ImportRunState.INGESTING) {
            throw RunStateException(
                "import run $importRunId is '${run.status}', not accepting observations (must be preflight_allowed/ingesting)",
            )
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
        // SSRF / host-allowlist gate on the observed URL + a real (2xx, hashed) fetch envelope, BEFORE any
        // write (decision #141, PR-2): a bad host/scheme/IP or a missing fetch proof is rejected up front.
        urlSafety.validate(obs.canonicalUrl, site.allowedHosts.toSet())
        validateFetchEvidence(obs.evidence)
        // First observation moves the run preflight_allowed -> ingesting (decision #137-C4); the row is
        // already write-locked above, so transition it in place without re-locking.
        if (state == ImportRunState.PREFLIGHT_ALLOWED) importRunStateMachine.transition(run, ImportRunState.INGESTING)
        factsOnlyGuard.validate(obs.facts)

        val factsJson = factsMapper.writeValueAsString(obs.facts)
        val hash = sha256(factsJson)
        val siteId = requireNotNull(site.id)

        val record = sourceRecordRepository.saveAndFlush(reconcileSourceRecord(siteId, obs, importRunId, factsJson, hash))
        linkCurrentRevision(record, obs, factsJson, hash, importRunId)
        rebuildCandidate(record, obs.facts)
        return record
    }

    /**
     * Resolve the stable source_record for this observation, reconciling identity (decision #137-C5/
     * SCR-P0-5): a known external id at a new URL is a slug rename (URL updated, the old one archived); a
     * known URL with a newly-discovered external id attaches that id; an external id / URL that points at a
     * DIFFERENT record is an identity collision surfaced for the operator, never silently overwritten.
     */
    private fun reconcileSourceRecord(
        siteId: Long,
        obs: SourceObservation,
        importRunId: Long,
        factsJson: String,
        hash: String,
    ): SourceRecord {
        val byExt = obs.externalId?.let { sourceRecordRepository.findBySourceSiteIdAndExternalId(siteId, it) }
        val byUrl = sourceRecordRepository.findBySourceSiteIdAndCanonicalUrl(siteId, obs.canonicalUrl)

        return when {
            byExt != null -> {
                byExt.lastSeenAt = Instant.now()
                if (byExt.canonicalUrl != obs.canonicalUrl) {
                    if (byUrl != null && byUrl.id != byExt.id) {
                        throw SourceIdentityConflictException(
                            "external id '${obs.externalId}' -> record ${byExt.id}, but url '${obs.canonicalUrl}' " +
                                "already -> record ${byUrl.id}",
                        )
                    }
                    urlHistoryRepository.save(
                        SourceRecordUrlHistory(sourceRecordId = requireNotNull(byExt.id), canonicalUrl = byExt.canonicalUrl),
                    )
                    byExt.canonicalUrl = obs.canonicalUrl
                }
                byExt
            }

            byUrl != null -> {
                byUrl.lastSeenAt = Instant.now()
                obs.externalId?.let { ext ->
                    when (byUrl.externalId) {
                        null -> byUrl.externalId = ext // newly-discovered stable id; byExt was null -> no collision
                        ext -> Unit
                        else -> throw SourceIdentityConflictException(
                            "url '${obs.canonicalUrl}' -> record ${byUrl.id} already has external id " +
                                "'${byUrl.externalId}', not '$ext'",
                        )
                    }
                }
                byUrl
            }

            else -> SourceRecord(
                sourceSiteId = siteId,
                canonicalUrl = obs.canonicalUrl,
                externalId = obs.externalId,
                importRunId = importRunId,
                contentHash = hash,
                parserVersion = obs.parserVersion,
                retrievedAt = obs.retrievedAt,
                rawFacts = factsJson,
                status = "parsed",
            )
        }
    }

    /**
     * Find-or-create the immutable revision for these facts and make it current (decision #137-C5).
     * Identical facts already current = no-op. A new (or reverted-to) revision re-queues review and
     * denormalizes onto the record. The very first revision keeps 'parsed'; any later change is a
     * 'reparse_pending' correction that re-enters the review queue.
     */
    private fun linkCurrentRevision(
        record: SourceRecord,
        obs: SourceObservation,
        factsJson: String,
        hash: String,
        importRunId: Long,
    ) {
        val recordId = requireNotNull(record.id)
        val existingRev = revisionRepository.findBySourceRecordIdAndContentHash(recordId, hash)
        if (existingRev != null && record.currentRevisionId == existingRev.id) return // unchanged re-import

        val wasFirst = record.currentRevisionId == null
        // A brand-new revision records its immutable fetch envelope (decision #141, PR-2) and binds to it;
        // reverting to an existing revision reuses that revision's already-bound evidence.
        val revision = existingRev ?: revisionRepository.save(
            SourceRecordRevision(
                sourceRecordId = recordId,
                contentHash = hash,
                parserVersion = obs.parserVersion,
                retrievedAt = obs.retrievedAt,
                rawFacts = factsJson,
                importRunId = importRunId,
                rawEvidenceId = recordRawEvidence(record, obs, importRunId),
            ),
        )
        record.currentRevisionId = revision.id
        record.rawEvidenceId = revision.rawEvidenceId // denormalized current pointer
        record.contentHash = hash
        record.rawFacts = factsJson
        record.parserVersion = obs.parserVersion
        record.retrievedAt = obs.retrievedAt
        record.importRunId = importRunId
        record.status = if (wasFirst) "parsed" else "reparse_pending"
        sourceRecordRepository.save(record)
    }

    /** Persist the immutable fetch envelope for this observation (decision #141, PR-2), returning its id. */
    private fun recordRawEvidence(record: SourceRecord, obs: SourceObservation, importRunId: Long): Long {
        val ev = obs.evidence
        val evidence = rawEvidenceRepository.save(
            RawEvidence(
                importRunId = importRunId,
                sourceSiteId = record.sourceSiteId,
                canonicalUrl = obs.canonicalUrl,
                httpStatus = ev.httpStatus,
                retrievedAt = obs.retrievedAt,
                contentHash = ev.contentHash,
                contentType = ev.contentType,
                parserVersion = obs.parserVersion,
            ),
        )
        return requireNotNull(evidence.id)
    }

    /** A fetch envelope must prove a successful (2xx) fetch with a real body hash (decision #141, PR-2). */
    private fun validateFetchEvidence(evidence: FetchEvidence) {
        if (evidence.httpStatus !in 200..299) {
            throw FetchEvidenceException("fetch evidence http status ${evidence.httpStatus} is not 2xx")
        }
        if (!SHA256_HEX.matches(evidence.contentHash)) {
            throw FetchEvidenceException("fetch evidence content hash is not a sha256 hex digest")
        }
    }

    /**
     * Seal ingestion (decision #137-C4): no further observations may be staged; the run moves to review.
     * The apply phase ([ReviewService.markReviewed] + [ReviewService.applyRun]) takes over from here.
     */
    @Transactional
    fun closeIngestion(importRunId: Long): ImportRun =
        importRunStateMachine.transition(importRunId, ImportRunState.AWAITING_REVIEW)

    /** Abort a run as failed (runner/operator error). Terminal + immutable; records an optional reason. */
    @Transactional
    fun failRun(importRunId: Long, reason: String? = null): ImportRun = terminate(importRunId, ImportRunState.FAILED, reason)

    /** Abort a run as blocked (a robots/ToS/host gate tripped mid-run). Terminal + immutable. */
    @Transactional
    fun blockRun(importRunId: Long, reason: String? = null): ImportRun = terminate(importRunId, ImportRunState.BLOCKED, reason)

    private fun terminate(importRunId: Long, to: ImportRunState, reason: String?): ImportRun {
        val run = importRunStateMachine.transition(importRunId, to)
        if (reason != null) {
            run.notes = reason
            importRunRepository.save(run)
        }
        return run
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

        /** A per-run robots snapshot must be fetched within this window of the run start (decision #139-R3). */
        private val ROBOTS_MAX_AGE = Duration.ofHours(1)
        private val ROBOTS_FUTURE_SKEW = Duration.ofMinutes(5)

        /** A fetch-evidence body hash must be a lowercase sha256 hex digest (decision #141, PR-2). */
        private val SHA256_HEX = Regex("[0-9a-f]{64}")
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

/** An observation's fetch envelope was missing/incomplete (not 2xx, or no body hash) (decision #141, PR-2). */
class FetchEvidenceException(message: String) : RuntimeException(message)

/** Two source identities (external id vs canonical url) point at different records (decision #137-C5). */
class SourceIdentityConflictException(message: String) : RuntimeException(message)

/** No source_site is registered under the given code. */
class UnknownSourceSiteException(val sourceCode: String) :
    RuntimeException("No source site '$sourceCode'")
