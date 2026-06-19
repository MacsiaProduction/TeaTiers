package com.macsia.teatiers.dto

/**
 * The wire contract for an opt-in client diagnostic report (decision #111). This is a deliberately
 * **minimal, first-party** shape — NOT ACRA's full multi-field schema — so the payload is entirely
 * under our control and trivially data-minimized: the app maps its ACRA report down to exactly these
 * allowlisted fields before sending.
 *
 * [rowCounts] is typed `Map<String, Int>`, so a non-integer value fails JSON binding outright — a free
 * string can never reach storage through it. The server still re-sanitizes everything (truncation,
 * key filtering, kind validation) in [com.macsia.teatiers.service.ClientDiagnosticsService].
 */
data class ClientDiagnosticReportDto(
    /** "crash" or "room_migration_signal"; anything else is rejected with 422. */
    val kind: String = "",
    val appVersionCode: Int? = null,
    val appVersionName: String? = null,
    val androidSdk: Int? = null,
    val deviceManufacturer: String? = null,
    val deviceModel: String? = null,
    val buildType: String? = null,
    /** crash only: the exception stack trace. */
    val stackTrace: String? = null,
    /** room_migration_signal only: numeric before/after counts (e.g. {"boards_before":3,...}). */
    val rowCounts: Map<String, Int>? = null,
)
