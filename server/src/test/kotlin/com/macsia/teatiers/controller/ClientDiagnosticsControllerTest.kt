package com.macsia.teatiers.controller

import com.macsia.teatiers.dto.ClientDiagnosticReportDto
import com.macsia.teatiers.service.ClientDiagnosticsService
import com.macsia.teatiers.service.DailyBudget
import io.mockk.Runs
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.verify
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import org.springframework.http.HttpStatus

/**
 * Unit tests for the client-diagnostics receiver (decision #111). The controller returns a
 * `ResponseEntity` from plain properties + a service call, so it's exercised directly — no Spring
 * context. Covers the fail-closed gate, the constant-time token check, and the happy path.
 */
class ClientDiagnosticsControllerTest {

    private val service = mockk<ClientDiagnosticsService>()
    private val dailyBudget = mockk<DailyBudget>()

    private fun controller(enabled: Boolean = true, token: String = "s3cret", underBudget: Boolean = true) =
        ClientDiagnosticsController(
            ClientDiagnosticsProperties(enabled = enabled, token = token),
            service.also { every { it.isAllowedKind(any()) } returns true },
            dailyBudget.also { every { it.tryAcquire() } returns underBudget },
        )

    private val crash = ClientDiagnosticReportDto(kind = "crash", stackTrace = "boom")

    @Test
    fun `503 when diagnostics are disabled`() {
        val response = controller(enabled = false).report(token = "s3cret", report = crash)

        assertEquals(HttpStatus.SERVICE_UNAVAILABLE, response.statusCode)
        verify(exactly = 0) { service.record(any()) }
    }

    @Test
    fun `503 when enabled but no token is configured (fails closed)`() {
        val response = controller(enabled = true, token = "").report(token = "anything", report = crash)

        assertEquals(HttpStatus.SERVICE_UNAVAILABLE, response.statusCode)
        verify(exactly = 0) { service.record(any()) }
    }

    @Test
    fun `401 when the token is missing`() {
        val response = controller().report(token = null, report = crash)

        assertEquals(HttpStatus.UNAUTHORIZED, response.statusCode)
        verify(exactly = 0) { service.record(any()) }
    }

    @Test
    fun `401 when the token is wrong`() {
        val response = controller().report(token = "nope", report = crash)

        assertEquals(HttpStatus.UNAUTHORIZED, response.statusCode)
        verify(exactly = 0) { service.record(any()) }
    }

    @Test
    fun `202 and records the report when enabled with a matching token`() {
        every { service.record(crash) } just Runs

        val response = controller().report(token = "s3cret", report = crash)

        assertEquals(HttpStatus.ACCEPTED, response.statusCode)
        verify(exactly = 1) { service.record(crash) }
    }

    @Test
    fun `429 and does not record once the global daily cap is hit`() {
        val response = controller(underBudget = false).report(token = "s3cret", report = crash)

        assertEquals(HttpStatus.TOO_MANY_REQUESTS, response.statusCode)
        verify(exactly = 0) { service.record(any()) }
    }

    @Test
    fun `422 rejects an unknown kind BEFORE spending the daily budget`() {
        val controller = controller()
        every { service.isAllowedKind("weird") } returns false
        val report = ClientDiagnosticReportDto(kind = "weird", stackTrace = "boom")

        assertFailsWith<InvalidClientReportException> { controller.report(token = "s3cret", report = report) }

        // Crucially, the junk report never consumed a budget token or hit the table.
        verify(exactly = 0) { dailyBudget.tryAcquire() }
        verify(exactly = 0) { service.record(any()) }
    }
}
