package com.macsia.teatiers.repository

import com.macsia.teatiers.domain.Tea
import com.macsia.teatiers.domain.TeaType
import jakarta.persistence.LockModeType
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Lock
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import java.util.UUID

interface TeaRepository : JpaRepository<Tea, Long>, TeaSearchRepository {

    /** Stable client-facing lookup (V7, decision #136); the API resolves by public_id, not the BIGINT id. */
    fun findByPublicId(publicId: UUID): Tea?

    /**
     * Load a tea with a pessimistic write lock so concurrent canonical applies to the SAME tea serialize
     * (decision #139-R4): the selected-claim swap + fill-null must not race. Null if the tea is missing.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select t from Tea t where t.id = :id")
    fun findByIdForUpdate(@Param("id") id: Long): Tea?

    /** Cross-source identity lookup; used by the seed/enrich upsert to avoid duplicate rows. */
    fun findByWikidataQid(wikidataQid: String): Tea?

    /** Normalized dedup-key lookup backing the section 6 enrich-on-miss upsert. */
    fun findByDedupKey(dedupKey: String): Tea?

    /**
     * `/resolve` cache hit: the lowest id whose name in any locale equals [q] once unaccented and
     * lowercased. `unaccent` (enabled in V1) folds Latin diacritics (e.g. pinyin tone marks); it is
     * a no-op for Cyrillic/CJK. Keeps the second user who types a known tea from re-enriching it.
     */
    @Query(
        value = "SELECT t.id FROM tea t JOIN tea_name n ON n.tea_id = t.id " +
            // Visibility (decision #137-C3): a resolve cache hit never lands on a merged/retracted tea
            // (a PENDING/FAILED stub IS a valid hit -- the resolver re-arms/polls it).
            "WHERE lower(unaccent(n.name)) = lower(unaccent(:q)) AND t.status = 'active' ORDER BY t.id LIMIT 1",
        nativeQuery = true,
    )
    fun findIdByNormalizedName(@Param("q") q: String): Long?

    @Query("select distinct t from Tea t left join fetch t.names where t.id in :ids order by t.id")
    fun findAllWithNames(@Param("ids") ids: Collection<Long>): List<Tea>

    // Facets reflect only what browse/search can return (decision #137-C3): active, non-stub rows.
    @Query(
        "select distinct t.type from Tea t where t.status = 'active' " +
            "and (t.enrichmentState is null or t.enrichmentState not in ('PENDING', 'FAILED')) order by t.type",
    )
    fun distinctTypes(): List<TeaType>

    @Query(
        "select distinct t.originCountry from Tea t where t.originCountry is not null and t.status = 'active' " +
            "and (t.enrichmentState is null or t.enrichmentState not in ('PENDING', 'FAILED')) order by t.originCountry",
    )
    fun distinctOrigins(): List<String>

    /**
     * Identity matcher, exact tier: the distinct ACTIVE tea ids with a name in ANY locale whose normal form
     * equals [q] normalized. Uses the SAME lower(f_unaccent(...)) that builds `tea_name.name_norm`
     * (the shared-normalizer invariant, decision #136) so a scraped name lines up with the catalog. Filters
     * `tea.status='active'` (decision #141 / FND-P1-1) so the matcher never proposes a retracted/merged tea.
     */
    @Query(
        value = "SELECT DISTINCT n.tea_id FROM tea_name n JOIN tea t ON t.id = n.tea_id " +
            "WHERE n.name_norm = lower(f_unaccent(:q)) AND t.status = 'active'",
        nativeQuery = true,
    )
    fun findTeaIdsByExactNameNorm(@Param("q") q: String): List<Long>

    /**
     * Identity matcher, trigram tier: candidate ACTIVE tea ids within a script, best similarity per tea,
     * above [threshold] (>= 0.3 to stay consistent with the pg_trgm `%` index prefilter). Review-only in the
     * pilot -- never an auto-merge. Filters `tea.status='active'` (decision #141 / FND-P1-1).
     */
    @Query(
        value = """
            SELECT n.tea_id AS "teaId",
                   MAX(similarity(n.name_norm, lower(f_unaccent(:q)))) AS "score"
            FROM tea_name n JOIN tea t ON t.id = n.tea_id
            WHERE n.name_norm % lower(f_unaccent(:q)) AND t.status = 'active'
            GROUP BY n.tea_id
            HAVING MAX(similarity(n.name_norm, lower(f_unaccent(:q)))) >= :threshold
            ORDER BY "score" DESC
            LIMIT :limit
        """,
        nativeQuery = true,
    )
    fun findTrigramNameCandidates(
        @Param("q") q: String,
        @Param("threshold") threshold: Double,
        @Param("limit") limit: Int,
    ): List<NameMatchCandidate>
}

/** Projection for the trigram match tier: a candidate tea id + its best name similarity (0..1). */
interface NameMatchCandidate {
    val teaId: Long
    val score: Double
}
