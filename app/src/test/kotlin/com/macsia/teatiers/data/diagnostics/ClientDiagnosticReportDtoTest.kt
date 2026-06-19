package com.macsia.teatiers.data.diagnostics

import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * The wire contract must match the server's DTO field names (decision #111) and must OMIT null fields
 * so a crash report never carries empty migration counts (and vice-versa).
 */
class ClientDiagnosticReportDtoTest {

    // Mirrors DiagnosticsWire's encoder: omit nulls + defaults.
    private val json = Json { explicitNulls = false; encodeDefaults = false }

    @Test
    fun `a crash serializes only the present fields, with server field names`() {
        val report = ClientDiagnosticReportDto(
            kind = ClientDiagnosticReportDto.KIND_CRASH,
            appVersionCode = 7,
            stackTrace = "boom",
        )

        assertEquals("""{"kind":"crash","appVersionCode":7,"stackTrace":"boom"}""", json.encodeToString(report))
    }

    @Test
    fun `a migration signal serializes numeric row counts and no stack trace`() {
        val report = ClientDiagnosticReportDto(
            kind = ClientDiagnosticReportDto.KIND_MIGRATION_SIGNAL,
            rowCounts = mapOf("boards_before" to 3, "boards_after" to 0),
        )

        assertEquals(
            """{"kind":"room_migration_signal","rowCounts":{"boards_before":3,"boards_after":0}}""",
            json.encodeToString(report),
        )
    }
}
