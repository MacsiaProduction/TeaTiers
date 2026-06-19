package com.macsia.teatiers.data.diagnostics

import kotlinx.serialization.Serializable

/**
 * The allowlisted diagnostic report the app sends to the first-party `/api/v1/client-diagnostics`
 * endpoint (decision #111). Field names match the server's DTO (default Jackson naming) so the same
 * JSON binds on both ends.
 *
 * This is the **only** shape that ever leaves the device for diagnostics — by construction it carries
 * app/build/device metadata, a stack trace, and numeric [rowCounts] and NOTHING user-identifying
 * (never tea/board names, notes, photo URIs, coords, OCR text, or account data — TeaTiers has none).
 * Encode with `explicitNulls = false` so absent fields are simply omitted.
 */
@Serializable
data class ClientDiagnosticReportDto(
    val kind: String,
    val appVersionCode: Int? = null,
    val appVersionName: String? = null,
    val androidSdk: Int? = null,
    val deviceManufacturer: String? = null,
    val deviceModel: String? = null,
    val buildType: String? = null,
    val stackTrace: String? = null,
    val rowCounts: Map<String, Int>? = null,
) {
    companion object {
        const val KIND_CRASH = "crash"
        const val KIND_MIGRATION_SIGNAL = "room_migration_signal"
    }
}
