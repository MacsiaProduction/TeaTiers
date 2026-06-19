package com.macsia.teatiers.service

import com.macsia.teatiers.controller.ClientDiagnosticsProperties
import com.macsia.teatiers.controller.InvalidClientReportException
import com.macsia.teatiers.domain.ClientDiagnostic
import com.macsia.teatiers.dto.ClientDiagnosticReportDto
import com.macsia.teatiers.repository.ClientDiagnosticRepository
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import org.junit.jupiter.api.BeforeEach
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

/** Pure-logic tests for the server-side re-sanitization + retention math (decision #111). */
class ClientDiagnosticsServiceTest {

    private val repository = mockk<ClientDiagnosticRepository>(relaxed = true)
    private val fixedNow = Instant.parse("2026-06-19T00:00:00Z")
    private val clock = Clock.fixed(fixedNow, ZoneOffset.UTC)
    private val props = ClientDiagnosticsProperties(
        retentionDays = 30,
        maxStackTraceChars = 50,
        maxFieldChars = 10,
        maxRowCountKeys = 2,
    )
    private val service = ClientDiagnosticsService(repository, props, clock)

    @BeforeEach
    fun stubSave() {
        // The generic save(S): S on a relaxed mock returns a placeholder that can't cast to the
        // entity type; echo the argument so the (ignored) return is well-typed.
        every { repository.save(any<ClientDiagnostic>()) } answers { firstArg() }
    }

    private fun captureSaved(): ClientDiagnostic {
        val slot = slot<ClientDiagnostic>()
        verify { repository.save(capture(slot)) }
        return slot.captured
    }

    @Test
    fun `an unknown report kind is rejected and never stored`() {
        assertFailsWith<InvalidClientReportException> {
            service.record(ClientDiagnosticReportDto(kind = "exfiltrate"))
        }
        verify(exactly = 0) { repository.save(any()) }
    }

    @Test
    fun `a crash truncates the stack trace and stamps the receive time`() {
        service.record(
            ClientDiagnosticReportDto(kind = "crash", appVersionCode = 7, stackTrace = "E".repeat(200)),
        )

        val saved = captureSaved()
        assertEquals("crash", saved.reportKind)
        assertEquals(fixedNow, saved.receivedAt)
        assertEquals(7, saved.appVersionCode)
        assertEquals(50, saved.stackTrace!!.length) // truncated to maxStackTraceChars
    }

    @Test
    fun `short metadata fields drop control chars and are length-capped`() {
        service.record(
            ClientDiagnosticReportDto(
                kind = "crash",
                // a BEL control char + trailing newline are stripped -> "Google"
                deviceManufacturer = "Google\n",
                deviceModel = "PixelPhoneXXL", // 13 chars -> capped to 10 = "PixelPhone"
            ),
        )

        val saved = captureSaved()
        assertEquals("Google", saved.deviceManufacturer)
        assertEquals("PixelPhone", saved.deviceModel)
    }

    @Test
    fun `row counts keep only well-formed keys, cap the count, and serialize numeric-only`() {
        service.record(
            ClientDiagnosticReportDto(
                kind = "room_migration_signal",
                // "bad key" has a space -> dropped; 3 valid keys, maxRowCountKeys=2 -> first 2 sorted.
                rowCounts = mapOf("teas" to 5, "boards" to 3, "notes" to 9, "bad key" to 1),
            ),
        )

        // sorted keys: boards, notes, teas -> take 2 -> boards, notes
        assertEquals("""{"boards":3,"notes":9}""", captureSaved().rowCounts)
    }

    @Test
    fun `empty or all-invalid row counts serialize to null`() {
        service.record(
            ClientDiagnosticReportDto(kind = "room_migration_signal", rowCounts = mapOf("bad key" to 1)),
        )

        assertNull(captureSaved().rowCounts)
    }

    @Test
    fun `purge deletes everything older than now minus retention`() {
        val cutoff = slot<Instant>()
        every { repository.deleteOlderThan(capture(cutoff)) } returns 3

        val removed = service.purgeExpired()

        assertEquals(3, removed)
        assertEquals(fixedNow.minus(Duration.ofDays(30)), cutoff.captured)
    }
}
