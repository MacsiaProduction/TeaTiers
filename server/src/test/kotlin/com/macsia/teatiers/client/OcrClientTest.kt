package com.macsia.teatiers.client

import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer

/**
 * HTTP contract tests for the OCR sidecar client over a local MockWebServer (review 2026-06-19 P2:
 * the OcrClient↔sidecar contract was untested). Asserts the multipart request shape, the happy-path
 * parse, and — the important one — that a sidecar **5xx / outage degrades to `null`** (the controller
 * then maps that to 502; see TeaControllerTest), rather than throwing out of `recognize`.
 */
class OcrClientTest {

    private lateinit var server: MockWebServer

    @BeforeTest
    fun setUp() {
        server = MockWebServer()
        server.start()
    }

    @AfterTest
    fun tearDown() {
        server.shutdown()
    }

    private fun clientFor(): OcrClient =
        OcrClient(OcrProperties(sidecarUrl = server.url("/").toString()))

    @Test
    fun `recognize posts a multipart file to the sidecar and returns the text`() {
        server.enqueue(
            MockResponse().setHeader("Content-Type", "application/json").setBody("""{"text":"Green tea blend"}"""),
        )

        val text = clientFor().recognize(byteArrayOf(1, 2, 3), "packaging.jpg")

        assertEquals("Green tea blend", text)
        val recorded = server.takeRequest()
        assertEquals("POST", recorded.method)
        assertEquals("/ocr", recorded.path)
        assertTrue(
            recorded.getHeader("Content-Type")?.startsWith("multipart/form-data") == true,
            "must post multipart/form-data",
        )
        assertTrue(recorded.body.readUtf8().contains("packaging.jpg"), "multipart must carry the filename")
    }

    @Test
    fun `recognize degrades to null on a sidecar 5xx rather than throwing`() {
        server.enqueue(MockResponse().setResponseCode(503))

        assertNull(clientFor().recognize(byteArrayOf(1, 2, 3), "x.jpg"))
    }

    @Test
    fun `recognize returns null when the sidecar omits the text field`() {
        server.enqueue(MockResponse().setHeader("Content-Type", "application/json").setBody("{}"))

        assertNull(clientFor().recognize(byteArrayOf(1, 2, 3), "x.jpg"))
    }
}
