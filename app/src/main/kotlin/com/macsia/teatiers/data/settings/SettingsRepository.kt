package com.macsia.teatiers.data.settings

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import com.macsia.teatiers.domain.model.AppSettings
import com.macsia.teatiers.domain.model.ThemeMode
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Reads/writes the appearance preferences (#28/#36). A corrupt-read falls back to defaults instead
 * of crashing the UI; an unknown stored theme value also degrades to [ThemeMode.SYSTEM] so a future
 * enum rename never bricks startup.
 */
@Singleton
class SettingsRepository @Inject constructor(
    private val dataStore: DataStore<Preferences>,
) {
    val settings: Flow<AppSettings> = dataStore.data
        .catch { error ->
            if (error is IOException) emit(emptyPreferences()) else throw error
        }
        .map { prefs ->
            AppSettings(
                themeMode = prefs[KEY_THEME_MODE]?.toThemeModeOrDefault() ?: ThemeMode.SYSTEM,
                dynamicColor = prefs[KEY_DYNAMIC_COLOR] ?: false,
            )
        }

    /**
     * Whether the user has dismissed the first-run intro on the boards screen. Defaults to false
     * (show it) and flips once on dismiss; a corrupt read degrades to "not dismissed" so the worst
     * case is showing the (dismissible) card again rather than crashing.
     */
    val introDismissed: Flow<Boolean> = dataStore.data
        .catch { error ->
            if (error is IOException) emit(emptyPreferences()) else throw error
        }
        .map { prefs -> prefs[KEY_INTRO_DISMISSED] ?: false }

    suspend fun setThemeMode(mode: ThemeMode) {
        dataStore.edit { it[KEY_THEME_MODE] = mode.name }
    }

    suspend fun setDynamicColor(enabled: Boolean) {
        dataStore.edit { it[KEY_DYNAMIC_COLOR] = enabled }
    }

    suspend fun setIntroDismissed() {
        dataStore.edit { it[KEY_INTRO_DISMISSED] = true }
    }

    private fun String.toThemeModeOrDefault(): ThemeMode =
        runCatching { ThemeMode.valueOf(this) }.getOrDefault(ThemeMode.SYSTEM)

    private companion object {
        val KEY_THEME_MODE = stringPreferencesKey("theme_mode")
        val KEY_DYNAMIC_COLOR = booleanPreferencesKey("dynamic_color")
        val KEY_INTRO_DISMISSED = booleanPreferencesKey("intro_dismissed")
    }
}
