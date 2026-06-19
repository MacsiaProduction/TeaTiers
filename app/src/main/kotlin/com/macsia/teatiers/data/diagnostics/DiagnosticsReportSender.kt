package com.macsia.teatiers.data.diagnostics

import android.content.Context
import org.acra.ReportField
import org.acra.config.CoreConfiguration
import org.acra.data.CrashReportData
import org.acra.sender.ReportSender
import org.acra.sender.ReportSenderFactory

/**
 * ACRA sender that maps a captured crash down to our allowlisted DTO and posts it (decision #111).
 * Only the **stack trace** is taken from ACRA's capture; the device/app/build fields are re-read from
 * stable [android.os.Build]/[com.macsia.teatiers.BuildConfig] in [DiagnosticsWire], so nothing ACRA
 * collected beyond the stack trace can leak. This replaces ACRA's built-in HttpSender precisely so we
 * control, field by field, what leaves the device.
 */
class DiagnosticsReportSender : ReportSender {
    override fun send(context: Context, errorContent: CrashReportData) {
        val stackTrace = errorContent.getString(ReportField.STACK_TRACE)
        DiagnosticsWire.post(
            DiagnosticsWire.report(kind = ClientDiagnosticReportDto.KIND_CRASH, stackTrace = stackTrace),
        )
    }
}

/** Registers [DiagnosticsReportSender] with ACRA (referenced from the ACRA config). */
class DiagnosticsReportSenderFactory : ReportSenderFactory {
    override fun create(context: Context, config: CoreConfiguration): ReportSender = DiagnosticsReportSender()

    override fun enabled(config: CoreConfiguration): Boolean = true
}
