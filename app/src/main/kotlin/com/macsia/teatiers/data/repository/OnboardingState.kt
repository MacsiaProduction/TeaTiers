package com.macsia.teatiers.data.repository

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import com.macsia.teatiers.data.diagnostics.DiagnosticsPreferences
import kotlinx.coroutines.flow.first
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Persistent "the app has done its first-run sample seed" marker (review §5). Kept OUT of Room — and
 * out of the user's catalog data — so that deleting the LAST board (or restoring an empty backup)
 * leaves an empty DB without the next launch reseeding sample boards over the user's deliberate empty
 * state. A full app-data clear wipes this too, which correctly re-arms the first-run seed.
 */
interface OnboardingState {
    /** True once [markSeeded] has run; survives restarts and an emptied Room DB. */
    suspend fun isSeeded(): Boolean
    suspend fun markSeeded()
}

@Singleton
class DataStoreOnboardingState @Inject constructor(
    private val dataStore: DataStore<Preferences>,
    private val diagnostics: DiagnosticsPreferences,
) : OnboardingState {
    override suspend fun isSeeded(): Boolean {
        // A destructive schema reset (the v7 #132 reset, or any future `fallbackToDestructiveMigration`)
        // wipes Room but NOT this marker, which would leave an upgraded install empty. The one-shot
        // reseed flag set by Room's onDestructiveMigration callback re-arms the first-run seed exactly
        // once so the sample boards repopulate.
        if (diagnostics.consumeReseedPending()) return false
        return dataStore.data.first()[KEY] ?: false
    }

    override suspend fun markSeeded() {
        dataStore.edit { it[KEY] = true }
    }

    private companion object {
        val KEY = booleanPreferencesKey("onboarding_seeded")
    }
}
