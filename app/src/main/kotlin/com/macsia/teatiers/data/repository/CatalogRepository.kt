package com.macsia.teatiers.data.repository

import com.macsia.teatiers.data.db.CatalogDao
import com.macsia.teatiers.data.db.toCacheEntity
import com.macsia.teatiers.data.db.toDomain
import com.macsia.teatiers.data.remote.CatalogApi
import com.macsia.teatiers.data.remote.dto.ResolveRequestDto
import com.macsia.teatiers.data.remote.toDomain
import com.macsia.teatiers.domain.model.CatalogTea
import com.macsia.teatiers.domain.model.CatalogTeaDetail
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import retrofit2.HttpException
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

/** Outcome of a catalog search. [Loaded.fromCache] means the network failed and these are reused. */
sealed interface CatalogSearchResult {
    data class Loaded(val teas: List<CatalogTea>, val fromCache: Boolean) : CatalogSearchResult

    /** Network unreachable and the cache had nothing for this query. */
    data object Offline : CatalogSearchResult

    /** The server answered with an error (and the cache had nothing). */
    data object Error : CatalogSearchResult
}

/** Outcome of loading one catalog tea's full detail (plan §5, `GET /teas/{id}`). */
sealed interface CatalogDetailResult {
    data class Loaded(val detail: CatalogTeaDetail) : CatalogDetailResult

    /** Network unreachable. */
    data object Offline : CatalogDetailResult

    /** The server answered with an error (4xx/5xx). */
    data object Error : CatalogDetailResult
}

/** Outcome of `POST /teas/resolve` (plan §6) — the optimistic enrich-on-add call. */
sealed interface ResolveResult {
    /** A ready row: a cache/dedup hit (MATCHED) or a fresh Wikidata import (ENRICHED). */
    data class Matched(val detail: CatalogTeaDetail) : ResolveResult

    /** Wikidata missed; the server is enriching a PENDING stub — poll `detail([catalogTeaId])`. */
    data class Enriching(val catalogTeaId: Long) : ResolveResult

    /** No match and the server's LLM tier is off — nothing was created. */
    data object Unresolved : ResolveResult

    /** Network unreachable — the caller queues the tea and retries on reconnect. */
    data object Offline : ResolveResult

    /** The server (or an unexpected payload) errored — the caller marks the tea retryable. */
    data object Error : ResolveResult
}

interface CatalogRepository {
    /** Network-first search; on failure falls back to the offline cache (plan §4b/§6 step 1). */
    suspend fun search(query: String, limit: Int = DEFAULT_LIMIT): CatalogSearchResult

    /**
     * Loads one tea's full detail from the network. Detail is not cached (unlike search): it is only
     * reached on an explicit tap, so a failure surfaces a retry rather than a stale offline copy.
     */
    suspend fun detail(id: Long): CatalogDetailResult

    /**
     * Resolves a typed tea name against the shared catalog (plan §6): dedup/Wikidata first, LLM
     * enrich on a miss. [sourceText] is an optional pasted vendor blurb that grounds the profile (#25).
     */
    suspend fun resolve(name: String, locale: String? = null, sourceText: String? = null): ResolveResult

    companion object {
        const val DEFAULT_LIMIT = 20
    }
}

@Singleton
class DefaultCatalogRepository @Inject constructor(
    private val api: CatalogApi,
    private val catalogDao: CatalogDao,
) : CatalogRepository {

    override suspend fun search(query: String, limit: Int): CatalogSearchResult {
        val trimmed = query.trim()
        if (trimmed.isEmpty()) return CatalogSearchResult.Loaded(emptyList(), fromCache = false)
        return withContext(Dispatchers.IO) {
            try {
                val teas = api.search(query = trimmed, limit = limit).items.map { it.toDomain() }
                if (teas.isNotEmpty()) {
                    val now = System.currentTimeMillis()
                    catalogDao.upsertAll(teas.map { it.toCacheEntity(now) })
                }
                CatalogSearchResult.Loaded(teas, fromCache = false)
            } catch (_: IOException) {
                cachedOr(trimmed, limit, CatalogSearchResult.Offline)
            } catch (_: HttpException) {
                cachedOr(trimmed, limit, CatalogSearchResult.Error)
            }
        }
    }

    override suspend fun detail(id: Long): CatalogDetailResult = withContext(Dispatchers.IO) {
        try {
            CatalogDetailResult.Loaded(api.detail(id).toDomain())
        } catch (_: IOException) {
            CatalogDetailResult.Offline
        } catch (_: HttpException) {
            CatalogDetailResult.Error
        }
    }

    override suspend fun resolve(name: String, locale: String?, sourceText: String?): ResolveResult =
        withContext(Dispatchers.IO) {
            val trimmed = name.trim()
            if (trimmed.isEmpty()) return@withContext ResolveResult.Unresolved
            try {
                val response = api.resolve(ResolveRequestDto(name = trimmed, locale = locale, sourceText = sourceText))
                when (response.status) {
                    // MATCHED (cache/dedup) and ENRICHED (fresh Wikidata) both return a ready row.
                    "MATCHED", "ENRICHED" ->
                        response.tea?.let { ResolveResult.Matched(it.toDomain()) } ?: ResolveResult.Error
                    "ENRICHING" ->
                        response.tea?.let { ResolveResult.Enriching(it.id) } ?: ResolveResult.Error
                    "UNRESOLVED" -> ResolveResult.Unresolved
                    else -> ResolveResult.Error
                }
            } catch (_: IOException) {
                ResolveResult.Offline
            } catch (_: HttpException) {
                ResolveResult.Error
            }
        }

    private suspend fun cachedOr(
        query: String,
        limit: Int,
        networkFailure: CatalogSearchResult,
    ): CatalogSearchResult {
        val cached = catalogDao.search(query.lowercase(), limit).map { it.toDomain() }
        return if (cached.isNotEmpty()) {
            CatalogSearchResult.Loaded(cached, fromCache = true)
        } else {
            networkFailure
        }
    }
}
