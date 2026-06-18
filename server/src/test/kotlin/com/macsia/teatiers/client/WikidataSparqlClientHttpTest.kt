package com.macsia.teatiers.client

import com.macsia.teatiers.domain.TeaType
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer

/**
 * HTTP round-trip tests for the Wikidata client over a local MockWebServer. The pure-logic suite
 * ([WikidataSparqlClientTest]) never built the real request URI, which is exactly how the encoding
 * defect shipped: the SPARQL was added as a literal queryParam value, so in RestClient's
 * TEMPLATE_AND_VALUES mode its `?`, `{`, `}` and spaces stayed raw -> URISyntaxException -> every
 * /resolve miss 500'd in prod (context/decisions.md #115). These tests exercise the actual
 * request path and guard both the encoding and the fail-closed degrade on a Wikidata error.
 */
class WikidataSparqlClientHttpTest {

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

    private fun clientFor(): WikidataSparqlClient =
        WikidataSparqlClient(WikidataProperties(endpoint = server.url("/sparql").toString()))

    @Test
    fun `findTea percent-encodes the SPARQL query and folds the response into a match`() {
        server.enqueue(
            MockResponse()
                .setHeader("Content-Type", "application/sparql-results+json")
                .setBody(SPARQL_JSON),
        )
        val client = clientFor()

        val match = client.findTea("Longjing", locale = null)

        // The recorded request must carry the *encoded* SPARQL: a raw `{` is illegal in a URI query
        // and is what 500'd the resolve in prod. The decoded `query` param must round-trip to
        // exactly buildQuery's output (findTea lowercases the needle first).
        val recorded = server.takeRequest()
        assertFalse(recorded.path!!.contains("{"), "SPARQL braces must be percent-encoded, not raw")
        val url = recorded.requestUrl!!
        assertEquals("/sparql", url.encodedPath)
        assertEquals(client.buildQuery("longjing"), url.queryParameter("query"))
        assertEquals("json", url.queryParameter("format"))

        // ...and the bindings fold into the expected match.
        assertEquals("Q1069130", match?.qid)
        assertEquals(TeaType.GREEN, match?.type)
        assertEquals("Лунцзин", match?.nameRu)
        assertEquals("CN", match?.originCountry)
    }

    @Test
    fun `findTea fails closed (returns null) when Wikidata errors rather than 500ing the caller`() {
        // MAX_ATTEMPTS retries then degrades; enqueue an error for each attempt.
        repeat(2) { server.enqueue(MockResponse().setResponseCode(500)) }

        assertNull(clientFor().findTea("any tea name", locale = null))
    }

    private companion object {
        val SPARQL_JSON =
            """
            {
              "results": {
                "bindings": [
                  {
                    "item": {"type": "uri", "value": "http://www.wikidata.org/entity/Q1069130"},
                    "cat": {"type": "uri", "value": "http://www.wikidata.org/entity/Q484083"},
                    "en": {"type": "literal", "value": "Longjing tea"},
                    "ru": {"type": "literal", "value": "Лунцзин"},
                    "zh": {"type": "literal", "value": "龙井茶"},
                    "iso2": {"type": "literal", "value": "CN"},
                    "descEn": {"type": "literal", "value": "pan-roasted green tea"}
                  }
                ]
              }
            }
            """.trimIndent()
    }
}
