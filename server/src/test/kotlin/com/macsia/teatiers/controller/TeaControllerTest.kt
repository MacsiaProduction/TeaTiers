package com.macsia.teatiers.controller

import com.macsia.teatiers.domain.TeaType
import com.macsia.teatiers.dto.FacetsDto
import com.macsia.teatiers.dto.PageDto
import com.macsia.teatiers.dto.ResolveResponseDto
import com.macsia.teatiers.dto.ResolveStatus
import com.macsia.teatiers.dto.TeaDetailDto
import com.macsia.teatiers.dto.TeaNameDto
import com.macsia.teatiers.dto.TeaProvenanceDto
import com.macsia.teatiers.dto.TeaSummaryDto
import com.macsia.teatiers.service.ResolveRateLimiter
import com.macsia.teatiers.service.ResolveService
import com.macsia.teatiers.service.TeaCatalogService
import io.mockk.every
import io.mockk.mockk
import kotlin.test.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.content
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status

@WebMvcTest(TeaController::class)
class TeaControllerTest {

    @Autowired
    lateinit var mockMvc: MockMvc

    @Autowired
    lateinit var service: TeaCatalogService

    @Autowired
    lateinit var resolveService: ResolveService

    @Autowired
    lateinit var rateLimiter: ResolveRateLimiter

    @TestConfiguration
    class MockConfig {
        @Bean
        fun teaCatalogService(): TeaCatalogService = mockk()

        @Bean
        fun resolveService(): ResolveService = mockk()

        @Bean
        fun resolveRateLimiter(): ResolveRateLimiter = mockk()
    }

    @Test
    fun `search returns a page of summaries`() {
        every { service.search(any(), any(), any(), any(), any(), any()) } returns PageDto(
            items = listOf(
                TeaSummaryDto(
                    id = 7,
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
    fun `detail returns the tea`() {
        every { service.detail(7) } returns TeaDetailDto(
            id = 7,
            wikidataQid = "Q474971",
            type = TeaType.GREEN,
            originCountry = "CN",
            region = null,
            cultivar = null,
            oxidationMin = null,
            oxidationMax = null,
            brand = null,
            image = null,
            names = listOf(TeaNameDto("en", "Longjing", isPrimary = true)),
            descriptions = emptyList(),
            flavors = emptyList(),
            provenance = TeaProvenanceDto("curated", null, null, "verified", null),
        )

        mockMvc.perform(get("/api/v1/teas/7"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.id").value(7))
            .andExpect(jsonPath("$.wikidataQid").value("Q474971"))
    }

    @Test
    fun `detail returns 404 problem json for a missing tea`() {
        every { service.detail(999) } returns null

        mockMvc.perform(get("/api/v1/teas/999"))
            .andExpect(status().isNotFound())
            .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
            .andExpect(jsonPath("$.title").value("Tea not found"))
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
        every { rateLimiter.tryAcquire(any()) } returns true
        every { resolveService.resolve("Longjing", "en") } returns ResolveResponseDto(
            status = ResolveStatus.ENRICHED,
            tea = TeaDetailDto(
                id = 11, wikidataQid = "Q1069130", type = TeaType.GREEN, originCountry = "CN",
                region = null, cultivar = null, oxidationMin = null, oxidationMax = null, brand = null,
                image = null, names = listOf(TeaNameDto("en", "Longjing tea", true)),
                descriptions = emptyList(), flavors = emptyList(),
                provenance = TeaProvenanceDto("wikidata", null, "CC0-1.0", "unverified", 0.9f),
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
        every { rateLimiter.tryAcquire(any()) } returns true

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
        every { rateLimiter.tryAcquire(any()) } returns false

        mockMvc.perform(
            post("/api/v1/teas/resolve")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"name":"Longjing"}"""),
        )
            .andExpect(status().isTooManyRequests())
            .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
            .andExpect(jsonPath("$.title").value("Rate limit exceeded"))
    }
}
