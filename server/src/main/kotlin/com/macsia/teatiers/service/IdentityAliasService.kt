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

    private companion object {
        val AUTHORITATIVE_ORIGINS = setOf("curated", "human_confirmed")
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
