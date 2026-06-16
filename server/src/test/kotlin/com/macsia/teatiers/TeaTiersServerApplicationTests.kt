package com.macsia.teatiers

import org.junit.jupiter.api.Test

class TeaTiersServerApplicationTests : AbstractIntegrationTest() {

    @Test
    fun contextLoads() {
        // Smoke test: the Spring context (web + actuator + JPA) must start and Flyway must
        // migrate cleanly against a real PostgreSQL container.
    }
}
