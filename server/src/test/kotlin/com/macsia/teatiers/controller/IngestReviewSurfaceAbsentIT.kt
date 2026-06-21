package com.macsia.teatiers.controller

import com.macsia.teatiers.AbstractIntegrationTest
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.test.context.TestPropertySource
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import kotlin.test.Test

/**
 * Decision #137-C7: the unauthenticated HTTP review controller was removed in favour of a local
 * operator CLI ([com.macsia.teatiers.cli.ReviewCli]). This proves the canonical-mutation surface is
 * NOT reachable over HTTP -- even with the old `teatiers.ingest.review-api.enabled=true` flag set, the
 * routes 404. A config mistake can no longer expose catalog mutation to the public listener.
 */
@AutoConfigureMockMvc
@TestPropertySource(properties = ["teatiers.ingest.review-api.enabled=true"])
class IngestReviewSurfaceAbsentIT : AbstractIntegrationTest() {

    @Autowired lateinit var mockMvc: MockMvc

    @Test
    fun `the admin review routes are absent even with the old enable flag on`() {
        mockMvc.perform(get("/api/v1/admin/ingest/review/pending")).andExpect(status().isNotFound)
        mockMvc.perform(get("/api/v1/admin/ingest/review/count")).andExpect(status().isNotFound)
        mockMvc.perform(post("/api/v1/admin/ingest/review/1/approve-new").param("reviewer", "x"))
            .andExpect(status().isNotFound)
        mockMvc.perform(post("/api/v1/admin/ingest/review/1/approve-merge").param("reviewer", "x"))
            .andExpect(status().isNotFound)
        mockMvc.perform(post("/api/v1/admin/ingest/review/1/reject").param("reviewer", "x"))
            .andExpect(status().isNotFound)
    }
}
