package com.macsia.teatiers.service

import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import java.sql.Connection
import java.util.UUID

/**
 * Reconciles existing catalog rows to the FROZEN public_id committed in the seed (decision #137-C1 /
 * #139-R1). Run once by the V11 Flyway migration on the first deploy that introduces public_id.
 *
 * Why this exists: V7 added `public_id UUID DEFAULT gen_random_uuid()`, so the existing production rows
 * receive RANDOM UUIDs; the seeder only applies the frozen UUID when it INSERTS a missing dedup_key, so it
 * skips those rows. Without this step a later rebuild-from-seed would produce DIFFERENT (frozen) UUIDs and
 * orphan every installed client. This matches each existing row to its seed record by the stable
 * `dedup_key` and rewrites its public_id (and the legacy-id map) to the frozen value.
 *
 * Idempotent (only touches rows whose public_id differs), and a no-op on a blank database (no rows yet --
 * the seeder then inserts with the frozen UUID directly). It NEVER invents a UUID: a row that matches no
 * seed record keeps its current id, and a collision with a different semantic row fails the migration.
 *
 * Plain class (not a Spring bean) so the Flyway Java migration can construct it with just a JDBC
 * Connection; the integration test calls [reconcile] directly against the test transaction's connection.
 */
class SeedPublicIdReconciler(private val seedResource: String = "seed/catalog-seed.json") {

    private val mapper = jacksonObjectMapper()
        .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)

    data class Report(val seedRecords: Int, val teasReconciled: Int, val legacyRowsReconciled: Int)

    /** Frozen `dedup_key -> public_id` from the committed seed (entries without a publicId are skipped). */
    fun mapping(): Map<String, UUID> {
        val stream = javaClass.classLoader.getResourceAsStream(seedResource)
            ?: error("seed resource '$seedResource' not found on the classpath")
        val bundle = stream.use { mapper.readValue(it, SeedBundle::class.java) }
        return bundle.teas.mapNotNull { tea -> tea.publicId?.let { DedupKeys.ofSeed(tea) to it } }.toMap()
    }

    /**
     * Reconcile every existing tea/legacy-map row to its frozen public_id. Drops the public_id FKs, rewrites
     * the values, then recreates the FKs -- all inside the caller's transaction (Flyway's migration tx, or
     * the test tx). Returns counts for the migration log / test assertions.
     */
    fun reconcile(connection: Connection): Report {
        val map = mapping()
        if (map.isEmpty()) return Report(0, 0, 0)

        failOnCollision(connection, map)

        // tea_legacy_id_map.public_id and tea.merged_into_public_id both reference tea.public_id; drop them
        // so the unique public_id can be reassigned atomically, then recreate (identical definitions). The
        // legacy-map FK was created inline in V7 (server-assigned name), so discover it rather than guess.
        val legacyFk = legacyMapPublicIdFkName(connection)
        if (legacyFk != null) exec(connection, """ALTER TABLE tea_legacy_id_map DROP CONSTRAINT "$legacyFk"""")
        exec(connection, "ALTER TABLE tea DROP CONSTRAINT IF EXISTS tea_merged_into_fk")

        var teas = 0
        connection.prepareStatement("UPDATE tea SET public_id = ? WHERE dedup_key = ? AND public_id <> ?").use { ps ->
            for ((dedupKey, publicId) in map) {
                ps.setObject(1, publicId)
                ps.setString(2, dedupKey)
                ps.setObject(3, publicId)
                teas += ps.executeUpdate()
            }
        }
        // Keep the legacy numeric id -> public_id map in lockstep (legacy_id stays = tea.id).
        val legacy = exec(
            connection,
            "UPDATE tea_legacy_id_map m SET public_id = t.public_id FROM tea t " +
                "WHERE m.legacy_id = t.id AND m.public_id <> t.public_id",
        )

        exec(
            connection,
            "ALTER TABLE tea_legacy_id_map ADD CONSTRAINT tea_legacy_id_map_public_id_fkey " +
                "FOREIGN KEY (public_id) REFERENCES tea (public_id)",
        )
        exec(
            connection,
            "ALTER TABLE tea ADD CONSTRAINT tea_merged_into_fk " +
                "FOREIGN KEY (merged_into_public_id) REFERENCES tea (public_id)",
        )
        return Report(map.size, teas, legacy)
    }

    /**
     * Fail closed if a frozen public_id already belongs to a DIFFERENT semantic row (a different dedup_key).
     * In practice impossible (V7's random UUIDs never collide with the frozen ones), but reassigning over it
     * would corrupt identity, so we refuse rather than fall back to a generated UUID.
     */
    private fun failOnCollision(connection: Connection, map: Map<String, UUID>) {
        val byPublicId = map.entries.associate { (k, v) -> v to k }
        connection.prepareStatement("SELECT dedup_key, public_id FROM tea").use { ps ->
            ps.executeQuery().use { rs ->
                while (rs.next()) {
                    val dedupKey = rs.getString("dedup_key")
                    val publicId = rs.getObject("public_id", UUID::class.java)
                    val ownerOfThisUuid = byPublicId[publicId]
                    if (ownerOfThisUuid != null && ownerOfThisUuid != dedupKey) {
                        throw SeedPublicIdReconcileException(
                            "frozen public_id $publicId belongs to seed '$ownerOfThisUuid' but row '$dedupKey' already holds it",
                        )
                    }
                }
            }
        }
    }

    /** The (server-assigned) name of the sole FK on `tea_legacy_id_map.public_id`, or null if absent. */
    private fun legacyMapPublicIdFkName(connection: Connection): String? =
        connection.prepareStatement(
            "SELECT conname FROM pg_constraint WHERE conrelid = 'tea_legacy_id_map'::regclass AND contype = 'f' LIMIT 1",
        ).use { ps -> ps.executeQuery().use { rs -> if (rs.next()) rs.getString(1) else null } }

    private fun exec(connection: Connection, sql: String): Int = connection.createStatement().use { it.executeUpdate(sql) }
}

/** The seed public-id reconciliation found an unsafe state (decision #139-R1) -- the migration must fail. */
class SeedPublicIdReconcileException(message: String) : RuntimeException(message)
