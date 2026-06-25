package com.macsia.teatiers.controller

import org.springframework.boot.context.properties.ConfigurationProperties

/**
 * The in-app auto-update manifest values (decision #119), bound from config/env.
 *
 * **Normal operation is hands-off:** set [manifestUrl] once to the release's stable
 * `releases/latest/download/latest.json` URL, and every future GitHub release auto-publishes its
 * manifest (the `release.yml` workflow writes `latest.json` as a release asset). The server
 * background-refreshes it ([AppManifestSource]); no per-release env edit or redeploy.
 *
 * The static fields below are the **operator override / fallback**: if [latestVersionCode] `> 0` they
 * win over the fetched manifest (an emergency pin), and they're the value served if [manifestUrl] is
 * unset/unreachable. With everything at its default ([latestVersionCode] `0`, blank [manifestUrl]) the
 * endpoint replies `204` ("no update info") and the app offers nothing.
 *
 * Phased integrity (decision #119): for now the manifest is trusted over TLS; an offline Ed25519
 * signature (a separate field/endpoint) is added before the public launch.
 */
@ConfigurationProperties(prefix = "teatiers.appupdate")
data class AppUpdateProperties(
    /** Stable URL of the auto-published manifest, e.g. `…/releases/latest/download/latest.json`; blank = off. */
    val manifestUrl: String = "",
    /** How often the background task re-fetches [manifestUrl] (ms). */
    val refreshMs: Long = 600_000,
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
    val minOsSdk: Int = 26,
)
