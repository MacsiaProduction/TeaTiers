package com.macsia.teatiers.service

import com.macsia.teatiers.client.OcrClient
import com.macsia.teatiers.client.OcrProperties
import com.macsia.teatiers.client.OcrSidecarResponse
import com.macsia.teatiers.client.SidecarUnreadableImageException
import com.macsia.teatiers.controller.OcrFailedException
import com.macsia.teatiers.controller.OcrUnavailableException
import com.macsia.teatiers.controller.OcrUnreadableImageException
import io.mockk.every
import io.mockk.mockk
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Proxy-contract tests for the OCR tier (review F9): the mapping of the sidecar's outcomes to the
 * endpoint's behaviour — disabled tier → 503, a null/failed sidecar result → 502, and a successful
 * result → sanitized + length-capped text.
 */
class OcrServiceTest {

    private val client = mockk<OcrClient>()
    private fun service(maxLen: Int = 4_000) = OcrService(client, OcrProperties(maxTextLength = maxLen))

    @Test
    fun `recognize maps a disabled tier to OcrUnavailableException (503)`() {
        every { client.isEnabled } returns false
        assertFailsWith<OcrUnavailableException> { service().recognize(byteArrayOf(1), "x.jpg") }
    }

    @Test
    fun `recognize maps a null sidecar result to OcrFailedException (502)`() {
        every { client.isEnabled } returns true
        every { client.recognize(any(), any()) } returns null
        assertFailsWith<OcrFailedException> { service().recognize(byteArrayOf(1), "x.jpg") }
    }

    @Test
    fun `recognize maps SidecarUnreadableImageException to OcrUnreadableImageException (422, UX2-P1-7)`() {
        every { client.isEnabled } returns true
        every { client.recognize(any(), any()) } throws SidecarUnreadableImageException()
        assertFailsWith<OcrUnreadableImageException> { service().recognize(byteArrayOf(1), "x.jpg") }
    }

    @Test
    fun `recognize sanitizes both the raw and the corrected sidecar text`() {
        every { client.isEnabled } returns true
        // Leading/trailing + inline whitespace collapsed, blank line dropped (zero-width stripping
        // is covered by OcrSanitizerTest; this just asserts the service delegates to the sanitizer).
        every { client.recognize(any(), any()) } returns
            OcrSidecarResponse(text = "  Зелёный  чай \n\n Сиху  ", corrected = "  Зелёный чай \n Сиху  ")
        val r = service().recognize(byteArrayOf(1), "x.jpg")
        assertEquals("Зелёный чай\nСиху", r.text)
        assertEquals("Зелёный чай\nСиху", r.corrected)
    }

    @Test
    fun `recognize falls back corrected to raw when the sidecar omits it (pre-104)`() {
        every { client.isEnabled } returns true
        every { client.recognize(any(), any()) } returns OcrSidecarResponse(text = "Зелёный чай", corrected = null)
        assertEquals("Зелёный чай", service().recognize(byteArrayOf(1), "x.jpg").corrected)
    }

    @Test
    fun `recognize caps both fields to maxTextLength`() {
        every { client.isEnabled } returns true
        every { client.recognize(any(), any()) } returns
            OcrSidecarResponse(text = "a".repeat(5_000), corrected = "b".repeat(5_000))
        val r = service(maxLen = 10).recognize(byteArrayOf(1), "x.jpg")
        assertTrue(r.text.length <= 10)
        assertTrue(r.corrected.length <= 10)
    }
}
