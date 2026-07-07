package com.macsia.teatiers.client

import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
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
    fun `recognize posts a multipart file to the sidecar and returns text + corrected`() {
        server.enqueue(
            MockResponse().setHeader("Content-Type", "application/json")
                .setBody("""{"text":"Green tea blend","corrected":"Green tea blend"}"""),
        )

        val resp = clientFor().recognize(byteArrayOf(1, 2, 3), "packaging.jpg")

        assertEquals("Green tea blend", resp?.text)
        assertEquals("Green tea blend", resp?.corrected)
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
    fun `recognize retries then degrades to null on a sidecar 5xx rather than throwing`() {
        repeat(2) { server.enqueue(MockResponse().setResponseCode(503)) }

        assertNull(clientFor().recognize(byteArrayOf(1, 2, 3), "x.jpg"))
        assertEquals(2, server.requestCount, "a transient 5xx is retried once before degrading")
    }

    @Test
    fun `recognize returns null when the sidecar omits the text field`() {
        server.enqueue(MockResponse().setHeader("Content-Type", "application/json").setBody("{}"))

        assertNull(clientFor().recognize(byteArrayOf(1, 2, 3), "x.jpg"))
    }

    @Test
    fun `recognize throws SidecarUnreadableImageException on a 422 without retrying`() {
        // UX2-P1-7: a 422 means the sidecar decoded the request but rejected the image itself — unlike
        // a 5xx outage, retrying the same bytes can't help, so this must fail fast (one request only)
        // and distinctly from the generic null-degrade path.
        server.enqueue(MockResponse().setResponseCode(422))

        assertFailsWith<SidecarUnreadableImageException> { clientFor().recognize(byteArrayOf(1, 2, 3), "x.jpg") }
        assertEquals(1, server.requestCount, "a 422 (bad image) must not be retried")
    }
}
