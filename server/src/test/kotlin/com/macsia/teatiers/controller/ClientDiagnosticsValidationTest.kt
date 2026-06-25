package com.macsia.teatiers.controller

import com.macsia.teatiers.service.ClientDiagnosticsService
import com.macsia.teatiers.service.DailyBudget
import io.mockk.every
import io.mockk.mockk
import kotlin.test.Test
import org.junit.jupiter.api.BeforeEach
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest
import org.springframework.context.annotation.Bean
import org.springframework.http.MediaType
import org.springframework.test.context.TestPropertySource
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status

/**
 * `@Valid` size caps on the diagnostics DTO (SRV-P2-1): an abusively oversized field is rejected at the
 * binding boundary with 400 rather than silently truncated by the service. A normal report still binds.
 */
@WebMvcTest(ClientDiagnosticsController::class)
@TestPropertySource(properties = ["teatiers.diagnostics.enabled=true", "teatiers.diagnostics.token=s3cret"])
class ClientDiagnosticsValidationTest {

    @Autowired lateinit var mockMvc: MockMvc

    @Autowired lateinit var dailyBudget: DailyBudget

    @TestConfiguration
    @EnableConfigurationProperties(ClientDiagnosticsProperties::class)
    class MockConfig {
        @Bean fun clientDiagnosticsService(): ClientDiagnosticsService = mockk(relaxed = true)

        @Bean fun diagnosticsDailyBudget(): DailyBudget = mockk()
    }

    @BeforeEach
    fun underBudget() {
        every { dailyBudget.tryAcquire() } returns true
    }

    @Test
    fun `a stack trace far over the cap is rejected 400, not silently truncated`() {
        val huge = "x".repeat(40_001) // cap is 40_000
        mockMvc.perform(
            post("/api/v1/client-diagnostics")
                .header("X-Diagnostics-Token", "s3cret")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"kind":"crash","stackTrace":"$huge"}"""),
        ).andExpect(status().isBadRequest())
    }

    @Test
    fun `a normal report passes validation and is accepted`() {
        mockMvc.perform(
            post("/api/v1/client-diagnostics")
                .header("X-Diagnostics-Token", "s3cret")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"kind":"crash","stackTrace":"boom"}"""),
        ).andExpect(status().isAccepted())
    }
}
