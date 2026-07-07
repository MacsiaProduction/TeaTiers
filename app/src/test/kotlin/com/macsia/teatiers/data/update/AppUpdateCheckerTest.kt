package com.macsia.teatiers.data.update

import com.macsia.teatiers.data.remote.AppUpdateApi
import com.macsia.teatiers.data.remote.dto.AppManifestDto
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory

class AppUpdateCheckerTest {

    private lateinit var server: MockWebServer

    @BeforeEach
    fun setUp() {
        server = MockWebServer()
        server.start()
    }

    @AfterEach
    fun tearDown() {
        server.shutdown()
    }

    private fun checkerFor(): AppUpdateChecker {
        val json = Json { ignoreUnknownKeys = true; explicitNulls = false }
        val api = Retrofit.Builder()
            .baseUrl(server.url("/api/v1/"))
            .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
            .build()
            .create(AppUpdateApi::class.java)
        return AppUpdateChecker(api)
    }

    private fun manifest(latest: Int, minSupported: Int = 0, mandatory: Boolean = false, minOsSdk: Int = 0) =
        AppManifestDto(
            latestVersionCode = latest,
            latestVersionName = "0.$latest.0",
            minSupportedVersionCode = minSupported,
            apkUrl = "https://example/a.apk",
            apkSha256 = "ab",
            signingCertSha256 = "cd",
            mandatory = mandatory,
            minOsSdk = minOsSdk,
        )

    // --- pure decision matrix (decideUpdate) ----------------------------------------------------

    @Test
    fun `same or older latest version is None (downgrade guard)`() {
        assertEquals(UpdateAvailability.None, decideUpdate(5, 30, manifest(latest = 5)))
        assertEquals(UpdateAvailability.None, decideUpdate(5, 30, manifest(latest = 4)))
    }

    @Test
    fun `newer + installable is Optional`() {
        assertTrue(decideUpdate(5, 30, manifest(latest = 6, minSupported = 1)) is UpdateAvailability.Optional)
    }

    @Test
    fun `below minSupportedVersionCode is Forced`() {
        assertTrue(decideUpdate(5, 30, manifest(latest = 6, minSupported = 6)) is UpdateAvailability.Forced)
    }

    @Test
    fun `mandatory release is Forced even above minSupported`() {
        assertTrue(decideUpdate(5, 30, manifest(latest = 6, minSupported = 1, mandatory = true)) is UpdateAvailability.Forced)
    }

    @Test
    fun `a release needing a newer OS than this device is None`() {
        assertEquals(UpdateAvailability.None, decideUpdate(5, 26, manifest(latest = 6, minOsSdk = 29)))
    }

    // --- best-effort network handling (AppUpdateChecker) ----------------------------------------

    @Test
    fun `200 manifest flows through the decision`() = runTest {
        server.enqueue(
            MockResponse().setHeader("Content-Type", "application/json").setBody(
                """{"latestVersionCode":2,"latestVersionName":"0.2.0","minSupportedVersionCode":2,"apkUrl":"https://x/a.apk","apkSha256":"ab","signingCertSha256":"cd","mandatory":false,"minOsSdk":0}""",
            ),
        )

        val result = checkerFor().check(installedVersionCode = 1, osSdkInt = 30)

        // installed 1 < minSupported 2 -> Forced.
        assertTrue(result is UpdateAvailability.Forced, "expected Forced, got $result")
    }

    @Test
    fun `204 No Content is None`() = runTest {
        server.enqueue(MockResponse().setResponseCode(204))

        assertEquals(UpdateAvailability.None, checkerFor().check(1, 30))
    }

    @Test
    fun `5xx is CheckFailed, not the misleading None (UX-P1-7)`() = runTest {
        server.enqueue(MockResponse().setResponseCode(500))

        assertEquals(UpdateAvailability.CheckFailed, checkerFor().check(1, 30))
    }

    @Test
    fun `a malformed manifest body is CheckFailed, never a crash`() = runTest {
        server.enqueue(MockResponse().setHeader("Content-Type", "application/json").setBody("not json"))

        assertEquals(UpdateAvailability.CheckFailed, checkerFor().check(1, 30))
    }

    @Test
    fun `a network failure is CheckFailed, never a crash (UX-P1-7)`() = runTest {
        server.shutdown()

        assertEquals(UpdateAvailability.CheckFailed, checkerFor().check(1, 30))
    }
}
