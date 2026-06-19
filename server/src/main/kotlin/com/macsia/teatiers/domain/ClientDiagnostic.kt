package com.macsia.teatiers.domain

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.Instant

/**
 * One opt-in, GMS-free client diagnostic report (decision #111): a `crash` or a
 * `room_migration_signal` (a silent local-data wipe the out-of-Room sentinel detected). Holds only
 * non-identifying, allowlisted fields — app/build/device metadata, a stack trace, and numeric row
 * counts. There is deliberately **no** client IP, account id, tea/board name, note, photo URI, coord,
 * or OCR text, so a row cannot re-identify the device or expose user content.
 *
 * Written by [com.macsia.teatiers.service.ClientDiagnosticsService] after it re-enforces the
 * allowlist; aged rows are removed by the scheduled retention purge.
 */
@Entity
@Table(name = "client_diagnostic")
class ClientDiagnostic(
    @Column(name = "report_kind", nullable = false)
    val reportKind: String,

    @Column(name = "received_at", nullable = false)
    val receivedAt: Instant,

    @Column(name = "app_version_code")
    val appVersionCode: Int? = null,

    @Column(name = "app_version_name")
    val appVersionName: String? = null,

    @Column(name = "android_sdk")
    val androidSdk: Int? = null,

    @Column(name = "device_manufacturer")
    val deviceManufacturer: String? = null,

    @Column(name = "device_model")
    val deviceModel: String? = null,

    @Column(name = "build_type")
    val buildType: String? = null,

    @Column(name = "stack_trace")
    val stackTrace: String? = null,

    @Column(name = "row_counts")
    val rowCounts: String? = null,

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long? = null,
)
