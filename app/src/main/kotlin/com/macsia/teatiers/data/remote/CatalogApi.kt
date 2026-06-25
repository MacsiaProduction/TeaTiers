package com.macsia.teatiers.data.remote

import com.macsia.teatiers.data.remote.dto.FacetsDto
import com.macsia.teatiers.data.remote.dto.OcrResponseDto
import com.macsia.teatiers.data.remote.dto.PageDto
import com.macsia.teatiers.data.remote.dto.ResolveRequestDto
import com.macsia.teatiers.data.remote.dto.ResolveResponseDto
import com.macsia.teatiers.data.remote.dto.TeaDetailDto
import com.macsia.teatiers.data.remote.dto.TeaSummaryDto
import okhttp3.MultipartBody
import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.Multipart
import retrofit2.http.POST
import retrofit2.http.Part
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
        // Nullable: an omitted `q` puts the endpoint in browse mode (server lists the whole
        // catalog, cursor-paginated by id) — see [CatalogRepository.browse].
        @Query("q") query: String? = null,
        @Query("locale") locale: String? = null,
        @Query("type") type: String? = null,
        @Query("origin") origin: String? = null,
        @Query("cursor") cursor: Long? = null,
        @Query("limit") limit: Int? = null,
    ): PageDto<TeaSummaryDto>

    // Response (not the bare DTO) so the repository can read a 410 lifecycle body (retracted/merged
    // tombstone) instead of it surfacing as an opaque HttpException.
    @GET("teas/{id}")
    suspend fun detail(@Path("id") id: Long): Response<TeaDetailDto>

    @GET("teas/facets")
    suspend fun facets(): FacetsDto

    /**
     * Resolve-on-add (plan §6): dedups the typed name against the catalog, enriches on a miss, and
     * returns the row + a status. Write-capable but unauthenticated (per-IP rate-limited server-side);
     * the client calls it in the background after an optimistic add, never per keystroke (#21).
     */
    @POST("teas/resolve")
    suspend fun resolve(@Body request: ResolveRequestDto): ResolveResponseDto

    /**
     * Scan-on-add (slice 1b): proxies a user-scanned packaging image to the OCR sidecar and returns
     * the recognized text for the user to review before it becomes a resolve `sourceText` (#25/#106).
     * Opt-in per scan; the image is processed in memory server-side and never stored.
     */
    @Multipart
    @POST("teas/ocr")
    suspend fun ocr(@Part file: MultipartBody.Part): OcrResponseDto
}
