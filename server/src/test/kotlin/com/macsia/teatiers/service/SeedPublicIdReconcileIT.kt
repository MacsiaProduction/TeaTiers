package com.macsia.teatiers.service

import com.macsia.teatiers.AbstractIntegrationTest
import com.macsia.teatiers.domain.Tea
import com.macsia.teatiers.domain.TeaName
import com.macsia.teatiers.domain.TeaType
import com.macsia.teatiers.repository.TeaLegacyIdMapRepository
import com.macsia.teatiers.repository.TeaRepository
import jakarta.persistence.EntityManager
import org.hibernate.Session
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.transaction.annotation.Transactional
import java.sql.Connection
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

/**
 * Decision #137-C1 / #139-R1: V11 reconciles existing catalog rows (which V7 gave random UUIDs) to their
 * FROZEN seed public_id, so production identity survives the V7 deploy and matches a clean rebuild. The
 * reconciliation logic is exercised directly against the test transaction's connection (exactly what the
 * V11 Flyway migration does in production), plus a check that Flyway actually discovered/applied V11.
 */
@Transactional
class SeedPublicIdReconcileIT : AbstractIntegrationTest() {

    @Autowired lateinit var entityManager: EntityManager

    @Autowired lateinit var teaRepository: TeaRepository

    @Autowired lateinit var legacyIdMap: TeaLegacyIdMapRepository

    @Autowired lateinit var catalogService: TeaCatalogService

    private val reconciler = SeedPublicIdReconciler()

    @Test
    fun `flyway discovered and applied the V11 java migration`() {
        val applied = entityManager
            .createNativeQuery("select count(*) from flyway_schema_history where version = '11'")
            .singleResult as Number
        assertEquals(1, applied.toInt(), "V11 java migration must be discovered + applied by Flyway")
    }

    @Test
    fun `existing random-uuid rows are reconciled to the frozen seed public id and the legacy map rebuilt`() {
        // Two real seed records: dedup_key -> frozen public_id.
        val frozen = reconciler.mapping().entries.take(2).associate { it.key to it.value }
        assertEquals(2, frozen.size, "seed must provide >=2 frozen public ids")

        // Simulate a post-V7 production state: the rows exist with RANDOM public_ids + a legacy-map entry.
        val ids = frozen.keys.associateWith { dedupKey ->
            val tea = Tea(type = TeaType.OTHER, source = "curated", dedupKey = dedupKey, verificationStatus = "verified")
            tea.publicId = UUID.randomUUID() // what V7's gen_random_uuid() assigns
            tea.addName(TeaName(locale = "en", name = dedupKey, isPrimary = true))
            val saved = teaRepository.saveAndFlush(tea)
            legacyIdMap.recordOnce(requireNotNull(saved.id), saved.publicId)
            requireNotNull(saved.id)
        }
        entityManager.flush()

        val report = runReconcile()
        entityManager.clear() // the raw-SQL UPDATE bypassed the persistence context

        assertEquals(2, report.teasReconciled, "both simulated production rows reconciled")

        ids.forEach { (dedupKey, id) ->
            val expected = frozen.getValue(dedupKey)
            val tea = teaRepository.findById(id).orElseThrow()
            assertEquals(expected, tea.publicId, "row '$dedupKey' now carries its frozen public_id")
            // numeric id still resolves (via the rebuilt legacy map) and the frozen UUID resolves the row
            assertEquals(expected, legacyIdMap.findById(id).orElseThrow().publicId)
            assertEquals(id, assertNotNull(catalogService.detailByLegacyId(id)).id)
            assertEquals(id, teaRepository.findByPublicId(expected)?.id)
        }

        // Idempotent: a second pass touches nothing.
        assertEquals(0, runReconcile().teasReconciled, "re-running reconciliation is a no-op")
    }

    private fun runReconcile(): SeedPublicIdReconciler.Report =
        entityManager.unwrap(Session::class.java)
            .doReturningWork { conn: Connection -> reconciler.reconcile(conn) }
}
