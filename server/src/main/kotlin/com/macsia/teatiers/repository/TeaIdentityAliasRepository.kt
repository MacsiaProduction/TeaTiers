package com.macsia.teatiers.repository

import com.macsia.teatiers.domain.TeaIdentityAlias
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param

interface TeaIdentityAliasRepository : JpaRepository<TeaIdentityAlias, Long> {

    fun findByTeaId(teaId: Long): List<TeaIdentityAlias>

    /**
     * Tier-0 authoritative lookup: the distinct ACTIVE tea ids whose AUTHORITATIVE alias (curated or
     * human-confirmed, verified) normalizes to [norm]. A hit here IS identity. Uses the same
     * lower(f_unaccent(...)) normal form as `alias_norm` so the operator's input lines up. Joins `tea` and
     * filters `status='active'` (decision #141 / FND-P1-1) so a retracted/merged owner is never proposed.
     */
    @Query(
        value = "SELECT DISTINCT a.tea_id FROM tea_identity_alias a JOIN tea t ON t.id = a.tea_id " +
            "WHERE a.alias_norm = lower(f_unaccent(:norm)) " +
            "AND a.verified = true AND a.origin IN ('curated', 'human_confirmed') AND t.status = 'active'",
        nativeQuery = true,
    )
    fun findAuthoritativeTeaIds(@Param("norm") norm: String): List<Long>

    /**
     * The OTHER active teas that already hold an authoritative alias for ([locale], [alias]) -- excluding
     * [teaId]. Backs the global authoritative-alias invariant (decision #141 / FND-P1-1): an authoritative
     * alias may belong to at most one active tea per (locale, normalized form). Keyed on `alias_norm` so
     * accent/case variants collapse to the same identity.
     */
    @Query(
        value = "SELECT DISTINCT a.tea_id FROM tea_identity_alias a JOIN tea t ON t.id = a.tea_id " +
            "WHERE a.locale = :locale AND a.alias_norm = lower(f_unaccent(:alias)) " +
            "AND a.verified = true AND a.origin IN ('curated', 'human_confirmed') " +
            "AND t.status = 'active' AND a.tea_id <> :teaId",
        nativeQuery = true,
    )
    fun findOtherActiveAuthoritativeOwners(
        @Param("locale") locale: String,
        @Param("alias") alias: String,
        @Param("teaId") teaId: Long,
    ): List<Long>

    /**
     * Repair report (decision #141 / FND-P1-1): (locale, normalized alias) groups owned by more than one
     * ACTIVE tea -- existing violations of the global authoritative-alias invariant an operator must
     * merge/repair. Empty on a clean catalog.
     */
    @Query(
        value = "SELECT a.locale AS locale, a.alias_norm AS aliasNorm, count(DISTINCT a.tea_id) AS teaCount " +
            "FROM tea_identity_alias a JOIN tea t ON t.id = a.tea_id " +
            "WHERE a.verified = true AND a.origin IN ('curated', 'human_confirmed') AND t.status = 'active' " +
            "GROUP BY a.locale, a.alias_norm HAVING count(DISTINCT a.tea_id) > 1",
        nativeQuery = true,
    )
    fun findDuplicateActiveAuthoritativeAliases(): List<DuplicateAuthoritativeAliasRow>
}

/** Repair-report row: an authoritative (locale, normalized alias) held by [teaCount] active teas (>1). */
interface DuplicateAuthoritativeAliasRow {
    val locale: String
    val aliasNorm: String
    val teaCount: Long
}
