package com.macsia.teatiers.controller

import org.springframework.boot.context.properties.ConfigurationProperties

/**
 * The in-app auto-update manifest values (decision #119), bound from config/env. The operator sets
 * these **after** cutting a GitHub release: the release workflow prints the APK's SHA-256, and the
 * signing-cert SHA-256 comes from `apksigner verify --print-certs`. Until a release is published,
 * [latestVersionCode] stays `0` and the endpoint replies `204` ("no update info"), so the app simply
 * doesn't offer an update.
 *
 * Phased integrity (decision #119): for now the manifest is trusted over TLS; an offline Ed25519
 * signature (a separate field/endpoint) is added before the public launch.
 */
@ConfigurationProperties(prefix = "teatiers.appupdate")
data class AppUpdateProperties(
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
