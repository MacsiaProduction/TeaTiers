package com.macsia.teatiers.data.diagnostics

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * Guards the one-shot reseed flag that makes the v7 destructive reset repopulate mock data (#132):
 * Room's `onDestructiveMigration` wipes the DB but not the `onboarding_seeded` marker, so the flag is
 * what re-arms the first-run seed. A failure to CLEAR it would reseed over the user's data on every
 * launch — hence the explicit one-shot assertion. Runs on the JVM under Robolectric (SharedPreferences).
 */
@RunWith(AndroidJUnit4::class)
@Config(application = Application::class, sdk = [35])
class DiagnosticsPreferencesTest {

    private val context = ApplicationProvider.getApplicationContext<Context>()

    @Test
    fun destructiveMigration_armsAOneShotReseedFlag() {
        val prefs = DiagnosticsPreferences(context)
        assertFalse("clean install: nothing pending", prefs.consumeReseedPending())

        DiagnosticsPreferences.markDestructiveMigration(context)
        assertTrue("a wipe arms the reseed", prefs.consumeReseedPending())
        assertFalse("one-shot: cleared after the first read", prefs.consumeReseedPending())
    }
}
