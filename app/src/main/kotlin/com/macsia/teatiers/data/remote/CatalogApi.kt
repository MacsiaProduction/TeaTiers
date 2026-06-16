package com.macsia.teatiers.data.remote

import com.macsia.teatiers.data.remote.dto.FacetsDto
import com.macsia.teatiers.data.remote.dto.PageDto
import com.macsia.teatiers.data.remote.dto.ResolveRequestDto
import com.macsia.teatiers.data.remote.dto.ResolveResponseDto
import com.macsia.teatiers.data.remote.dto.TeaDetailDto
import com.macsia.teatiers.data.remote.dto.TeaSummaryDto
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.POST
import retrofit2.http.Path
import retrofit2.http.Query

/**
 * Read-only catalog endpoints (plan §5). Suspend functions; the repository confines calls to a
 * background dispatcher and behind a try/catch (network failures surface as a fallback to cache,
 * not crashes). Detail/facets are defined for completeness; M3's first slice wires only search.
 */
interface CatalogApi {

    @GET("teas/search")
    suspend fun search(
        @Query("q") query: String,
        @Query("locale") locale: String? = null,
        @Query("type") type: String? = null,
        @Query("origin") origin: String? = null,
        @Query("cursor") cursor: Long? = null,
        @Query("limit") limit: Int? = null,
    ): PageDto<TeaSummaryDto>

    @GET("teas/{id}")
    suspend fun detail(@Path("id") id: Long): TeaDetailDto

    @GET("teas/facets")
    suspend fun facets(): FacetsDto

    /**
     * Resolve-on-add (plan §6): dedups the typed name against the catalog, enriches on a miss, and
     * returns the row + a status. Write-capable but unauthenticated (per-IP rate-limited server-side);
     * the client calls it in the background after an optimistic add, never per keystroke (#21).
     */
    @POST("teas/resolve")
    suspend fun resolve(@Body request: ResolveRequestDto): ResolveResponseDto
}
