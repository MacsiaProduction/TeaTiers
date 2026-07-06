package com.macsia.teatiers.data.repository

import com.macsia.teatiers.data.db.CatalogDao
import com.macsia.teatiers.data.db.toCacheEntity
import com.macsia.teatiers.data.remote.CatalogApi
import com.macsia.teatiers.domain.model.CatalogName
import com.macsia.teatiers.domain.model.CatalogTea
import com.macsia.teatiers.domain.model.FlavorDimension
import com.macsia.teatiers.domain.model.TeaType
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory

@OptIn(ExperimentalCoroutinesApi::class)
class DefaultCatalogRepositoryTest {

    private lateinit var server: MockWebServer
    private lateinit var api: CatalogApi
    private val dao = mockk<CatalogDao>(relaxUnitFun = true)
    private val json = Json { ignoreUnknownKeys = true; explicitNulls = false }

    @BeforeEach
    fun setUp() {
        server = MockWebServer()
        server.start()
        api = Retrofit.Builder()
            .baseUrl(server.url("/api/v1/"))
            .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
            .build()
            .create(CatalogApi::class.java)
    }

    @AfterEach
    fun tearDown() {
        runCatching { server.shutdown() }
    }

    @Test
    fun `search returns parsed teas and caches them on a network hit`() = runTest {
        server.enqueue(MockResponse().setResponseCode(200).setBody(SEARCH_BODY))
        val repo = DefaultCatalogRepository(api, dao, json)

        val result = repo.search("long")

        assertTrue(result is CatalogSearchResult.Loaded)
        result as CatalogSearchResult.Loaded
        assertFalse(result.fromCache)
        assertEquals(1, result.teas.size)
        val tea = result.teas.first()
        assertEquals(TeaType.GREEN, tea.type)
        assertEquals("Лунцзин", tea.nameRu)
        assertEquals("Dragon Well", tea.nameEn)
        coVerify(exactly = 1) { dao.upsertAll(any()) }
    }

    @Test
    fun `blank query short-circuits without touching the network or cache`() = runTest {
        val repo = DefaultCatalogRepository(api, dao, json)

        val result = repo.search("   ")

        assertEquals(CatalogSearchResult.Loaded(emptyList(), fromCache = false), result)
        assertEquals(0, server.requestCount)
        coVerify(exactly = 0) { dao.search(any(), any()) }
    }

    @Test
    fun `server error with an empty cache surfaces Error`() = runTest {
        server.enqueue(MockResponse().setResponseCode(500))
        coEvery { dao.search(any(), any()) } returns emptyList()
        val repo = DefaultCatalogRepository(api, dao, json)

        val result = repo.search("long")

        assertEquals(CatalogSearchResult.Error, result)
    }

    @Test
    fun `server error falls back to cached rows flagged fromCache`() = runTest {
        server.enqueue(MockResponse().setResponseCode(503))
        val cached = sampleTea().toCacheEntity(fetchedAtEpochMs = 1L)
        coEvery { dao.search("long", any()) } returns listOf(cached)
        val repo = DefaultCatalogRepository(api, dao, json)

        val result = repo.search("Long")

        assertTrue(result is CatalogSearchResult.Loaded)
        result as CatalogSearchResult.Loaded
        assertTrue(result.fromCache)
        assertEquals("Лунцзин", result.teas.single().nameRu)
    }

    @Test
    fun `network failure with an empty cache surfaces Offline`() = runTest {
        server.shutdown()
        coEvery { dao.search(any(), any()) } returns emptyList()
        val repo = DefaultCatalogRepository(api, dao, json)

        val result = repo.search("long")

        assertEquals(CatalogSearchResult.Offline, result)
    }

    @Test
    fun `browse parses a page and carries the next cursor`() = runTest {
        server.enqueue(MockResponse().setResponseCode(200).setBody(BROWSE_BODY))
        val repo = DefaultCatalogRepository(api, dao, json)

        val result = repo.browse(cursor = null)

        assertTrue(result is CatalogBrowseResult.Loaded)
        result as CatalogBrowseResult.Loaded
        assertEquals(2, result.teas.size)
        assertEquals(7L, result.nextCursor)
        // Browse mode omits `q` and forwards the cursor on a paged request.
        val recorded = server.takeRequest()
        assertTrue(recorded.path!!.contains("/teas/search"))
        assertFalse(recorded.path!!.contains("q="))
        coVerify(exactly = 1) { dao.upsertAll(any()) }
    }

    @Test
    fun `browse forwards the cursor on a paged request`() = runTest {
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"items":[],"nextCursor":null}"""))
        val repo = DefaultCatalogRepository(api, dao, json)

        val result = repo.browse(cursor = 42L)

        assertEquals(CatalogBrowseResult.Loaded(emptyList(), nextCursor = null), result)
        assertTrue(server.takeRequest().path!!.contains("cursor=42"))
    }

    @Test
    fun `browse maps a server error to Error and a network failure to Offline`() = runTest {
        server.enqueue(MockResponse().setResponseCode(500))
        val repo = DefaultCatalogRepository(api, dao, json)
        assertEquals(CatalogBrowseResult.Error, repo.browse())

        server.shutdown()
        assertEquals(CatalogBrowseResult.Offline, repo.browse())
    }

    @Test
    fun `detail returns a parsed detail on a network hit`() = runTest {
        server.enqueue(MockResponse().setResponseCode(200).setBody(DETAIL_BODY))
        val repo = DefaultCatalogRepository(api, dao, json)

        val result = repo.detail(1)

        assertTrue(result is CatalogDetailResult.Loaded)
        result as CatalogDetailResult.Loaded
        val detail = result.detail
        assertEquals(TeaType.GREEN, detail.type)
        assertEquals("Лунцзин", detail.nameRu)
        assertEquals("West Lake, Hangzhou", detail.region)
        // The unknown axis (MINTY) is dropped; the two known ones map, and the out-of-range
        // intensity (9) is clamped to the shared 0..5 scale.
        assertEquals(2, detail.flavors.size)
        assertTrue(detail.flavors.any { it.dimension == FlavorDimension.SWEETNESS && it.intensity == 3 })
        assertTrue(detail.flavors.any { it.dimension == FlavorDimension.UMAMI && it.intensity == 5 })
        assertEquals("https://example.org/longjing.jpg", detail.image?.url)
        assertTrue(detail.isUnverified.not())
    }

    @Test
    fun `detail server error surfaces Error`() = runTest {
        server.enqueue(MockResponse().setResponseCode(500))
        val repo = DefaultCatalogRepository(api, dao, json)

        assertEquals(CatalogDetailResult.Error, repo.detail(1))
    }

    @Test
    fun `detail 429 surfaces RateLimited, distinct from a generic server error (UX-P1-6)`() = runTest {
        server.enqueue(MockResponse().setResponseCode(429))
        val repo = DefaultCatalogRepository(api, dao, json)

        assertEquals(CatalogDetailResult.RateLimited, repo.detail(1))
    }

    @Test
    fun `detail network failure surfaces Offline`() = runTest {
        server.shutdown()
        val repo = DefaultCatalogRepository(api, dao, json)

        assertEquals(CatalogDetailResult.Offline, repo.detail(1))
    }

    @Test
    fun `detail 410 surfaces a Retracted tombstone carrying the survivor id`() = runTest {
        server.enqueue(
            MockResponse().setResponseCode(410).setBody(
                """{"publicId":"old-uuid","status":"merged","supersededByPublicId":"survivor-uuid"}""",
            ),
        )
        val repo = DefaultCatalogRepository(api, dao, json)

        val result = repo.detail(1)

        assertTrue(result is CatalogDetailResult.Retracted)
        assertEquals("survivor-uuid", (result as CatalogDetailResult.Retracted).supersededByPublicId)
    }

    @Test
    fun `resolve MATCHED returns the parsed detail`() = runTest {
        server.enqueue(MockResponse().setResponseCode(200).setBody(resolveBody("MATCHED", DETAIL_BODY)))
        val repo = DefaultCatalogRepository(api, dao, json)

        val result = repo.resolve("Лунцзин")

        assertTrue(result is ResolveResult.Matched)
        assertEquals("Лунцзин", (result as ResolveResult.Matched).detail.nameRu)
    }

    @Test
    fun `resolve ENRICHING carries the catalog id to poll`() = runTest {
        val stub = """{"id":42,"type":"OTHER","enrichmentState":"PENDING"}"""
        server.enqueue(MockResponse().setResponseCode(200).setBody(resolveBody("ENRICHING", stub)))
        val repo = DefaultCatalogRepository(api, dao, json)

        val result = repo.resolve("Неизвестный чай")

        assertEquals(ResolveResult.Enriching(42), result)
    }

    @Test
    fun `resolve UNRESOLVED maps to Unresolved (null tea)`() = runTest {
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"status":"UNRESOLVED","tea":null}"""))
        val repo = DefaultCatalogRepository(api, dao, json)

        assertEquals(ResolveResult.Unresolved, repo.resolve("Фантом"))
    }

    @Test
    fun `resolve maps a server error to Error and a network failure to Offline`() = runTest {
        server.enqueue(MockResponse().setResponseCode(500))
        val repo = DefaultCatalogRepository(api, dao, json)
        assertEquals(ResolveResult.Error, repo.resolve("Чай"))

        server.shutdown()
        assertEquals(ResolveResult.Offline, repo.resolve("Чай"))
    }

    @Test
    fun `resolve short-circuits a blank name without touching the network`() = runTest {
        val repo = DefaultCatalogRepository(api, dao, json)

        assertEquals(ResolveResult.Unresolved, repo.resolve("   "))
        assertEquals(0, server.requestCount)
    }

    @Test
    fun `ocr returns the recognized text on a hit`() = runTest {
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"text":"Зелёный чай"}"""))
        val repo = DefaultCatalogRepository(api, dao, json)

        val result = repo.ocr(byteArrayOf(1, 2, 3))

        assertTrue(result is OcrResult.Recognized)
        assertEquals("Зелёный чай", (result as OcrResult.Recognized).text)
        // The request must be multipart with a `file` part.
        val recorded = server.takeRequest()
        assertEquals("POST", recorded.method)
        assertTrue(recorded.path!!.endsWith("/teas/ocr"))
        assertTrue(recorded.getHeader("Content-Type")!!.startsWith("multipart/form-data"))
    }

    @Test
    fun `ocr maps a missing text field to blank recognized`() = runTest {
        server.enqueue(MockResponse().setResponseCode(200).setBody("{}"))
        val repo = DefaultCatalogRepository(api, dao, json)

        val result = repo.ocr(byteArrayOf(1))

        assertEquals(OcrResult.Recognized("", ""), result)
    }

    @Test
    fun `ocr maps text + corrected from the response`() = runTest {
        server.enqueue(
            MockResponse().setResponseCode(200)
                .setBody("""{"text":"Дянь Хyн","corrected":"Дянь Хун"}"""),
        )
        val repo = DefaultCatalogRepository(api, dao, json)

        val result = repo.ocr(byteArrayOf(1))

        assertEquals(OcrResult.Recognized(text = "Дянь Хyн", corrected = "Дянь Хун"), result)
    }

    @Test
    fun `ocr falls back corrected to raw text when the server omits it`() = runTest {
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"text":"Дянь Хун"}"""))
        val repo = DefaultCatalogRepository(api, dao, json)

        val result = repo.ocr(byteArrayOf(1))

        assertEquals(OcrResult.Recognized(text = "Дянь Хун", corrected = "Дянь Хун"), result)
    }

    @Test
    fun `ocr maps status codes to typed outcomes`() = runTest {
        val repo = DefaultCatalogRepository(api, dao, json)
        server.enqueue(MockResponse().setResponseCode(413))
        assertEquals(OcrResult.TooLarge, repo.ocr(byteArrayOf(1)))
        server.enqueue(MockResponse().setResponseCode(429))
        assertEquals(OcrResult.RateLimited, repo.ocr(byteArrayOf(1)))
        server.enqueue(MockResponse().setResponseCode(503))
        assertEquals(OcrResult.Unavailable, repo.ocr(byteArrayOf(1)))
        server.enqueue(MockResponse().setResponseCode(500))
        assertEquals(OcrResult.Error, repo.ocr(byteArrayOf(1)))
    }

    @Test
    fun `ocr maps a network failure to Offline and an empty image to Error`() = runTest {
        val repo = DefaultCatalogRepository(api, dao, json)
        assertEquals(OcrResult.Error, repo.ocr(ByteArray(0))) // short-circuits, no request
        assertEquals(0, server.requestCount)
        server.shutdown()
        assertEquals(OcrResult.Offline, repo.ocr(byteArrayOf(1)))
    }

    private fun sampleTea(): CatalogTea = CatalogTea(
        id = 1,
        type = TeaType.GREEN,
        originCountry = "CN",
        brand = null,
        verificationStatus = "verified",
        names = listOf(
            CatalogName("ru", "Лунцзин", isPrimary = true),
            CatalogName("en", "Dragon Well", isPrimary = false),
        ),
    )

    private companion object {
        /** Wraps a tea body (or `null`) in the `/resolve` envelope with the given status. */
        fun resolveBody(status: String, teaBody: String): String = """{"status":"$status","tea":$teaBody}"""

        val SEARCH_BODY =
            """
            {"items":[{"id":1,"type":"GREEN","originCountry":"CN","brand":null,
            "verificationStatus":"verified","names":[
            {"locale":"en","name":"Dragon Well","primary":false},
            {"locale":"ru","name":"Лунцзин","primary":true}]}],"nextCursor":null}
            """.trimIndent()

        // Two-item browse page that still has a next cursor (more pages follow).
        val BROWSE_BODY =
            """
            {"items":[
            {"id":1,"type":"GREEN","originCountry":"CN","brand":null,"verificationStatus":"verified",
            "names":[{"locale":"ru","name":"Лунцзин","primary":true}]},
            {"id":7,"type":"OOLONG","originCountry":"CN","brand":null,"verificationStatus":"verified",
            "names":[{"locale":"ru","name":"Те Гуань Инь","primary":true}]}],"nextCursor":7}
            """.trimIndent()

        // Includes an unknown flavor axis ("MINTY") and an out-of-range intensity to exercise the mapper.
        val DETAIL_BODY =
            """
            {"id":1,"wikidataQid":"Q123","type":"GREEN","originCountry":"CN",
            "region":"West Lake, Hangzhou","cultivar":null,"oxidationMin":null,"oxidationMax":null,
            "brand":null,"image":{"url":"https://example.org/longjing.jpg","license":"CC BY-SA 4.0",
            "sourceUrl":"https://commons.example.org/longjing"},
            "names":[{"locale":"en","name":"Dragon Well","primary":false},
            {"locale":"ru","name":"Лунцзин","primary":true}],
            "descriptions":[{"locale":"ru","short":"Зелёный чай.","full":null,"source":"curated","license":null}],
            "flavors":[{"dimension":"SWEETNESS","intensity":3},{"dimension":"MINTY","intensity":2},
            {"dimension":"UMAMI","intensity":9}],
            "provenance":{"source":"curated","sourceUrl":null,"license":null,
            "verificationStatus":"verified","confidence":null}}
            """.trimIndent()
    }
}
