package com.macsia.teatiers.repository

import com.macsia.teatiers.domain.ClientDiagnostic
import java.time.Instant
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param

/** Persistence for opt-in client diagnostics (decision #111). Write + age-based purge only. */
interface ClientDiagnosticRepository : JpaRepository<ClientDiagnostic, Long> {

    /** Retention sweep: drop every report older than [cutoff]. Returns how many rows were removed. */
    @Modifying
    @Query("DELETE FROM ClientDiagnostic d WHERE d.receivedAt < :cutoff")
    fun deleteOlderThan(@Param("cutoff") cutoff: Instant): Int
}
