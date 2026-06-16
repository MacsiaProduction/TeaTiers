package com.macsia.teatiers

import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.testcontainers.service.connection.ServiceConnection
import org.testcontainers.postgresql.PostgreSQLContainer

/**
 * Base for tests that need the real schema. Uses the singleton-container pattern: one PostgreSQL
 * container is started once for the whole JVM and shared by every test class, which keeps a
 * stable port for Spring's cached application context. (@Testcontainers would stop/restart the
 * static container per class and break the cached context's datasource.) @ServiceConnection wires
 * Spring's datasource to it and Flyway runs the migrations on context start. Locally this uses the
 * podman socket via DOCKER_HOST; CI uses Docker.
 */
@SpringBootTest
abstract class AbstractIntegrationTest {

    companion object {
        @JvmStatic
        @ServiceConnection
        val postgres: PostgreSQLContainer = PostgreSQLContainer("postgres:16-alpine").apply {
            start()
            // Ryuk is disabled under rootless podman, so stop the singleton at JVM shutdown.
            Runtime.getRuntime().addShutdownHook(Thread { stop() })
        }
    }
}
