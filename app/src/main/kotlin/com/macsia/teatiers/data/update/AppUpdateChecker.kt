package com.macsia.teatiers.data.update

import com.macsia.teatiers.data.remote.AppUpdateApi
import com.macsia.teatiers.data.remote.dto.AppManifestDto
import javax.inject.Inject
import javax.inject.Singleton

/** The outcome of an update check (decision #119). */
sealed interface UpdateAvailability {
    /** No newer installable release (or the check failed — it's best-effort). */
    data object None : UpdateAvailability

    /** A newer release the user may install at their leisure. */
    data class Optional(val manifest: AppManifestDto) : UpdateAvailability

    /** The installed build is below the server's minimum support (or the release is mandatory). */
    data class Forced(val manifest: AppManifestDto) : UpdateAvailability
}

/**
 * Pure update decision (decision #119), kept out of [AppUpdateChecker] so it's trivially unit-tested.
 * Offers an update only when the manifest is genuinely **newer** (the first downgrade guard — the
 * installer re-checks too) AND installable on this device's OS; forces it when the install is below
 * the server's `minSupportedVersionCode` or the release is flagged `mandatory`.
 */
internal fun decideUpdate(installedVersionCode: Int, osSdkInt: Int, m: AppManifestDto): UpdateAvailability {
    if (m.latestVersionCode <= installedVersionCode) return UpdateAvailability.None
    if (m.minOsSdk > osSdkInt) return UpdateAvailability.None // the new APK needs a newer OS than this device
    val forced = m.mandatory || installedVersionCode < m.minSupportedVersionCode
    return if (forced) UpdateAvailability.Forced(m) else UpdateAvailability.Optional(m)
}

/**
 * Checks the first-party manifest endpoint (`/api/v1/app/latest`) for a newer GitHub-Releases APK.
 * **Best-effort:** a network error, a `204 No Content` (no release configured), or any non-2xx all
 * resolve to [UpdateAvailability.None] — the update check must never crash or block the app. The
 * caller passes the installed version + OS level (so this stays pure/unit-testable):
 * `check(BuildConfig.VERSION_CODE, Build.VERSION.SDK_INT)`.
 */
@Singleton
class AppUpdateChecker @Inject constructor(
    private val api: AppUpdateApi,
) {

    suspend fun check(installedVersionCode: Int, osSdkInt: Int): UpdateAvailability {
        val response = try {
            api.latest()
        } catch (_: Exception) {
            return UpdateAvailability.None
        }
        if (!response.isSuccessful) return UpdateAvailability.None
        val manifest = response.body() ?: return UpdateAvailability.None // 204 No Content => no update
        return decideUpdate(installedVersionCode, osSdkInt, manifest)
    }
}
