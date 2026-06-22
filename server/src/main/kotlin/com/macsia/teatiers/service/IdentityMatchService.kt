package com.macsia.teatiers.service

import com.macsia.teatiers.domain.MatchCandidate
import com.macsia.teatiers.domain.MatchDecision
import com.macsia.teatiers.domain.NormalizedCandidate
import com.macsia.teatiers.repository.MatchCandidateRepository
import com.macsia.teatiers.repository.MatchDecisionRepository
import com.macsia.teatiers.repository.NameMatchCandidate
import com.macsia.teatiers.repository.NormalizedCandidateRepository
import com.macsia.teatiers.repository.SourceRecordRepository
import com.macsia.teatiers.repository.TeaIdentityAliasRepository
import com.macsia.teatiers.repository.TeaRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.math.BigDecimal
import java.math.RoundingMode

/**
 * Cross-script identity matcher (decision #136). For a staged source_record it proposes ONE match
 * decision and writes it to the review queue as 'pending'. In the pilot NOTHING auto-merges or
 * auto-creates: even a Tier-0 authoritative hit is a proposal the operator approves. Tiers, in priority:
 *
 *   0 authoritative  -- a curated/human-confirmed alias normalizes to a catalog name -> that IS identity
 *   1 exact          -- a catalog name in any locale equals the candidate name (shared f_unaccent norm)
 *   3 transliteration (exact) -- the Palladius(Cyrillic)->pinyin / pypinyin(Hanzi)->pinyin candidate is exact
 *   2 trigram        -- pg_trgm near-match within a script
 *   3 transliteration (trigram) -- trigram on the transliteration candidate
 *   none             -- propose create_new
 *
 * Brand/vendor never auto-collapses: a branded candidate matching a differently-branded tea is flagged
 * 'conflict' for the operator, not silently attached (the anti-vendor-lot-explosion rule).
 */
@Service
class IdentityMatchService(
    private val teaRepository: TeaRepository,
    private val teaIdentityAliasRepository: TeaIdentityAliasRepository,
    private val normalizedCandidateRepository: NormalizedCandidateRepository,
    private val matchDecisionRepository: MatchDecisionRepository,
    private val matchCandidateRepository: MatchCandidateRepository,
    private val sourceRecordRepository: SourceRecordRepository,
) {

    /** One ranked candidate within the winning tier: a distinct ACTIVE tea + its best score for that tier. */
    data class RankedCandidate(val teaId: Long, val score: Double?)

    /**
     * The chosen single-row proposal ([candidateTeaId]/[tier]/[score]/[kind]) PLUS the full ranked
     * [candidates] set within the winning tier (decision #141 / FND-P1-1). [kind]='conflict' when the top
     * tier has more than one distinct active owner (ambiguity the operator must resolve with an explicit
     * target) or on a brand mismatch; never an auto-attach to one of several owners.
     */
    data class MatchProposal(
        val tier: String,
        val candidateTeaId: Long?,
        val score: Double?,
        val kind: String,
        val candidates: List<RankedCandidate> = emptyList(),
    )

    /**
     * Produce and persist the best pending proposal for a source record's CURRENT revision (decision
     * #137-C5). A decision already made (approved/rejected) for the current revision is returned unchanged
     * (idempotent). But a NEW revision -- a corrected re-import after an earlier approval -- gets a FRESH
     * pending decision bound to that revision, so corrections never stall at 'reparse_pending'. There is at
     * most one open pending per record (enforced by a DB partial-unique); it is re-pointed in place.
     */
    @Transactional
    fun proposeFor(sourceRecordId: Long, importRunId: Long? = null): MatchDecision {
        val record = sourceRecordRepository.findById(sourceRecordId).orElse(null)
            ?: error("no source_record $sourceRecordId")
        val currentRevisionId = requireNotNull(record.currentRevisionId) {
            "source_record $sourceRecordId has no current revision; ingest it first"
        }
        val candidate = normalizedCandidateRepository.findBySourceRecordId(sourceRecordId)
            ?: error("no normalized_candidate for source_record $sourceRecordId; ingest it first")
        val decisions = matchDecisionRepository.findBySourceRecordId(sourceRecordId)
        // A decision already made for THIS exact revision -> return it; a later revision still re-reviews.
        decisions.firstOrNull { it.sourceRecordRevisionId == currentRevisionId && it.decision in DECIDED }
            ?.let { return it }

        val proposal = bestProposal(candidate)
        val score = proposal.score?.let { BigDecimal.valueOf(it).setScale(4, RoundingMode.HALF_UP) }

        val existingPending = decisions.firstOrNull { it.decision == "pending" }
        val decision = existingPending ?: MatchDecision(
            sourceRecordId = sourceRecordId,
            matchTier = proposal.tier,
            proposedKind = proposal.kind,
        )
        decision.sourceRecordRevisionId = currentRevisionId
        decision.matchTier = proposal.tier
        decision.proposedKind = proposal.kind
        decision.normalizedCandidateId = candidate.id
        decision.candidateTeaId = proposal.candidateTeaId
        decision.matchScore = score
        decision.importRunId = importRunId
        decision.decision = "pending"
        val saved = matchDecisionRepository.save(decision)
        persistCandidates(requireNotNull(saved.id), proposal)
        return saved
    }

    /** Replace the decision's ranked candidate set (it is re-pointed in place across revisions, #141). */
    private fun persistCandidates(decisionId: Long, proposal: MatchProposal) {
        matchCandidateRepository.deleteByMatchDecisionId(decisionId)
        proposal.candidates.forEachIndexed { rank, cand ->
            matchCandidateRepository.save(
                MatchCandidate(
                    matchDecisionId = decisionId,
                    teaId = cand.teaId,
                    matchTier = proposal.tier,
                    matchScore = cand.score?.let { BigDecimal.valueOf(it).setScale(4, RoundingMode.HALF_UP) },
                    rank = rank,
                ),
            )
        }
    }

    /**
     * The first tier (in priority order) that yields >=1 distinct ACTIVE candidate wins; its full ranked
     * candidate set is returned (decision #141 / FND-P1-1) -- no longer collapsed to a single firstOrNull/max.
     */
    private fun bestProposal(c: NormalizedCandidate): MatchProposal {
        val names = listOfNotNull(c.nameRu, c.nameEn, c.nameZh, c.namePinyin).filter { it.isNotBlank() }
        val translit = listOfNotNull(c.palladiusBridge, c.pinyinFromHanzi).filter { it.isNotBlank() }

        exactTier(names, 1.0) { teaIdentityAliasRepository.findAuthoritativeTeaIds(it) }
            ?.let { return finalize(c, "authoritative", it) }
        exactTier(names, EXACT_SCORE) { teaRepository.findTeaIdsByExactNameNorm(it) }
            ?.let { return finalize(c, "exact", it) }
        exactTier(translit, TRANSLIT_EXACT_SCORE) { teaRepository.findTeaIdsByExactNameNorm(it) }
            ?.let { return finalize(c, "transliteration", it) }
        trigramTier(names)?.let { return finalize(c, "trigram", it) }
        trigramTier(translit)?.let { return finalize(c, "transliteration", it) }

        return MatchProposal("none", null, null, "create_new")
    }

    /** A fixed-score tier (authoritative/exact): distinct active owners for [values], or null if none. */
    private inline fun exactTier(values: List<String>, score: Double, lookup: (String) -> List<Long>): List<RankedCandidate>? =
        values.flatMap(lookup).distinct().sorted().map { RankedCandidate(it, score) }.ifEmpty { null }

    /** The trigram tier: one entry per distinct active tea (its best score), ranked desc; null if none. */
    private fun trigramTier(values: List<String>): List<RankedCandidate>? =
        values.flatMap { teaRepository.findTrigramNameCandidates(it, TRIGRAM_THRESHOLD, CANDIDATE_LIMIT) }
            .groupBy { it.teaId }
            .map { (teaId, hits) -> RankedCandidate(teaId, hits.maxOf(NameMatchCandidate::score)) }
            .sortedWith(compareByDescending<RankedCandidate> { it.score }.thenBy { it.teaId })
            .take(CANDIDATE_LIMIT)
            .ifEmpty { null }

    /**
     * Turn a winning tier's ranked candidate set into a proposal. More than one distinct owner at the top is
     * ambiguity -> 'conflict' (the operator must pass an explicit target, enforced by ReviewService); a
     * single owner attaches unless its brand conflicts (the anti-vendor-lot rule). The top candidate is the
     * default target either way.
     */
    private fun finalize(c: NormalizedCandidate, tier: String, candidates: List<RankedCandidate>): MatchProposal {
        val top = candidates.first()
        val kind = when {
            candidates.size > 1 -> "conflict" // multiple distinct active owners: never auto-attach to one
            brandConflict(c, top.teaId) -> "conflict"
            else -> "attach"
        }
        return MatchProposal(tier, top.teaId, top.score, kind, candidates)
    }

    private fun brandConflict(c: NormalizedCandidate, teaId: Long): Boolean {
        val candidateBrand = c.brand?.takeIf { it.isNotBlank() } ?: return false
        val tea = teaRepository.findById(teaId).orElse(null) ?: return false
        val teaBrand = tea.brand?.takeIf { it.isNotBlank() } ?: return false
        return CrossScriptKeys.normalizeHint(candidateBrand) != CrossScriptKeys.normalizeHint(teaBrand)
    }

    private companion object {
        /** Terminal decision states: a re-propose for the same revision returns the existing one. */
        val DECIDED = setOf("approved_new", "approved_merge", "rejected")
        const val EXACT_SCORE = 0.95
        const val TRANSLIT_EXACT_SCORE = 0.9
        // >= 0.3 to stay consistent with the pg_trgm `%` index prefilter; calibrated later on a labeled corpus.
        const val TRIGRAM_THRESHOLD = 0.3
        const val CANDIDATE_LIMIT = 5
    }
}
