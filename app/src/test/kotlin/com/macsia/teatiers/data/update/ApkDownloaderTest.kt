package com.macsia.teatiers.data.update

import android.content.Context
import android.util.Log
import com.macsia.teatiers.data.remote.dto.AppManifestDto
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.Buffer
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

class ApkDownloaderTest {

    private lateinit var server: MockWebServer

    @TempDir
    lateinit var cacheDir: File

    @BeforeEach
    fun setUp() {
        server = MockWebServer()
        server.start()
        // ApkDownloader logs on a failed source; android.util.Log throws "not mocked" in plain JVM.
        mockkStatic(Log::class)
        every { Log.w(any<String>(), any<String>()) } returns 0
    }

    @AfterEach
    fun tearDown() {
        server.shutdown()
    }

    private fun downloader(): ApkDownloader {
        val context = mockk<Context> { every { cacheDir } returns this@ApkDownloaderTest.cacheDir }
        return ApkDownloader(context, OkHttpClient())
    }

    private fun apkBody(bytes: ByteArray) = MockResponse().setBody(Buffer().write(bytes))

    @Test
    fun `downloads from the primary url`() = runTest {
        val apk = byteArrayOf(1, 2, 3, 4, 5)
        server.enqueue(apkBody(apk))

        val file = downloader().download(AppManifestDto(apkUrl = server.url("/teatiers.apk").toString()))

        assertNotNull(file)
        assertArrayEquals(apk, file!!.readBytes())
    }

    @Test
    fun `falls back to a mirror when the primary fails`() = runTest {
        val apk = byteArrayOf(9, 8, 7)
        server.enqueue(MockResponse().setResponseCode(500)) // primary
        server.enqueue(apkBody(apk)) // mirror

        val file = downloader().download(
            AppManifestDto(
                apkUrl = server.url("/primary.apk").toString(),
                mirrorUrls = listOf(server.url("/mirror.apk").toString()),
            ),
        )

        assertNotNull(file)
        assertArrayEquals(apk, file!!.readBytes())
    }

    @Test
    fun `returns null when every source fails`() = runTest {
        server.enqueue(MockResponse().setResponseCode(500))
        server.enqueue(MockResponse().setResponseCode(503))

        val file = downloader().download(
            AppManifestDto(
                apkUrl = server.url("/a.apk").toString(),
                mirrorUrls = listOf(server.url("/b.apk").toString()),
            ),
        )

        assertNull(file)
    }

    @Test
    fun `returns null when the manifest has no usable url`() = runTest {
        assertNull(downloader().download(AppManifestDto(apkUrl = "")))
    }
}
