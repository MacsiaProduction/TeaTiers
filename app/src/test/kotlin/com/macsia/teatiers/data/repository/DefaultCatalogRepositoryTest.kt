package com.macsia.teatiers.data.repository

import com.macsia.teatiers.data.db.CatalogDao
import com.macsia.teatiers.data.db.toCacheEntity
import com.macsia.teatiers.data.remote.CatalogApi
import com.macsia.teatiers.domain.model.CatalogName
import com.macsia.teatiers.domain.model.CatalogTea
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

    @BeforeEach
    fun setUp() {
        server = MockWebServer()
        server.start()
        val json = Json { ignoreUnknownKeys = true; explicitNulls = false }
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
        val repo = DefaultCatalogRepository(api, dao)

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
        val repo = DefaultCatalogRepository(api, dao)

        val result = repo.search("   ")

        assertEquals(CatalogSearchResult.Loaded(emptyList(), fromCache = false), result)
        assertEquals(0, server.requestCount)
        coVerify(exactly = 0) { dao.search(any(), any()) }
    }

    @Test
    fun `server error with an empty cache surfaces Error`() = runTest {
        server.enqueue(MockResponse().setResponseCode(500))
        coEvery { dao.search(any(), any()) } returns emptyList()
        val repo = DefaultCatalogRepository(api, dao)

        val result = repo.search("long")

        assertEquals(CatalogSearchResult.Error, result)
    }

    @Test
    fun `server error falls back to cached rows flagged fromCache`() = runTest {
        server.enqueue(MockResponse().setResponseCode(503))
        val cached = sampleTea().toCacheEntity(fetchedAtEpochMs = 1L)
        coEvery { dao.search("long", any()) } returns listOf(cached)
        val repo = DefaultCatalogRepository(api, dao)

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
        val repo = DefaultCatalogRepository(api, dao)

        val result = repo.search("long")

        assertEquals(CatalogSearchResult.Offline, result)
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
        val SEARCH_BODY =
            """
            {"items":[{"id":1,"type":"GREEN","originCountry":"CN","brand":null,
            "verificationStatus":"verified","names":[
            {"locale":"en","name":"Dragon Well","primary":false},
            {"locale":"ru","name":"Лунцзин","primary":true}]}],"nextCursor":null}
            """.trimIndent()
    }
}
