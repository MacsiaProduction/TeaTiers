package com.macsia.teatiers.controller

import org.springframework.boot.context.properties.ConfigurationProperties

/**
 * Opt-in client-diagnostics options (decision #111), bound from config/env. The receiver is **off by
 * default** and **fails closed**: until the operator sets `enabled=true` AND a non-blank [token], the
 * endpoint replies `503` and stores nothing.
 *
 * [token] is a shared anti-spam credential the app sends as `X-Diagnostics-Token`. It ships inside the
 * APK, so it is **extractable and is NOT a security boundary** — it only raises the bar against trivial
 * or accidental flooding of the public endpoint; rotate it freely. The server still re-enforces the
 * field allowlist and size caps regardless of the token.
 */
@ConfigurationProperties(prefix = "teatiers.diagnostics")
data class ClientDiagnosticsProperties(
    val enabled: Boolean = false,
    val token: String = "",
    /** Reports older than this are removed by the daily retention purge. */
    val retentionDays: Long = 30,
    /** Stack traces are truncated to this many characters before storage. */
    val maxStackTraceChars: Int = 20_000,
    /** Short metadata fields (version name, device model, …) are truncated to this many characters. */
    val maxFieldChars: Int = 120,
    /** At most this many numeric row-count entries are kept from a migration signal. */
    val maxRowCountKeys: Int = 32,
    /**
     * Global ceiling on accepted reports per UTC day — bounds disk growth since the APK-extractable
     * token is not a real auth barrier (review finding). `<= 0` disables the cap.
     */
    val dailyCap: Int = 500,
)
