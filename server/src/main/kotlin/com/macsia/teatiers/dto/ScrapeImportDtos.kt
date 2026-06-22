package com.macsia.teatiers.dto

import com.macsia.teatiers.validation.Iso3166
import com.macsia.teatiers.validation.KnownTeaType
import java.time.Instant

/**
 * The facts-only import contract (decision #136). These DTOs carry STRUCTURED FACTS only -- there is
 * deliberately NO description / prose / review / image field, NO `source`, and NO `verificationStatus`:
 * the importer sets `source = 'scrape'` and a non-verified status itself, and can never be handed
 * `verified`. A scraped name/field that smuggles prose is rejected by the FactsOnlyGuard.
 */

/** One localized name observed at a source. `locale` in {en, ru, zh-Hans, pinyin}. */
data class ScrapedName(
    val locale: String,
    val value: String,
    val isPrimary: Boolean = false,
)

/**
 * The structured facts of one product/catalog observation. No prose, no images. Semantic constraints
 * (decision #141, PR-3) are enforced by `FactsValidator` at ingest AND re-checked at canonical apply:
 * an unknown [type] and a non-ISO [originCountry] are REJECTED, never coerced. (region stays free-text
 * until the #138 Wikidata-QID region table lands; it is length/prose-bounded by FactsOnlyGuard.)
 */
data class ScrapedFacts(
    val names: List<ScrapedName>,
    @field:KnownTeaType val type: String? = null,
    @field:Iso3166 val originCountry: String? = null,
    val region: String? = null,
    val cultivar: String? = null,
    val oxidationMin: Int? = null,
    val oxidationMax: Int? = null,
    val brand: String? = null,
    val vendor: String? = null,
)

/**
 * The immutable fetch envelope for ONE observation (decision #141, PR-2). Proves which fetch produced the
 * facts: [contentHash] is the sha256 of the fetched BODY (distinct from the facts-JSON hash the importer
 * computes), [httpStatus] must be 2xx, [contentType] is the declared MIME. Persisted as a `RawEvidence` row
 * and bound to the source_record_revision; canonical apply fails closed if this chain is absent.
 */
data class FetchEvidence(
    val contentHash: String,
    val httpStatus: Int,
    val contentType: String? = null,
)

/**
 * One source observation handed to the importer (typically emitted by the local one-off scraper CLI).
 * Identity for re-import idempotency is (sourceSiteCode, externalId) or, failing that,
 * (sourceSiteCode, canonicalUrl). The importer NEVER creates a canonical tea from this directly -- it
 * stages a source_record + a review candidate. [evidence] is mandatory: no observation may be staged
 * without proof of the fetch that produced it (decision #141, PR-2).
 */
data class SourceObservation(
    val sourceSiteCode: String,
    val canonicalUrl: String,
    val externalId: String? = null,
    val retrievedAt: Instant,
    val parserVersion: String,
    val facts: ScrapedFacts,
    val evidence: FetchEvidence,
)

/**
 * The per-run robots snapshot a run must carry to be ingestible (decision #137-C4 + #139-R3). The scraper
 * fetches `robots.txt` and supplies a COMPLETE snapshot; the importer fails closed unless [decision] ==
 * "allow" AND the evidence is fresh + complete (a 2xx [httpStatus], a non-blank body [hash], the exact
 * [robotsUrl], and the [userAgent] the decision was made for). A hard gate, not audit metadata.
 */
data class RobotsEvidence(
    val decision: String, // 'allow' | 'disallow' | 'fail_closed' | 'not_checked'
    val robotsUrl: String,
    val userAgent: String,
    val fetchedAt: Instant,
    val httpStatus: Int? = null,
    val hash: String? = null,
)
