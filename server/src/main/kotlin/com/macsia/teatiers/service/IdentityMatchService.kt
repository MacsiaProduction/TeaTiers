package com.macsia.teatiers.service

import com.macsia.teatiers.domain.MatchDecision
import com.macsia.teatiers.domain.NormalizedCandidate
import com.macsia.teatiers.repository.MatchDecisionRepository
import com.macsia.teatiers.repository.NameMatchCandidate
import com.macsia.teatiers.repository.NormalizedCandidateRepository
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
) {

    data class MatchProposal(val tier: String, val candidateTeaId: Long?, val score: Double?, val kind: String)

    /**
     * Produce and persist the best pending proposal for a source record's normalized candidate. Idempotent
     * per source record: an existing PENDING decision is updated in place rather than stacking a second one
     * (so a reparse re-proposal can't later be double-approved into duplicate teas). A re-propose is a no-op
     * once the record has an already-decided (approved/rejected) decision -- a fresh review needs that
     * decision revoked first.
     */
    @Transactional
    fun proposeFor(sourceRecordId: Long, importRunId: Long? = null): MatchDecision {
        val candidate = normalizedCandidateRepository.findBySourceRecordId(sourceRecordId)
            ?: error("no normalized_candidate for source_record $sourceRecordId; ingest it first")
        val proposal = bestProposal(candidate)
        val score = proposal.score?.let { BigDecimal.valueOf(it).setScale(4, RoundingMode.HALF_UP) }

        val existingPending = matchDecisionRepository.findBySourceRecordId(sourceRecordId)
            .firstOrNull { it.decision == "pending" }
        val decision = existingPending ?: MatchDecision(
            sourceRecordId = sourceRecordId,
            matchTier = proposal.tier,
            proposedKind = proposal.kind,
        )
        decision.matchTier = proposal.tier
        decision.proposedKind = proposal.kind
        decision.normalizedCandidateId = candidate.id
        decision.candidateTeaId = proposal.candidateTeaId
        decision.matchScore = score
        decision.importRunId = importRunId
        decision.decision = "pending"
        return matchDecisionRepository.save(decision)
    }

    private fun bestProposal(c: NormalizedCandidate): MatchProposal {
        val names = listOfNotNull(c.nameRu, c.nameEn, c.nameZh, c.namePinyin).filter { it.isNotBlank() }
        val translit = listOfNotNull(c.palladiusBridge, c.pinyinFromHanzi).filter { it.isNotBlank() }

        // Tier 0: authoritative alias -> identity.
        names.forEach { n ->
            teaIdentityAliasRepository.findAuthoritativeTeaIds(n).firstOrNull()
                ?.let { return finalize(c, it, "authoritative", 1.0) }
        }
        // Tier 1: exact same-script name.
        names.forEach { n ->
            teaRepository.findTeaIdsByExactNameNorm(n).firstOrNull()
                ?.let { return finalize(c, it, "exact", EXACT_SCORE) }
        }
        // Tier 3 (exact): the transliteration candidate matches a catalog name exactly.
        translit.forEach { t ->
            teaRepository.findTeaIdsByExactNameNorm(t).firstOrNull()
                ?.let { return finalize(c, it, "transliteration", TRANSLIT_EXACT_SCORE) }
        }
        // Tier 2: trigram near-match on a direct name.
        bestTrigram(names)?.let { return finalize(c, it.teaId, "trigram", it.score) }
        // Tier 3 (trigram): trigram on the transliteration candidate.
        bestTrigram(translit)?.let { return finalize(c, it.teaId, "transliteration", it.score) }

        return MatchProposal("none", null, null, "create_new")
    }

    private fun bestTrigram(values: List<String>): NameMatchCandidate? =
        values.flatMap { teaRepository.findTrigramNameCandidates(it, TRIGRAM_THRESHOLD, CANDIDATE_LIMIT) }
            .maxByOrNull { it.score }

    /** Attach unless the candidate's brand conflicts with the matched tea's (never auto-collapse vendors). */
    private fun finalize(c: NormalizedCandidate, teaId: Long, tier: String, score: Double): MatchProposal {
        val kind = if (brandConflict(c, teaId)) "conflict" else "attach"
        return MatchProposal(tier, teaId, score, kind)
    }

    private fun brandConflict(c: NormalizedCandidate, teaId: Long): Boolean {
        val candidateBrand = c.brand?.takeIf { it.isNotBlank() } ?: return false
        val tea = teaRepository.findById(teaId).orElse(null) ?: return false
        val teaBrand = tea.brand?.takeIf { it.isNotBlank() } ?: return false
        return CrossScriptKeys.normalizeHint(candidateBrand) != CrossScriptKeys.normalizeHint(teaBrand)
    }

    private companion object {
        const val EXACT_SCORE = 0.95
        const val TRANSLIT_EXACT_SCORE = 0.9
        // >= 0.3 to stay consistent with the pg_trgm `%` index prefilter; calibrated later on a labeled corpus.
        const val TRIGRAM_THRESHOLD = 0.3
        const val CANDIDATE_LIMIT = 5
    }
}
