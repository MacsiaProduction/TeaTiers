package com.macsia.teatiers.controller

import com.macsia.teatiers.dto.ClientDiagnosticReportDto
import com.macsia.teatiers.service.ClientDiagnosticsService
import com.macsia.teatiers.service.DiagnosticsDailyBudget
import jakarta.validation.Valid
import java.security.MessageDigest
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController

/**
 * Opt-in client-diagnostics receiver (decision #111): `POST /api/v1/client-diagnostics`. The
 * sideloaded, GMS-free app's ACRA reporter posts a strictly-allowlisted crash / silent-wipe report
 * here so a destructive Room migration can't wipe local-first data unseen.
 *
 * - **Fails closed**: until the operator sets `enabled=true` AND a non-blank token, it replies `503`.
 * - **Shared anti-spam token** in the `X-Diagnostics-Token` header, compared in constant time. The
 *   token ships in the APK and is NOT a security boundary (see [ClientDiagnosticsProperties]).
 * - **Global daily cap** ([DiagnosticsDailyBudget]) bounds total inserts per UTC day → `429`, so the
 *   extractable token can't be used to flood the table and fill disk (review finding). Global, not
 *   per-IP, so the endpoint still never reads the client IP.
 * - **No-PII**: it never reads or stores the client IP. The body is re-sanitized + allowlisted by
 *   [ClientDiagnosticsService]; an unknown `kind` is rejected `422`.
 */
@RestController
@RequestMapping("/api/v1/client-diagnostics")
class ClientDiagnosticsController(
    private val props: ClientDiagnosticsProperties,
    private val service: ClientDiagnosticsService,
    private val dailyBudget: DiagnosticsDailyBudget,
) {

    @PostMapping
    fun report(
        @RequestHeader(name = "X-Diagnostics-Token", required = false) token: String?,
        @Valid @RequestBody report: ClientDiagnosticReportDto,
    ): ResponseEntity<Void> {
        if (!props.enabled || props.token.isBlank()) {
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).build()
        }
        if (!tokenMatches(token)) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build()
        }
        if (!dailyBudget.tryAcquire()) {
            return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS).build()
        }
        service.record(report)
        return ResponseEntity.accepted().build()
    }

    /** Constant-time comparison so a wrong token can't be brute-forced by timing. */
    private fun tokenMatches(provided: String?): Boolean {
        if (provided.isNullOrEmpty()) return false
        val a = provided.toByteArray(Charsets.UTF_8)
        val b = props.token.toByteArray(Charsets.UTF_8)
        return MessageDigest.isEqual(a, b)
    }
}

/** A diagnostic report failed the server-side allowlist (e.g. an unknown `kind`). Rendered `422`. */
@ResponseStatus(HttpStatus.UNPROCESSABLE_ENTITY)
class InvalidClientReportException(message: String) : RuntimeException(message)
