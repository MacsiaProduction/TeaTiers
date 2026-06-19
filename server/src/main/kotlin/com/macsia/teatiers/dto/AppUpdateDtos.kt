package com.macsia.teatiers.dto

/**
 * In-app auto-update manifest (decision #119), served at `GET /api/v1/app/latest`. The GMS-free
 * sideloaded app polls this to decide whether to offer (or force) an update of the GitHub-Releases
 * APK. **Anonymous + no-PII**: every field is build/version metadata about the *release*, never about
 * the device or user.
 *
 * The app's update decision: `mandatory == true` OR `installedVersionCode < minSupportedVersionCode`
 * ⇒ forced; else `latestVersionCode > installedVersionCode` ⇒ optional. Before installing, the app
 * verifies the download against [apkSha256] and pins the signer to [signingCertSha256] (and Android
 * enforces same-signer on update), then downgrade-rejects `versionCode <= installed`.
 */
data class AppManifestDto(
    val latestVersionCode: Int,
    val latestVersionName: String,
    /** Below this, the install is too old to keep talking to the API → the app forces an update. */
    val minSupportedVersionCode: Int,
    /** Primary APK download (GitHub Release now; a Yandex Object Storage mirror is added before public). */
    val apkUrl: String,
    /** Lowercase hex SHA-256 of the APK bytes — the app pins the download to this. */
    val apkSha256: String,
    /** Lowercase hex SHA-256 of the release signing certificate — the app pins the APK's signer to this. */
    val signingCertSha256: String,
    /** Fallback download URLs tried in order if [apkUrl] is unreachable (RU reachability). */
    val mirrorUrls: List<String>,
    val releaseNotesRu: String,
    val releaseNotesEn: String,
    val mandatory: Boolean,
    /** The lowest Android SDK the new APK supports; the app hides the update on older OSes. */
    val minOsSdk: Int,
)
