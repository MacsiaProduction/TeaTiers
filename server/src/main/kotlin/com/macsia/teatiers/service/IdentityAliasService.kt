package com.macsia.teatiers.service

import com.macsia.teatiers.domain.TeaIdentityAlias
import com.macsia.teatiers.repository.TeaIdentityAliasRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

/**
 * Operator management of the AUTHORITATIVE cross-script alias seed (decision #136). Curated and
 * human-confirmed aliases are the only ones that establish identity (Tier 0); this is how an operator
 * seeds the top teas (`Да Хун Пао`/`Da Hong Pao`/`大红袍`) and how an approved review decision promotes a
 * library-derived alias to confirmed.
 */
@Service
class IdentityAliasService(
    private val aliasRepository: TeaIdentityAliasRepository,
) {

    /**
     * Add (or promote) an authoritative alias. Idempotent on (tea, locale, alias). Enforces the global
     * authoritative-alias invariant (decision #141 / FND-P1-1): an authoritative alias may belong to at most
     * ONE active tea per (locale, normalized form), so Tier-0 identity stays deterministic. A collision with
     * a DIFFERENT active tea fails closed with [DuplicateAuthoritativeAliasException] -- the operator should
     * merge the two identities (or repair the duplicate) rather than create a second owner.
     */
    @Transactional
    fun addAuthoritative(
        teaId: Long,
        locale: String,
        alias: String,
        romanizationSystem: String? = null,
        origin: String = "curated",
        source: String? = null,
    ): TeaIdentityAlias {
        require(origin in AUTHORITATIVE_ORIGINS) { "authoritative alias origin must be curated/human_confirmed" }
        // Serialize concurrent promotion of the SAME (locale, alias) so the active-owner check-then-insert
        // below is atomic across transactions (H4): the invariant has no backing DB unique index. Key
        // approximates alias_norm (lower+trim); accent variants are a tiny residual the check still catches.
        aliasRepository.lockAuthoritativeAliasKey("$locale:${alias.trim().lowercase()}")
        aliasRepository.findOtherActiveAuthoritativeOwners(locale, alias, teaId).firstOrNull()?.let { other ->
            throw DuplicateAuthoritativeAliasException(locale, alias, other, teaId)
        }
        val existing = aliasRepository.findByTeaId(teaId).firstOrNull { it.locale == locale && it.alias == alias }
        if (existing != null) {
            existing.origin = origin
            existing.verified = true
            existing.romanizationSystem = romanizationSystem ?: existing.romanizationSystem
            return aliasRepository.save(existing)
        }
        return aliasRepository.save(
            TeaIdentityAlias(
                teaId = teaId,
                locale = locale,
                alias = alias,
                origin = origin,
                romanizationSystem = romanizationSystem,
                verified = true,
                source = source,
            ),
        )
    }

    /**
     * Read-only collision check (H3, decision #141 review): the active tea already holding an authoritative
     * alias for ([locale], [alias]) excluding [teaId] (null = a not-yet-created tea, so exclude nothing), or
     * null if none. Same query as the [addAuthoritative] guard, so the apply phase can detect a collision and
     * quarantine just that decision BEFORE writing -- instead of the write throwing mid-batch and rolling the
     * whole run back. Runs in the caller's apply tx, so it also sees aliases written by earlier decisions.
     */
    @Transactional(readOnly = true)
    fun conflictingOwner(locale: String, alias: String, teaId: Long?): Long? =
        aliasRepository.findOtherActiveAuthoritativeOwners(locale, alias, teaId ?: NO_TEA).firstOrNull()

    private companion object {
        val AUTHORITATIVE_ORIGINS = setOf("curated", "human_confirmed")

        /** Sentinel for "no tea to exclude" -- no real tea has this id, so the check returns every owner. */
        const val NO_TEA = -1L
    }
}

/**
 * An authoritative alias was about to be attached to a second active tea (decision #141 / FND-P1-1) -- the
 * global one-owner-per-(locale, alias) invariant. The two identities must be merged/repaired instead.
 */
class DuplicateAuthoritativeAliasException(
    val locale: String,
    val alias: String,
    val existingTeaId: Long,
    val attemptedTeaId: Long,
) : RuntimeException(
    "authoritative alias '$alias' ($locale) already belongs to active tea $existingTeaId; " +
        "cannot also assign it to tea $attemptedTeaId -- merge the identities instead",
)
