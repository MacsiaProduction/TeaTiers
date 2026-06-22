package db.migration

import com.macsia.teatiers.service.SeedPublicIdReconciler
import org.flywaydb.core.api.migration.BaseJavaMigration
import org.flywaydb.core.api.migration.Context
import org.slf4j.LoggerFactory

/**
 * Reconcile existing catalog rows to their FROZEN seed public_id (decision #137-C1 / #139-R1).
 *
 * A Java (not SQL) migration on purpose: the join key is `tea.dedup_key`, which can contain punctuation
 * (e.g. an apostrophe), so a parameterized statement is safer than a hand-escaped `VALUES` list, and the
 * dedup key + frozen UUID come from the committed seed via the SAME [com.macsia.teatiers.service.DedupKeys]
 * logic the seeder uses (no drift). The work lives in [SeedPublicIdReconciler] so it is unit/integration
 * testable; this class only adapts it to Flyway. No-op on a blank database; idempotent on re-run.
 *
 * Discovered by Flyway's scan of `classpath:db/migration` (this package). Version 11, after V10.
 */
@Suppress("ClassNaming")
class V11__reconcile_seed_public_ids : BaseJavaMigration() {

    private val log = LoggerFactory.getLogger(javaClass)

    override fun migrate(context: Context) {
        val report = SeedPublicIdReconciler().reconcile(context.connection)
        log.info(
            "V11 seed public_id reconcile: {} seed records, {} teas reconciled, {} legacy-map rows reconciled",
            report.seedRecords,
            report.teasReconciled,
            report.legacyRowsReconciled,
        )
    }
}
