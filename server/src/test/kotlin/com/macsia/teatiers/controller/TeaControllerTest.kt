package com.macsia.teatiers.controller

import com.macsia.teatiers.client.OcrProperties
import com.macsia.teatiers.domain.TeaType
import com.macsia.teatiers.dto.CatalogDetail
import com.macsia.teatiers.dto.FacetsDto
import com.macsia.teatiers.dto.OcrResponseDto
import com.macsia.teatiers.dto.PageDto
import com.macsia.teatiers.dto.ResolveResponseDto
import com.macsia.teatiers.dto.ResolveStatus
import com.macsia.teatiers.dto.TeaDetailDto
import com.macsia.teatiers.dto.TeaImageDto
import com.macsia.teatiers.dto.TeaLifecycleDto
import com.macsia.teatiers.dto.TeaNameDto
import com.macsia.teatiers.dto.TeaProvenanceDto
import com.macsia.teatiers.dto.TeaSummaryDto
import com.macsia.teatiers.service.ClientRateLimiter
import com.macsia.teatiers.service.OcrService
import com.macsia.teatiers.service.ResolveService
import com.macsia.teatiers.service.TeaCatalogService
import io.mockk.clearMocks
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlin.test.Test
import org.junit.jupiter.api.BeforeEach
import java.util.UUID
import java.util.concurrent.Semaphore
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.http.MediaType
import org.springframework.test.context.TestPropertySource
import org.springframework.mock.web.MockMultipartFile
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.content
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status

@WebMvcTest(TeaController::class)
// Small OCR image cap so a tiny multipart can exercise the 413 path (constructor-bound props).
@TestPropertySource(properties = ["teatiers.ocr.max-image-bytes=10"])
class TeaControllerTest {

    @Autowired
    lateinit var mockMvc: MockMvc

    @Autowired
    lateinit var service: TeaCatalogService

    @Autowired
    lateinit var resolveService: ResolveService

    @Autowired
    @Qualifier("resolveRateLimiter")
    lateinit var resolveRateLimiter: ClientRateLimiter

    @Autowired
    @Qualifier("ocrRateLimiter")
    lateinit var ocrRateLimiter: ClientRateLimiter

    @Autowired
    @Qualifier("searchRateLimiter")
    lateinit var searchRateLimiter: ClientRateLimiter

    @Autowired
    lateinit var edgeRateBucket: io.github.bucket4j.Bucket

    @Autowired
    lateinit var ocrService: OcrService

    @Autowired
    lateinit var ocrConcurrencyGate: Semaphore

    // OcrProperties is constructor-bound @ConfigurationProperties — register it for binding rather
    // than hand-constructing a @Bean (which would trigger JavaBean/setter binding and fail on the vals).
    @TestConfiguration
    @EnableConfigurationProperties(OcrProperties::class)
    class MockConfig {
        @Bean
        fun teaCatalogService(): TeaCatalogService = mockk()

        @Bean
        fun resolveService(): ResolveService = mockk()

        @Bean
        fun resolveRateLimiter(): ClientRateLimiter = mockk()

        @Bean
        fun ocrRateLimiter(): ClientRateLimiter = mockk()

        @Bean
        fun searchRateLimiter(): ClientRateLimiter = mockk()

        // The global edge ceiling for /search + /resolve; mocked so tests can force overload (503).
        @Bean
        fun edgeRateBucket(): io.github.bucket4j.Bucket = mockk()

        @Bean
        fun ocrService(): OcrService = mockk()

        // A real semaphore (not a mock) so the controller's acquire/release works; tests that need
        // saturation drain its permits explicitly.
        @Bean
        fun ocrConcurrencyGate(): Semaphore = Semaphore(2)
    }

    // The mock beans are Spring singletons, so MockK call history would otherwise accumulate across
    // test methods — reset it per test so the `verify(exactly = 0)` token-spend assertions are scoped
    // to the call under test.
    @BeforeEach
    fun resetMocks() {
        clearMocks(service, resolveService, resolveRateLimiter, ocrRateLimiter, searchRateLimiter, edgeRateBucket, ocrService)
        // Search is now rate-limited; default every test to "allowed" (the limit case overrides).
        every { searchRateLimiter.tryAcquire(any()) } returns true
        // The global edge ceiling defaults to "allowed"; the overload test overrides it.
        every { edgeRateBucket.tryConsume(1) } returns true
    }

    @Test
    fun `search returns a page of summaries`() {
        every { service.search(any(), any(), any(), any(), any(), any()) } returns PageDto(
            items = listOf(
                TeaSummaryDto(
                    id = 7,
                    publicId = UUID.randomUUID(),
                    type = TeaType.GREEN,
                    originCountry = "CN",
                    brand = null,
                    verificationStatus = "verified",
                    names = listOf(TeaNameDto("en", "Longjing", isPrimary = true)),
                ),
            ),
            nextCursor = 7,
        )

        mockMvc.perform(get("/api/v1/teas/search").param("q", "long"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.items[0].id").value(7))
            .andExpect(jsonPath("$.items[0].type").value("GREEN"))
            .andExpect(jsonPath("$.items[0].names[0].name").value("Longjing"))
            .andExpect(jsonPath("$.nextCursor").value(7))
    }

    @Test
    fun `search rejects an over-cap q with 400 (SRV-P2-2 — @Validated enforces the @Size cap)`() {
        // q longer than MAX_QUERY_LEN (100) must be rejected at the param-validation boundary, not reach the
        // service. Without @Validated the cap silently never runs.
        mockMvc.perform(get("/api/v1/teas/search").param("q", "x".repeat(101)))
            .andExpect(status().isBadRequest())
        verify(exactly = 0) { service.search(any(), any(), any(), any(), any(), any()) }
    }

    @Test
    fun `detail returns the tea`() {
        // The numeric route resolves through the legacy map (decision #137-C1) and returns a sealed result.
        every { service.detailByLegacyId(7) } returns CatalogDetail.Full(
            TeaDetailDto(
                id = 7,
                publicId = UUID.randomUUID(),
                status = "active",
                supersededByPublicId = null,
                wikidataQid = "Q474971",
                type = TeaType.GREEN,
                originCountry = "CN",
                region = null,
                harvestYear = null,
                cultivar = null,
                oxidationMin = null,
                oxidationMax = null,
                brand = null,
                image = TeaImageDto("https://img.example/longjing.jpg", "CC BY-SA 4.0", "https://commons.example/longjing"),
                images = listOf(
                    TeaImageDto("https://img.example/longjing.jpg", "CC BY-SA 4.0", "https://commons.example/longjing"),
                    TeaImageDto("https://img.example/longjing-2.jpg", "CC BY 4.0", "https://commons.example/longjing2"),
                ),
                names = listOf(TeaNameDto("en", "Longjing", isPrimary = true)),
                descriptions = emptyList(),
                flavors = emptyList(),
                provenance = TeaProvenanceDto("curated", null, null, "verified", null),
                enrichmentState = null,
            ),
        )

        mockMvc.perform(get("/api/v1/teas/7"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.id").value(7))
            .andExpect(jsonPath("$.wikidataQid").value("Q474971"))
            // The image list is exposed, and `image` stays as the first one for back-compat (#70.2).
            .andExpect(jsonPath("$.images.length()").value(2))
            .andExpect(jsonPath("$.images[0].url").value("https://img.example/longjing.jpg"))
            .andExpect(jsonPath("$.image.url").value("https://img.example/longjing.jpg"))
    }

    @Test
    fun `detail returns 404 problem json for a missing tea`() {
        every { service.detailByLegacyId(999) } returns null

        mockMvc.perform(get("/api/v1/teas/999"))
            .andExpect(status().isNotFound())
            .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
            .andExpect(jsonPath("$.title").value("Tea not found"))
    }

    @Test
    fun `a retracted tea returns 410 gone with a content-free lifecycle body`() {
        val pid = UUID.randomUUID()
        every { service.detailByPublicId(pid) } returns CatalogDetail.Tombstone(
            TeaLifecycleDto(publicId = pid, status = "retracted", supersededByPublicId = null, message = "withdrawn"),
        )

        mockMvc.perform(get("/api/v1/teas/by-public-id/$pid"))
            .andExpect(status().isGone)
            .andExpect(jsonPath("$.status").value("retracted"))
            .andExpect(jsonPath("$.publicId").value(pid.toString()))
            // No catalog content leaks through a tombstone (decision #139-R2).
            .andExpect(jsonPath("$.names").doesNotExist())
            .andExpect(jsonPath("$.provenance").doesNotExist())
    }

    @Test
    fun `facets returns distinct types and origins`() {
        every { service.facets() } returns FacetsDto(
            types = listOf(TeaType.GREEN, TeaType.OOLONG),
            origins = listOf("CN", "IN"),
        )

        mockMvc.perform(get("/api/v1/teas/facets"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.types[0]").value("GREEN"))
            .andExpect(jsonPath("$.origins[1]").value("IN"))
    }

    @Test
    fun `bad type query param yields 400 problem json`() {
        mockMvc.perform(get("/api/v1/teas/search").param("type", "oolong"))
            .andExpect(status().isBadRequest())
            .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
            .andExpect(jsonPath("$.status").value(400))
    }

    @Test
    fun `resolve returns the enriched tea`() {
        every { resolveRateLimiter.tryAcquire(any()) } returns true
        every { resolveService.resolve("Longjing", "en", null) } returns ResolveResponseDto(
            status = ResolveStatus.ENRICHED,
            tea = TeaDetailDto(
                id = 11, publicId = UUID.randomUUID(), status = "active", supersededByPublicId = null,
                wikidataQid = "Q1069130", type = TeaType.GREEN, originCountry = "CN",
                region = null, harvestYear = null, cultivar = null, oxidationMin = null, oxidationMax = null, brand = null,
                image = null, images = emptyList(), names = listOf(TeaNameDto("en", "Longjing tea", true)),
                descriptions = emptyList(), flavors = emptyList(),
                provenance = TeaProvenanceDto("wikidata", null, "CC0-1.0", "unverified", 0.9f),
                enrichmentState = null,
            ),
        )

        mockMvc.perform(
            post("/api/v1/teas/resolve")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"name":"Longjing","locale":"en"}"""),
        )
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value("ENRICHED"))
            .andExpect(jsonPath("$.tea.id").value(11))
            .andExpect(jsonPath("$.tea.wikidataQid").value("Q1069130"))
    }

    @Test
    fun `resolve rejects a blank name with 400 problem json`() {
        every { resolveRateLimiter.tryAcquire(any()) } returns true

        mockMvc.perform(
            post("/api/v1/teas/resolve")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"name":"   "}"""),
        )
            .andExpect(status().isBadRequest())
            .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
            .andExpect(jsonPath("$.status").value(400))
    }

    @Test
    fun `resolve returns 429 problem json when rate limited`() {
        every { resolveRateLimiter.tryAcquire(any()) } returns false

        mockMvc.perform(
            post("/api/v1/teas/resolve")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"name":"Longjing"}"""),
        )
            .andExpect(status().isTooManyRequests())
            .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
            .andExpect(jsonPath("$.title").value("Rate limit exceeded"))
    }

    @Test
    fun `search returns 503 problem json when the global edge ceiling is saturated (PRIV-P1-1)`() {
        // The per-client limiter allows this caller, but the churn-immune global edge bucket is exhausted.
        every { edgeRateBucket.tryConsume(1) } returns false

        mockMvc.perform(get("/api/v1/teas/search").param("q", "long"))
            .andExpect(status().isServiceUnavailable())
            .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
            .andExpect(jsonPath("$.title").value("Service overloaded"))
    }

    @Test
    fun `ocr returns the recognized text + corrected`() {
        every { ocrRateLimiter.tryAcquire(any()) } returns true
        every { ocrService.recognize(any(), any()) } returns OcrResponseDto("Green tea blend", "Green tea blend")

        mockMvc.perform(
            multipart("/api/v1/teas/ocr").file(MockMultipartFile("file", "x.jpg", "image/jpeg", byteArrayOf(1, 2, 3))),
        )
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.text").value("Green tea blend"))
            .andExpect(jsonPath("$.corrected").value("Green tea blend"))
    }

    @Test
    fun `ocr returns 503 busy when the global concurrency gate is saturated`() {
        every { ocrRateLimiter.tryAcquire(any()) } returns true
        // Drain both permits so the controller's gate tryAcquire fails fast (review F4).
        repeat(2) { ocrConcurrencyGate.acquire() }
        try {
            mockMvc.perform(
                multipart("/api/v1/teas/ocr")
                    .file(MockMultipartFile("file", "x.jpg", "image/jpeg", byteArrayOf(1, 2, 3))),
            )
                .andExpect(status().isServiceUnavailable())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.title").value("OCR busy"))
        } finally {
            repeat(2) { ocrConcurrencyGate.release() }
        }
        // Saturation fast-fails before recognition, so the sidecar is never called.
        verify(exactly = 0) { ocrService.recognize(any(), any()) }
    }

    @Test
    fun `ocr returns 503 problem json when the tier is off`() {
        every { ocrRateLimiter.tryAcquire(any()) } returns true
        every { ocrService.recognize(any(), any()) } throws OcrUnavailableException()

        mockMvc.perform(
            multipart("/api/v1/teas/ocr").file(MockMultipartFile("file", "x.jpg", "image/jpeg", byteArrayOf(1))),
        )
            .andExpect(status().isServiceUnavailable())
            .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
            .andExpect(jsonPath("$.title").value("OCR unavailable"))
    }

    @Test
    fun `ocr returns 502 problem json when the sidecar fails`() {
        // OcrService throws OcrFailedException when OcrClient.recognize returns null (sidecar outage
        // / 5xx — OcrClient swallows it to null, see OcrClientTest). The controller surfaces 502.
        every { ocrRateLimiter.tryAcquire(any()) } returns true
        every { ocrService.recognize(any(), any()) } throws OcrFailedException()

        mockMvc.perform(
            multipart("/api/v1/teas/ocr").file(MockMultipartFile("file", "x.jpg", "image/jpeg", byteArrayOf(1))),
        )
            .andExpect(status().isBadGateway())
            .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
            .andExpect(jsonPath("$.title").value("OCR failed"))
    }

    @Test
    fun `search rejects an over-long query with 400 problem json`() {
        // Spring 6.1+/Boot 4.x built-in method validation enforces the bare param-level @Size(max=100)
        // on q -> HandlerMethodValidationException -> 400 problem+json. NOTE: no class-level @Validated
        // (that routes through the AOP interceptor -> ConstraintViolationException, unhandled here ->
        // 500), which would regress the very behavior this test guards (review P2).
        mockMvc.perform(get("/api/v1/teas/search").param("q", "x".repeat(101)))
            .andExpect(status().isBadRequest())
            .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
        verify(exactly = 0) { service.search(any(), any(), any(), any(), any(), any()) }
    }

    @Test
    fun `search returns 429 problem json when its window is exhausted`() {
        every { searchRateLimiter.tryAcquire(any()) } returns false

        mockMvc.perform(get("/api/v1/teas/search").param("q", "long"))
            .andExpect(status().isTooManyRequests())
            .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
        verify(exactly = 0) { service.search(any(), any(), any(), any(), any(), any()) }
    }

    @Test
    fun `ocr uses its own window, not the resolve one`() {
        // OCR consults only its own limiter — the /resolve window is never touched, so the two
        // endpoints can't deplete each other's budget (decision #103).
        every { ocrRateLimiter.tryAcquire(any()) } returns true
        every { ocrService.recognize(any(), any()) } returns OcrResponseDto("Oolong", "Oolong")

        mockMvc.perform(
            multipart("/api/v1/teas/ocr").file(MockMultipartFile("file", "x.jpg", "image/jpeg", byteArrayOf(1, 2, 3))),
        )
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.text").value("Oolong"))
        verify(exactly = 0) { resolveRateLimiter.tryAcquire(any()) }
    }

    @Test
    fun `ocr returns 429 problem json when its own window is exhausted`() {
        every { ocrRateLimiter.tryAcquire(any()) } returns false

        mockMvc.perform(
            multipart("/api/v1/teas/ocr").file(MockMultipartFile("file", "x.jpg", "image/jpeg", byteArrayOf(1, 2, 3))),
        )
            .andExpect(status().isTooManyRequests())
            .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
            .andExpect(jsonPath("$.title").value("Rate limit exceeded"))
    }

    @Test
    fun `ocr rejects an oversized image with 413 before spending a token`() {
        // The limiter would deny, but request-shape validation runs first, so an oversized upload
        // gets 413 and never burns a rate-limit token (review P2: validate-before-acquire).
        every { ocrRateLimiter.tryAcquire(any()) } returns false

        mockMvc.perform(
            multipart("/api/v1/teas/ocr").file(MockMultipartFile("file", "x.jpg", "image/jpeg", ByteArray(20))),
        )
            .andExpect(status().isPayloadTooLarge())
        verify(exactly = 0) { ocrRateLimiter.tryAcquire(any()) }
    }

    @Test
    fun `ocr rejects an empty file with 400 before spending a token`() {
        every { ocrRateLimiter.tryAcquire(any()) } returns false

        mockMvc.perform(
            multipart("/api/v1/teas/ocr").file(MockMultipartFile("file", "x.jpg", "image/jpeg", ByteArray(0))),
        )
            .andExpect(status().isBadRequest())
        verify(exactly = 0) { ocrRateLimiter.tryAcquire(any()) }
    }
}
