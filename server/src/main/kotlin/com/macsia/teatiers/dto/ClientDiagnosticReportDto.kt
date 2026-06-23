package com.macsia.teatiers.dto

import jakarta.validation.constraints.Size

/**
 * The wire contract for an opt-in client diagnostic report (decision #111). This is a deliberately
 * **minimal, first-party** shape — NOT ACRA's full multi-field schema — so the payload is entirely
 * under our control and trivially data-minimized: the app maps its ACRA report down to exactly these
 * allowlisted fields before sending.
 *
 * [rowCounts] is typed `Map<String, Int>`, so a non-integer value fails JSON binding outright — a free
 * string can never reach storage through it. The server still re-sanitizes everything (truncation,
 * key filtering, kind validation) in [com.macsia.teatiers.service.ClientDiagnosticsService].
 *
 * Bean Validation caps (SRV-P2-1) reject an abusive field outright at the controller's `@Valid` boundary
 * rather than silently truncating it. They sit WELL ABOVE the service's truncation caps (stack 20k, field
 * 120, 32 keys) so a legitimate report is never rejected — they bound abuse (a megabyte field / thousands of
 * keys), while the service truncation stays as defense in depth and the Caddy per-route body cap bounds the
 * raw request body before it is parsed.
 */
data class ClientDiagnosticReportDto(
    /** "crash" or "room_migration_signal"; anything else is rejected with 422. */
    @field:Size(max = MAX_KIND)
    val kind: String = "",
    val appVersionCode: Int? = null,
    @field:Size(max = MAX_SHORT_FIELD)
    val appVersionName: String? = null,
    val androidSdk: Int? = null,
    @field:Size(max = MAX_SHORT_FIELD)
    val deviceManufacturer: String? = null,
    @field:Size(max = MAX_SHORT_FIELD)
    val deviceModel: String? = null,
    @field:Size(max = MAX_KIND)
    val buildType: String? = null,
    /** crash only: the exception stack trace. */
    @field:Size(max = MAX_STACK_TRACE)
    val stackTrace: String? = null,
    /** room_migration_signal only: numeric before/after counts (e.g. {"boards_before":3,...}). */
    @field:Size(max = MAX_ROW_COUNT_KEYS)
    val rowCounts: Map<String, Int>? = null,
) {
    private companion object {
        const val MAX_KIND = 64
        const val MAX_SHORT_FIELD = 256
        const val MAX_STACK_TRACE = 40_000
        const val MAX_ROW_COUNT_KEYS = 256
    }
}
