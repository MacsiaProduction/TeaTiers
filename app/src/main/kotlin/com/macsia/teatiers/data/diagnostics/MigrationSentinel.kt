package com.macsia.teatiers.data.diagnostics

import android.util.Log
import com.macsia.teatiers.data.db.TeaDao
import com.macsia.teatiers.data.db.TeaDatabase
import com.macsia.teatiers.di.AppScope
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "MigrationSentinel"

/** The current user-data row counts the sentinel watches (numeric, no content). */
data class RowCounts(val boards: Int, val teas: Int, val photos: Int) {
    val isEmpty: Boolean get() = boards == 0 && teas == 0 && photos == 0
    val hasData: Boolean get() = boards > 0 || teas > 0 || photos > 0
}

/** The baseline persisted on the previous launch (see [DiagnosticsPreferences.readState]). */
data class SentinelState(
    val appVersionCode: Int,
    val dbVersion: Int,
    val boards: Int,
    val teas: Int,
    val photos: Int,
    val destructiveMigration: Boolean,
)

/**
 * Pure wipe decision (decision #111), separated so it's trivially unit-tested. Reports a silent wipe
 * when the data this device HAD is gone — either because Room's destructive-migration callback fired,
 * or because a version/schema change coincided with non-empty → empty counts. On a fresh install the
 * baseline is [DiagnosticsPreferences.UNKNOWN] (no prior data known), so nothing is reported — there
 * was nothing to lose.
 */
internal fun detectWipe(prev: SentinelState, current: RowCounts, currentDbVersion: Int, currentVersionCode: Int): Map<String, Int>? {
    val prevHadData = prev.boards > 0 || prev.teas > 0 || prev.photos > 0
    if (!prevHadData) return null
    val versionChanged = prev.appVersionCode != currentVersionCode || prev.dbVersion != currentDbVersion
    val wiped = prev.destructiveMigration || (versionChanged && current.isEmpty)
    if (!wiped) return null
    return mapOf(
        "boards_before" to prev.boards.coerceAtLeast(0),
        "boards_after" to current.boards,
        "teas_before" to prev.teas.coerceAtLeast(0),
        "teas_after" to current.teas,
        "photos_before" to prev.photos.coerceAtLeast(0),
        "photos_after" to current.photos,
    )
}

/**
 * Detects a silent local-data wipe across an app update (decision #111) and, when the user has opted
 * in, reports a `room_migration_signal`. State lives outside Room ([DiagnosticsPreferences]) so a
 * destructive migration can't erase the evidence. Best-effort and self-contained: any failure is
 * swallowed so the check can never crash or slow startup.
 */
@Singleton
class MigrationSentinel @Inject constructor(
    private val teaDao: TeaDao,
    private val database: TeaDatabase,
    private val prefs: DiagnosticsPreferences,
    @AppScope private val scope: CoroutineScope,
) {

    /** Fire-and-forget from Application.onCreate; no-op unless the user opted in. */
    fun scheduleCheck() {
        scope.launch {
            runCatching { check() }.onFailure { Log.w(TAG, "sentinel check failed: ${it.message}") }
        }
    }

    suspend fun check() {
        if (!prefs.enabled.value) return
        val current = RowCounts(
            boards = teaDao.boardCount(),
            teas = teaDao.teaCount(),
            photos = teaDao.photoCount(),
        )
        // Reading the open helper's version after Room has opened/migrated gives the live schema version.
        val dbVersion = database.openHelper.readableDatabase.version
        val versionCode = com.macsia.teatiers.BuildConfig.VERSION_CODE
        val prev = prefs.readState()

        detectWipe(prev, current, dbVersion, versionCode)?.let { counts ->
            DiagnosticsWire.post(
                DiagnosticsWire.report(kind = ClientDiagnosticReportDto.KIND_MIGRATION_SIGNAL, rowCounts = counts),
            )
        }
        prefs.saveBaseline(versionCode, dbVersion, current)
    }
}
