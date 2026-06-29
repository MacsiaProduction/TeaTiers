package com.macsia.teatiers.service

import com.macsia.teatiers.controller.ClientDiagnosticsProperties
import com.macsia.teatiers.controller.InvalidClientReportException
import com.macsia.teatiers.domain.ClientDiagnostic
import com.macsia.teatiers.dto.ClientDiagnosticReportDto
import com.macsia.teatiers.repository.ClientDiagnosticRepository
import java.time.Clock
import java.time.Instant
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

/**
 * Stores opt-in client diagnostics (decision #111) after **re-enforcing the allowlist** the app is
 * already supposed to honor — defense in depth. It validates the report kind, truncates free-text
 * fields, strips control characters from short metadata, and keeps only well-formed numeric row
 * counts; the `Map<String, Int>` type already makes a non-numeric count impossible to bind. A daily
 * [purgeExpired] sweep enforces retention.
 *
 * The client IP is never passed in or stored (see the controller) — by design there is nothing here
 * that can re-identify a device or expose user content.
 */
@Service
class ClientDiagnosticsService(
    private val repository: ClientDiagnosticRepository,
    private val props: ClientDiagnosticsProperties,
    private val clock: Clock = Clock.systemUTC(),
) {

    /** Whether a report kind passes the server allowlist — checked by the controller before it spends
     *  a daily-budget token, so junk can't burn the cap. [record] re-checks as defense in depth. */
    fun isAllowedKind(kind: String): Boolean = kind in ALLOWED_KINDS

    @Transactional
    fun record(report: ClientDiagnosticReportDto) {
        if (report.kind !in ALLOWED_KINDS) {
            throw InvalidClientReportException("unknown report kind '${report.kind}'")
        }
        val entity = ClientDiagnostic(
            reportKind = report.kind,
            receivedAt = Instant.now(clock),
            appVersionCode = report.appVersionCode,
            appVersionName = shortField(report.appVersionName),
            androidSdk = report.androidSdk,
            deviceManufacturer = shortField(report.deviceManufacturer),
            deviceModel = shortField(report.deviceModel),
            buildType = shortField(report.buildType),
            stackTrace = report.stackTrace?.take(props.maxStackTraceChars),
            rowCounts = encodeRowCounts(report.rowCounts),
        )
        repository.save(entity)
    }

    /** Daily retention sweep (03:17 UTC; offset off the hour to dodge other cron spikes). */
    @Scheduled(cron = "0 17 3 * * *", zone = "UTC")
    @Transactional
    fun purgeExpired(): Int {
        val cutoff = Instant.now(clock).minus(java.time.Duration.ofDays(props.retentionDays))
        return repository.deleteOlderThan(cutoff)
    }

    /** Strip ISO control chars (no log/JSON injection, no stray newlines) and cap the length. */
    private fun shortField(value: String?): String? {
        if (value == null) return null
        val cleaned = value.filterNot { it.isISOControl() }.trim()
        if (cleaned.isEmpty()) return null
        return cleaned.take(props.maxFieldChars)
    }

    /**
     * Keep only sane keys mapping to integers, cap the count, and emit compact JSON (or null). Built
     * by hand rather than via a serializer: keys are constrained to [KEY_PATTERN] (no quote/backslash)
     * and values are `Int`, so the result is always well-formed JSON with nothing to escape.
     */
    private fun encodeRowCounts(counts: Map<String, Int>?): String? {
        if (counts.isNullOrEmpty()) return null
        val sanitized = counts.asSequence()
            .filter { (key, _) -> KEY_PATTERN.matches(key) }
            .sortedBy { it.key } // deterministic ordering
            .take(props.maxRowCountKeys)
            .toList()
        if (sanitized.isEmpty()) return null
        return sanitized.joinToString(separator = ",", prefix = "{", postfix = "}") { (k, v) -> "\"$k\":$v" }
    }

    private companion object {
        val ALLOWED_KINDS = setOf("crash", "room_migration_signal")
        val KEY_PATTERN = Regex("^[A-Za-z0-9_]{1,40}$")
    }
}
