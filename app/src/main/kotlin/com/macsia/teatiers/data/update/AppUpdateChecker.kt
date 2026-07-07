package com.macsia.teatiers.data.update

import com.macsia.teatiers.data.remote.AppUpdateApi
import com.macsia.teatiers.data.remote.dto.AppManifestDto
import javax.inject.Inject
import javax.inject.Singleton

/** The outcome of an update check (decision #119). */
sealed interface UpdateAvailability {
    /** No newer installable release — a genuine, successfully-checked "you're up to date". */
    data object None : UpdateAvailability

    /** A newer release the user may install at their leisure. */
    data class Optional(val manifest: AppManifestDto) : UpdateAvailability

    /** The installed build is below the server's minimum support (or the release is mandatory). */
    data class Forced(val manifest: AppManifestDto) : UpdateAvailability

    /**
     * The check itself failed (network error, malformed response, non-2xx) — distinct from [None]
     * (UX-P1-6/P1-7) so "no update" and "couldn't tell" don't look identical to the user tapping
     * "check for updates" offline.
     */
    data object CheckFailed : UpdateAvailability
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
 * Never crashes or blocks the app: a network error, malformed response, or non-2xx resolves to
 * [UpdateAvailability.CheckFailed] (UX-P1-7) rather than the misleading [UpdateAvailability.None] —
 * only a `204 No Content` (no release configured) or a genuinely up-to-date/ineligible manifest is
 * [UpdateAvailability.None]. The caller passes the installed version + OS level (so this stays
 * pure/unit-testable): `check(BuildConfig.VERSION_CODE, Build.VERSION.SDK_INT)`.
 */
@Singleton
class AppUpdateChecker @Inject constructor(
    private val api: AppUpdateApi,
) {

    suspend fun check(installedVersionCode: Int, osSdkInt: Int): UpdateAvailability {
        val response = try {
            api.latest()
        } catch (_: Exception) {
            return UpdateAvailability.CheckFailed
        }
        if (!response.isSuccessful) return UpdateAvailability.CheckFailed
        val manifest = response.body() ?: return UpdateAvailability.None // 204 No Content => no update
        return decideUpdate(installedVersionCode, osSdkInt, manifest)
    }
}
