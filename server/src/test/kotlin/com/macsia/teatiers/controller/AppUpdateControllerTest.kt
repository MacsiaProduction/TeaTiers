package com.macsia.teatiers.controller

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import org.springframework.http.HttpStatus

/**
 * Unit tests for the app-update manifest endpoint (decision #119). The controller returns a
 * `ResponseEntity` from plain properties, so it's exercised directly — no Spring context needed.
 */
class AppUpdateControllerTest {

    private fun configured() = AppUpdateProperties(
        latestVersionCode = 2,
        latestVersionName = "0.2.0",
        minSupportedVersionCode = 1,
        apkUrl = "https://github.com/MacsiaProduction/TeaTiers/releases/download/v0.2.0/teatiers-v0.2.0.apk",
        apkSha256 = "ABCDEF0123",
        signingCertSha256 = "99AABB",
        mirrorUrls = listOf("https://storage.yandexcloud.net/teatiers/teatiers-v0.2.0.apk"),
        releaseNotesRu = "Исправления",
        releaseNotesEn = "Fixes",
        mandatory = false,
        minOsSdk = 26,
    )

    @Test
    fun `204 when no release is configured`() {
        val response = AppUpdateController(AppUpdateProperties()).latest(ifNoneMatch = null)

        assertEquals(HttpStatus.NO_CONTENT, response.statusCode)
    }

    @Test
    fun `200 with the manifest, sha fields lowercased, and an ETag when configured`() {
        val response = AppUpdateController(configured()).latest(ifNoneMatch = null)

        assertEquals(HttpStatus.OK, response.statusCode)
        val body = response.body!!
        assertEquals(2, body.latestVersionCode)
        assertEquals(1, body.minSupportedVersionCode)
        // The app pins on these, so they must be normalized to lowercase hex.
        assertEquals("abcdef0123", body.apkSha256)
        assertEquals("99aabb", body.signingCertSha256)
        assertEquals(1, body.mirrorUrls.size)
        assertNotNull(response.headers.eTag)
    }

    @Test
    fun `304 when If-None-Match matches the current ETag`() {
        val controller = AppUpdateController(configured())
        val etag = controller.latest(ifNoneMatch = null).headers.eTag!!

        val cached = controller.latest(ifNoneMatch = etag)

        assertEquals(HttpStatus.NOT_MODIFIED, cached.statusCode)
    }

    @Test
    fun `ETag changes when the release changes`() {
        val v2 = AppUpdateController(configured()).latest(null).headers.eTag
        val v3 = AppUpdateController(configured().copy(latestVersionCode = 3)).latest(null).headers.eTag

        assert(v2 != v3) { "a new release must produce a new ETag" }
    }
}
