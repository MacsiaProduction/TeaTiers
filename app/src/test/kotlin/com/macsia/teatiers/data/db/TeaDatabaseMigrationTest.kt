package com.macsia.teatiers.data.db

import android.app.Application
import android.database.sqlite.SQLiteConstraintException
import androidx.room.testing.MigrationTestHelper
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
 * sample (P1-1). When a real collection lands, the next bump adds a `Migration(7, N)` test here.
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
