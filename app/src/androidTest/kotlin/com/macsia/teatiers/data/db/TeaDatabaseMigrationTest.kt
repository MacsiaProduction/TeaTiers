package com.macsia.teatiers.data.db

import androidx.room.testing.MigrationTestHelper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * On-device Room migration tests (review P1-4 / decision #130). v6 is the PUBLIC baseline — release
 * builds no longer destructively migrate (AppModule), so every future schema bump MUST ship an
 * explicit `Migration(6, N)` proven lossless here against the committed schema JSON.
 *
 * MigrationTestHelper reads the exported schemas from the androidTest assets (wired in build.gradle.kts
 * via `sourceSets["androidTest"].assets.srcDir("schemas")`). This runs on an emulator in CI
 * (`connectedDebugAndroidTest`), NOT in the JVM unit-test suite.
 *
 * Adding a v7 (the planned tea/sample split, decision #132): create at 6, then
 * `runMigrationsAndValidate(TEST_DB, 7, true, MIGRATION_6_7)` + a repository read/write smoke after.
 */
@RunWith(AndroidJUnit4::class)
class TeaDatabaseMigrationTest {

    @get:Rule
    val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        TeaDatabase::class.java,
    )

    @Test
    fun v6_baseline_createsAndValidates() {
        // No post-v6 migration exists yet, so this proves the committed 6.json is valid and the DB
        // opens + validates at v6 — the foundation every future Migration(6, N) test extends.
        helper.createDatabase(TEST_DB, 6).close()
        helper.runMigrationsAndValidate(TEST_DB, 6, true).close()
    }

    private companion object {
        const val TEST_DB = "migration-test.db"
    }
}
