package com.macsia.teatiers.service

import com.macsia.teatiers.AbstractIntegrationTest
import com.macsia.teatiers.domain.ClientDiagnostic
import com.macsia.teatiers.repository.ClientDiagnosticRepository
import java.time.Duration
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import org.junit.jupiter.api.BeforeEach
import org.springframework.beans.factory.annotation.Autowired

/**
 * Integration test for client diagnostics (decision #111) against real Postgres: the entity ↔ V6
 * schema match is proven by Hibernate's `validate` on context start, and here we exercise the
 * allowlisted insert + the age-based retention purge (run through [ClientDiagnosticsService] so the
 * `@Modifying` delete executes in its transaction — the path the scheduled job uses).
 */
class ClientDiagnosticIT : AbstractIntegrationTest() {

    @Autowired
    lateinit var service: ClientDiagnosticsService

    @Autowired
    lateinit var repository: ClientDiagnosticRepository

    @BeforeEach
    fun clean() {
        // The container is shared across IT classes and these tests commit, so start from empty.
        repository.deleteAll()
    }

    @Test
    fun `persists an allowlisted report and the purge removes only aged rows`() {
        // A fresh crash + a 40-day-old migration signal; retention is 30 days.
        repository.save(
            ClientDiagnostic(reportKind = "crash", receivedAt = Instant.now(), stackTrace = "boom", appVersionCode = 7),
        )
        repository.save(
            ClientDiagnostic(
                reportKind = "room_migration_signal",
                receivedAt = Instant.now().minus(Duration.ofDays(40)),
                rowCounts = """{"boards_before":3,"boards_after":0}""",
            ),
        )
        assertEquals(2, repository.count())

        val removed = service.purgeExpired()

        assertEquals(1, removed, "only the 40-day-old row is past the 30-day retention")
        assertEquals(1, repository.count())
        assertEquals("crash", repository.findAll().single().reportKind)
    }
}
