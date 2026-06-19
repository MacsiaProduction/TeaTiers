package com.macsia.teatiers.data.remote.dto

import kotlinx.serialization.Serializable

/**
 * The in-app auto-update manifest (decision #119), mirroring the server's `dto/AppUpdateDtos.kt`,
 * fetched from `GET /api/v1/app/latest`. Anonymous + no-PII — pure release metadata. The updater
 * verifies a downloaded APK against [apkSha256] and pins its signer to [signingCertSha256] (and
 * Android enforces same-signer on update) before installing; defaults keep an older client decoding
 * a manifest that gains fields.
 */
@Serializable
data class AppManifestDto(
    val latestVersionCode: Int = 0,
    val latestVersionName: String = "",
    val minSupportedVersionCode: Int = 0,
    val apkUrl: String = "",
    val apkSha256: String = "",
    val signingCertSha256: String = "",
    val mirrorUrls: List<String> = emptyList(),
    val releaseNotesRu: String = "",
    val releaseNotesEn: String = "",
    val mandatory: Boolean = false,
    val minOsSdk: Int = 0,
)
