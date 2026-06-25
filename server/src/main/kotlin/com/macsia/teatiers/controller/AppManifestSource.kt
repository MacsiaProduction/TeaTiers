package com.macsia.teatiers.controller

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.macsia.teatiers.dto.AppManifestDto
import java.time.Duration
import org.slf4j.LoggerFactory
import org.springframework.http.client.SimpleClientHttpRequestFactory
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import org.springframework.web.client.RestClient

/**
 * Supplies the manifest [AppUpdateController] serves, auto-published from GitHub Releases.
 *
 * A background task ([refresh]) re-fetches [AppUpdateProperties.manifestUrl] (the stable
 * `releases/latest/download/latest.json` asset, which redirects to the newest release) and keeps the
 * last-known-good copy, so the hot poll path never blocks on — or fails with — GitHub. The JSON is
 * read as text and parsed explicitly because the GitHub asset CDN serves it as `octet-stream`, which
 * content-type negotiation would otherwise reject.
 *
 * [current] precedence: a non-default static config wins (operator emergency pin), else the fetched
 * manifest, else `null` → the controller `204`s.
 */
@Component
class AppManifestSource(
    private val props: AppUpdateProperties,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    // The app has no ObjectMapper bean (it builds them locally, e.g. CatalogImportService) — match that.
    private val mapper = jacksonObjectMapper()

    @Volatile private var remote: AppManifestDto? = null

    private val restClient: RestClient = RestClient.builder()
        .requestFactory(
            SimpleClientHttpRequestFactory().apply {
                setConnectTimeout(Duration.ofSeconds(5))
                setReadTimeout(Duration.ofSeconds(10))
            },
        )
        .build()

    /** The manifest to serve (sha fields lowercased), or null when nothing is published yet. */
    fun current(): AppManifestDto? = when {
        props.latestVersionCode > 0 -> props.toManifest().normalized()
        else -> remote
    }

    // fixedDelay's first run fires shortly after startup, so the manifest loads eagerly; a request
    // racing ahead of it just 204s once and the app retries on its next poll.
    @Scheduled(fixedDelayString = "\${teatiers.appupdate.refresh-ms:600000}")
    fun refresh() {
        val url = props.manifestUrl
        if (url.isBlank()) return
        try {
            val json = restClient.get().uri(url).retrieve().body(String::class.java)
            remote = json?.let { mapper.readValue(it, AppManifestDto::class.java) }?.normalized()
        } catch (ex: Exception) {
            // Keep last-known-good on any failure (outage, redirect, malformed JSON) — never blank a live release.
            log.warn("app manifest refresh from {} failed: {} (keeping last-known-good)", url, ex.toString())
        }
    }

    private fun AppUpdateProperties.toManifest() = AppManifestDto(
        latestVersionCode = latestVersionCode,
        latestVersionName = latestVersionName,
        minSupportedVersionCode = minSupportedVersionCode,
        apkUrl = apkUrl,
        apkSha256 = apkSha256,
        signingCertSha256 = signingCertSha256,
        mirrorUrls = mirrorUrls,
        releaseNotesRu = releaseNotesRu,
        releaseNotesEn = releaseNotesEn,
        mandatory = mandatory,
        minOsSdk = minOsSdk,
    )

    /** The app pins on the sha fields, so normalize them to lowercase hex regardless of the source. */
    private fun AppManifestDto.normalized() =
        copy(apkSha256 = apkSha256.lowercase(), signingCertSha256 = signingCertSha256.lowercase())
}
