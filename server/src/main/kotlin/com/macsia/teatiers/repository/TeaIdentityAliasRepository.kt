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

    /**
     * Serialize concurrent authoritative-alias promotion of the SAME identity (H4 / SRV-P1-1, decision #141
     * review). The global one-active-owner invariant is a service-layer check-then-insert with no DB unique
     * index (a partial index can't reference the owner tea's status, and demotion-at-tombstone infra doesn't
     * exist yet), so two concurrent cross-run applies could otherwise both pass the check. A transaction-scoped
     * Postgres advisory lock makes that check-then-insert atomic across transactions. The lock key is built in
     * SQL from the EXACT normal form the invariant compares on -- `:locale` (matched verbatim, as in
     * [findOtherActiveAuthoritativeOwners]) joined to `lower(f_unaccent(:alias))` (the same expression that
     * defines `alias_norm`) -- so accent/case variants that collapse to ONE identity also collapse to ONE lock
     * (SRV-P1-1). A Kotlin `lowercase()` key would miss accents and let two colliding variants take different
     * locks, defeating the lock. The inner SELECT runs the lock (side effect); the outer returns 1.
     */
    @Query(
        value = "SELECT 1 FROM (SELECT pg_advisory_xact_lock(" +
            "hashtext(:locale || ':' || lower(f_unaccent(:alias)))::bigint)) AS _lock",
        nativeQuery = true,
    )
    fun lockAuthoritativeAlias(@Param("locale") locale: String, @Param("alias") alias: String): Int
}

/** Repair-report row: an authoritative (locale, normalized alias) held by [teaCount] active teas (>1). */
interface DuplicateAuthoritativeAliasRow {
    val locale: String
    val aliasNorm: String
    val teaCount: Long
}
