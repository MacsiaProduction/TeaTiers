package com.macsia.teatiers.controller

import kotlin.test.Test
import kotlin.test.assertEquals

class HealthControllerTest {

    @Test
    fun `ping reports ok for the catalog service`() {
        val response = HealthController().ping()

        assertEquals("ok", response.status)
        assertEquals("teatiers-catalog", response.service)
    }
}
