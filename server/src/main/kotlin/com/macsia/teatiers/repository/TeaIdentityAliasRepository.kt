package com.macsia.teatiers.repository

import com.macsia.teatiers.domain.TeaIdentityAlias
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param

interface TeaIdentityAliasRepository : JpaRepository<TeaIdentityAlias, Long> {

    fun findByTeaId(teaId: Long): List<TeaIdentityAlias>

    /**
     * Tier-0 authoritative lookup: the distinct tea ids whose AUTHORITATIVE alias (curated or
     * human-confirmed, verified) normalizes to [norm]. A hit here IS identity. Uses the same
     * lower(f_unaccent(...)) normal form as `alias_norm` so the operator's input lines up.
     */
    @Query(
        value = "SELECT DISTINCT tea_id FROM tea_identity_alias " +
            "WHERE alias_norm = lower(f_unaccent(:norm)) " +
            "AND verified = true AND origin IN ('curated', 'human_confirmed')",
        nativeQuery = true,
    )
    fun findAuthoritativeTeaIds(@Param("norm") norm: String): List<Long>
}
