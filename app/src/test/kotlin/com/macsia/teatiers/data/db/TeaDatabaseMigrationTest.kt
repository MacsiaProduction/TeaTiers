package com.macsia.teatiers.data.db

import android.app.Application
import android.database.sqlite.SQLiteConstraintException
import androidx.room.testing.MigrationTestHelper
import com.macsia.teatiers.di.AppModule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * Room schema/migration tests, run on the JVM under Robolectric (decision #130 / review P1-4).
 * Actions has no emulator and there's no local device, so these run in the unit-test suite
 * (`./gradlew testDebugUnitTest`, the `check` gate).
 *
 * **v7 (tea/sample split, #132) is a DESTRUCTIVE reset, not a migration** (owner decision 2026-06-23:
 * the shipped v0.1.0 collection is mock data), so there is no `Migration(6,7)` to test — this asserts
 * the v7 schema is internally consistent (matches the committed `7.json`) and that the FK invariants
 * the split relies on actually hold on Robolectric's SQLite: CASCADE delete of a sample's children,
 * rejection of orphan links, and `ON DELETE SET NULL` so evicting a catalog ref never deletes a
 * sample (P1-1).
 *
 * **v7→v8 (catalog dual-key, #137-C2)** is the FIRST real `Migration` off the durable baseline:
 * [v7_to_v8_addsCatalogPublicId_lossless] seeds a v7 row, migrates, and proves no data is dropped and
 * the new column lands nullable (lazily backfilled at runtime, not by the migration).
 *
 * MigrationTestHelper reads the exported schemas from the merged debug assets (wired in
 * build.gradle.kts via `sourceSets["debug"].assets.srcDir("schemas")` + `unitTests.isIncludeAndroidResources
 * = true`). AndroidJUnit4 delegates to Robolectric on the JVM; junit-vintage-engine runs it on the
 * JUnit 5 platform. SDK is pinned to 35 (Robolectric 4.15.1's max on JDK 17); the plain Application
 * skips Hilt — a schema test needs neither DI nor a specific API level.
 */
@RunWith(AndroidJUnit4::class)
@Config(application = Application::class, sdk = [35])
class TeaDatabaseMigrationTest {

    @get:Rule
    val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        TeaDatabase::class.java,
    )

    @Test
    fun v7_schema_createsAndValidates() {
        // Proves the committed 7.json is valid and the DB opens + validates at v7 — the baseline every
        // future Migration(7, N) test extends.
        helper.createDatabase(TEST_DB, 7).close()
        helper.runMigrationsAndValidate(TEST_DB, 7, true).close()
    }

    @Test
    fun v7_isTheDurableBaseline_neverDestructivelyResetFromV7Onward() {
        // Data-loss guard (AND): only the pre-launch mock schemas v1..v6 may be destructively reset on
        // upgrade. v7 (the public baseline) and anything newer must require an explicit Migration —
        // adding 7 here, or reverting to a blanket fallbackToDestructiveMigration, would silently wipe
        // a real user's collection on the next schema bump. This pins the AppModule policy.
        val resetFrom = AppModule.DESTRUCTIVE_RESET_FROM.toList()
        assertEquals("only pre-v7 versions are destructively reset", listOf(1, 2, 3, 4, 5, 6), resetFrom)
        assertEquals("v7 is never destructively reset", false, resetFrom.contains(7))
    }

    @Test
    fun v7_to_v8_addsCatalogPublicId_lossless() {
        // First migration off the v7 baseline (#137-C2). Seed a fully-populated catalog_refs row at v7,
        // run Migration(7,8), and prove it's lossless: every column round-trips and the new
        // catalogPublicId lands NULL (backfill is lazy at runtime — the migration never touches data).
        helper.createDatabase(TEST_DB, 7).use { db ->
            db.execSQL(
                "INSERT INTO catalog_refs (id, type, brand, shortBlurb, fetchedAtEpochMs) " +
                    "VALUES (42, 'OOLONG', 'Acme', 'a roasted oolong', 123)",
            )
        }

        val db = helper.runMigrationsAndValidate(TEST_DB, 8, true, MIGRATION_7_8)
        db.query(
            "SELECT type, brand, shortBlurb, fetchedAtEpochMs, catalogPublicId FROM catalog_refs WHERE id = 42",
        ).use { c ->
            assertEquals("the v7 ref survives the migration", 1, c.count)
            c.moveToFirst()
            assertEquals("type preserved", "OOLONG", c.getString(0))
            assertEquals("brand preserved", "Acme", c.getString(1))
            assertEquals("blurb preserved", "a roasted oolong", c.getString(2))
            assertEquals("fetchedAt preserved", 123L, c.getLong(3))
            assertEquals("new column backfills lazily, so it's NULL right after migrate", true, c.isNull(4))
        }
        db.close()
    }

    @Test
    fun v7_enforcesForeignKeys_cascadeAndSetNull() {
        // De-risks the JVM gate (tea-sample-split-v7.md Q8: "Robolectric can mock FK behavior
        // incorrectly"). Proves Robolectric's SQLite enforces the exact FK semantics v7 relies on.
        val db = helper.createDatabase(TEST_DB, 7)
        // MigrationTestHelper opens with foreign_keys OFF (migration mode); turn it on like Room does.
        db.execSQL("PRAGMA foreign_keys = ON")
        db.execSQL("INSERT INTO boards (id, name, position) VALUES ('b1', 'Board', 0)")
        db.execSQL("INSERT INTO catalog_refs (id, type, fetchedAtEpochMs) VALUES (42, 'OOLONG', 0)")
        db.execSQL("INSERT INTO tea_samples (id, type, enrichmentState, catalogTeaId) VALUES ('t1', 'OOLONG', 'NONE', 42)")
        db.execSQL("INSERT INTO placements (id, boardId, teaId, tierId, position) VALUES ('p1', 'b1', 't1', NULL, 0)")

        // SET NULL: evicting the catalog ref must NOT delete the sample — it just unlinks it (P1-1).
        db.execSQL("DELETE FROM catalog_refs WHERE id = 42")
        db.query("SELECT catalogTeaId FROM tea_samples WHERE id = 't1'").use { c ->
            c.moveToFirst()
            assertEquals("sample survives ref eviction", 1, c.count)
            assertEquals("catalogTeaId is set to NULL", true, c.isNull(0))
        }

        // CASCADE: deleting the parent sample removes its placement.
        db.execSQL("DELETE FROM tea_samples WHERE id = 't1'")
        db.query("SELECT COUNT(*) FROM placements").use { c ->
            c.moveToFirst()
            assertEquals("placement cascade-deletes with its sample", 0, c.getInt(0))
        }

        // ENFORCEMENT: an orphan placement (no such sample) must be rejected, not silently accepted.
        try {
            db.execSQL("INSERT INTO placements (id, boardId, teaId, tierId, position) VALUES ('p2', 'b1', 'ghost', NULL, 0)")
            fail("expected a foreign-key constraint violation for the orphan placement")
        } catch (_: SQLiteConstraintException) {
            // expected — Robolectric's SQLite enforces the FK
        }
        db.close()
    }

    private companion object {
        const val TEST_DB = "schema-test.db"
    }
}
