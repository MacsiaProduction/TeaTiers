package com.macsia.teatiers.data.remote

import com.macsia.teatiers.data.remote.dto.AppManifestDto
import retrofit2.Response
import retrofit2.http.GET

/**
 * The in-app auto-update manifest endpoint (decision #119): `GET /api/v1/app/latest`. Returns the
 * raw [Response] so the caller can treat **204 No Content** (no release configured) as "no update"
 * rather than a deserialization error. Anonymous; the manifest carries no PII.
 */
interface AppUpdateApi {

    @GET("app/latest")
    suspend fun latest(): Response<AppManifestDto>
}
