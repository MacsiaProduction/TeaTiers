package com.macsia.teatiers.data.diagnostics

import android.os.Build
import android.util.Log
import com.macsia.teatiers.BuildConfig
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.util.concurrent.TimeUnit

private const val TAG = "DiagnosticsWire"

/**
 * The single egress for opt-in diagnostics (decision #111): stamps an allowlisted report with stable
 * device/build metadata and POSTs it to the first-party `/client-diagnostics` endpoint with the
 * shared anti-spam token. Both the ACRA crash sender and the migration sentinel funnel through here
 * so exactly one, auditable JSON shape can ever leave the device.
 *
 * It is a **no-op when [BuildConfig.DIAGNOSTICS_TOKEN] is blank** (a stock build), so diagnostics
 * never leave a device that wasn't built with a configured token AND whose user opted in.
 */
object DiagnosticsWire {

    private val json = Json { explicitNulls = false; encodeDefaults = false }
    private val jsonMedia = "application/json; charset=utf-8".toMediaType()

    // A tiny dedicated client: diagnostics are infrequent (a crash, a startup check) and must never
    // hang the app, so short timeouts and no shared interceptors.
    private val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(5, TimeUnit.SECONDS)
            .readTimeout(5, TimeUnit.SECONDS)
            .build()
    }

    /** Build an allowlisted report, filling in stable device/build fields the caller shouldn't repeat. */
    fun report(
        kind: String,
        stackTrace: String? = null,
        rowCounts: Map<String, Int>? = null,
    ): ClientDiagnosticReportDto = ClientDiagnosticReportDto(
        kind = kind,
        appVersionCode = BuildConfig.VERSION_CODE,
        appVersionName = BuildConfig.VERSION_NAME,
        androidSdk = Build.VERSION.SDK_INT,
        deviceManufacturer = Build.MANUFACTURER,
        deviceModel = Build.MODEL,
        buildType = BuildConfig.BUILD_TYPE,
        stackTrace = stackTrace,
        rowCounts = rowCounts,
    )

    private fun endpointUrl(): String = BuildConfig.CATALOG_BASE_URL.trimEnd('/') + "/client-diagnostics"

    /**
     * POST [report] synchronously; returns true iff the server accepted it. Blank token => skip
     * (no-op). Network failures are swallowed (best-effort) — diagnostics must never crash the app or
     * become a crash loop.
     */
    fun post(report: ClientDiagnosticReportDto): Boolean {
        val token = BuildConfig.DIAGNOSTICS_TOKEN
        if (token.isBlank()) return false
        return try {
            val body = json.encodeToString(report).toRequestBody(jsonMedia)
            val request = Request.Builder()
                .url(endpointUrl())
                .addHeader("X-Diagnostics-Token", token)
                .post(body)
                .build()
            client.newCall(request).execute().use { it.isSuccessful }
        } catch (e: IOException) {
            Log.w(TAG, "diagnostics post failed: ${e.message}")
            false
        } catch (e: IllegalArgumentException) {
            Log.w(TAG, "diagnostics post bad config: ${e.message}")
            false
        }
    }
}
