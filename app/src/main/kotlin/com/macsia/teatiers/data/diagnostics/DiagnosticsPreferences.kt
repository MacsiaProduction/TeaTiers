package com.macsia.teatiers.data.diagnostics

import android.content.Context
import android.content.SharedPreferences
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The opt-in flag + the out-of-Room wipe sentinel's state (decision #111), kept in a small
 * [SharedPreferences] file rather than DataStore for one reason: ACRA must decide whether to install
 * its crash hook in [android.app.Application.attachBaseContext], which runs **before** Hilt — so the
 * flag has to be readable synchronously with no injected dependency ([isEnabled]).
 *
 * Diagnostics are **off by default**. The sentinel counts live here (not in Room) precisely so a
 * destructive Room migration that drops the user's data can't also erase the evidence of it.
 */
@Singleton
class DiagnosticsPreferences @Inject constructor(
    @ApplicationContext context: Context,
) {
    private val prefs = context.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    private val _enabled = MutableStateFlow(prefs.getBoolean(KEY_ENABLED, false))
    val enabled: StateFlow<Boolean> = _enabled.asStateFlow()

    fun setEnabled(value: Boolean) {
        prefs.edit().putBoolean(KEY_ENABLED, value).apply()
        _enabled.value = value
    }

    /** The last-known baseline the sentinel saved on a prior launch. */
    fun readState(): SentinelState = SentinelState(
        appVersionCode = prefs.getInt(KEY_VERSION, UNKNOWN),
        dbVersion = prefs.getInt(KEY_DB_VERSION, UNKNOWN),
        boards = prefs.getInt(KEY_BOARDS, UNKNOWN),
        teas = prefs.getInt(KEY_TEAS, UNKNOWN),
        photos = prefs.getInt(KEY_PHOTOS, UNKNOWN),
        destructiveMigration = prefs.getBoolean(KEY_DESTRUCTIVE, false),
    )

    /** Persist the new baseline and clear the one-shot destructive-migration flag. */
    fun saveBaseline(appVersionCode: Int, dbVersion: Int, counts: RowCounts) {
        prefs.edit()
            .putInt(KEY_VERSION, appVersionCode)
            .putInt(KEY_DB_VERSION, dbVersion)
            .putInt(KEY_BOARDS, counts.boards)
            .putInt(KEY_TEAS, counts.teas)
            .putInt(KEY_PHOTOS, counts.photos)
            .putBoolean(KEY_DESTRUCTIVE, false)
            .apply()
    }

    companion object {
        private const val FILE = "teatiers_diagnostics"
        private const val KEY_ENABLED = "enabled"
        private const val KEY_VERSION = "last_app_version_code"
        private const val KEY_DB_VERSION = "last_db_version"
        private const val KEY_BOARDS = "last_boards"
        private const val KEY_TEAS = "last_teas"
        private const val KEY_PHOTOS = "last_photos"
        private const val KEY_DESTRUCTIVE = "destructive_migration"

        /** Sentinel value for "no baseline yet" (a fresh install) — distinct from a real zero count. */
        const val UNKNOWN = -1

        /** Synchronous read for [android.app.Application.attachBaseContext] (pre-Hilt). */
        fun isEnabled(context: Context): Boolean =
            context.getSharedPreferences(FILE, Context.MODE_PRIVATE).getBoolean(KEY_ENABLED, false)

        /** Set from Room's `onDestructiveMigration` callback — the definitive "tables were dropped" mark. */
        fun markDestructiveMigration(context: Context) {
            context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
                .edit().putBoolean(KEY_DESTRUCTIVE, true).apply()
        }
    }
}
