package com.macsia.teatiers.client

import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer

/**
 * HTTP round-trip tests for [FoundationModelsClient] over a local MockWebServer. Guards the retry on a
 * 200-with-empty-content: an empty completion (content filter / truncation / empty stream) is a
 * transient result, not an outage, so chatJson must retry rather than return null and let the caller
 * mark the enrichment FAILED on the first blip.
 */
class FoundationModelsClientHttpTest {

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

    private fun clientFor(): FoundationModelsClient =
        FoundationModelsClient(
            LlmProperties(endpoint = server.url("/v1/chat/completions").toString(), apiKey = "k", folderId = "f"),
        )

    private fun json(body: String) =
        MockResponse().setHeader("Content-Type", "application/json").setBody(body)

    @Test
    fun `chatJson retries past a 200 with empty content and returns the next non-empty completion`() {
        // First attempt: HTTP 200 but no usable content (empty choices) -> must retry, not give up.
        server.enqueue(json("""{"choices":[]}"""))
        // Second attempt: the real structured profile.
        server.enqueue(json("""{"choices":[{"message":{"content":"{\"ok\":true}"}}]}"""))

        val result = clientFor().chatJson("model", "sys", emptyList(), "user")

        assertEquals("{\"ok\":true}", result)
        assertEquals(2, server.requestCount, "an empty 200 must trigger a retry")
    }

    @Test
    fun `chatJson returns null after exhausting attempts on repeated empty completions`() {
        repeat(2) { server.enqueue(json("""{"choices":[]}""")) }

        assertNull(clientFor().chatJson("model", "sys", emptyList(), "user"))
        assertEquals(2, server.requestCount)
    }
}
