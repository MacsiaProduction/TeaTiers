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
    /**
     * True once [markSeeded] has run; survives restarts and an emptied Room DB. A pure read (UX-P2-16)
     * — unlike the prior version, this does NOT also consume the reseed-pending marker, so calling it
     * more than once can no longer silently change the answer. Use [consumeReseedPending] at the one
     * call site that actually decides whether to reseed.
     */
    suspend fun isSeeded(): Boolean

    /**
     * One-shot: true if a destructive schema reset (the v7 #132 reset, or any future
     * `fallbackToDestructiveMigration`) left the reseed flag set, consuming it so it fires exactly
     * once. A destructive reset wipes Room but NOT the [isSeeded] marker, which would otherwise leave
     * an upgraded install empty forever — call this alongside [isSeeded] where the reseed decision is
     * actually made, not from any other read path.
     */
    suspend fun consumeReseedPending(): Boolean

    suspend fun markSeeded()
}

@Singleton
class DataStoreOnboardingState @Inject constructor(
    private val dataStore: DataStore<Preferences>,
    private val diagnostics: DiagnosticsPreferences,
) : OnboardingState {
    override suspend fun isSeeded(): Boolean = dataStore.data.first()[KEY] ?: false

    override suspend fun consumeReseedPending(): Boolean = diagnostics.consumeReseedPending()

    override suspend fun markSeeded() {
        dataStore.edit { it[KEY] = true }
    }

    private companion object {
        val KEY = booleanPreferencesKey("onboarding_seeded")
    }
}
