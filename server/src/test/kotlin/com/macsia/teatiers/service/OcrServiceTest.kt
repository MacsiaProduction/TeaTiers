package com.macsia.teatiers.service

import com.macsia.teatiers.client.OcrClient
import com.macsia.teatiers.client.OcrProperties
import com.macsia.teatiers.controller.OcrFailedException
import com.macsia.teatiers.controller.OcrUnavailableException
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
    fun `recognize sanitizes the raw sidecar text`() {
        every { client.isEnabled } returns true
        // Leading/trailing + inline whitespace collapsed, blank line dropped (zero-width stripping
        // is covered by OcrSanitizerTest; this just asserts the service delegates to the sanitizer).
        every { client.recognize(any(), any()) } returns "  Зелёный  чай \n\n Сиху  "
        assertEquals("Зелёный чай\nСиху", service().recognize(byteArrayOf(1), "x.jpg"))
    }

    @Test
    fun `recognize caps the text to maxTextLength`() {
        every { client.isEnabled } returns true
        every { client.recognize(any(), any()) } returns "a".repeat(5_000)
        assertTrue(service(maxLen = 10).recognize(byteArrayOf(1), "x.jpg").length <= 10)
    }
}
