plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.spring)
    alias(libs.plugins.kotlin.jpa)
    alias(libs.plugins.spring.boot)
    alias(libs.plugins.spring.dependency.management)
    alias(libs.plugins.cyclonedx.bom)
}

group = "com.macsia.teatiers"
version = "0.1.0-SNAPSHOT"

dependencies {
    implementation(libs.spring.boot.starter.web)
    implementation(libs.spring.boot.starter.actuator)
    implementation(libs.spring.boot.starter.data.jpa)
    implementation(libs.spring.boot.starter.validation)
    implementation(libs.jackson.module.kotlin)
    implementation(libs.caffeine)
    implementation(libs.ipaddress)
    implementation(libs.spring.boot.starter.flyway)
    runtimeOnly(libs.flyway.database.postgresql)
    runtimeOnly(libs.postgresql)

    testImplementation(libs.spring.boot.starter.test)
    testImplementation(libs.spring.boot.starter.webmvc.test)
    testImplementation(libs.spring.boot.testcontainers)
    testImplementation(libs.kotlin.test.junit5)
    testImplementation(libs.mockk)
    testImplementation(libs.mockwebserver)
    testImplementation(libs.testcontainers.postgresql)
    testImplementation(libs.testcontainers.junit.jupiter)
}

kotlin {
    jvmToolchain(21)
    compilerOptions {
        // Treat JSR-305 nullability annotations as strict so Spring's @Nullable/@NonNull
        // participate in Kotlin null-safety.
        freeCompilerArgs.add("-Xjsr305=strict")
    }
}

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
}

// Spring Boot's repackaged bootJar is the deployable artifact; disabling the plain jar keeps
// build/libs to a single file so the Docker image's COPY is unambiguous.
tasks.named("jar") { enabled = false }

// SBOM for the OSV-Scanner CI gate (decision #102). Scope to what the bootJar actually ships
// (runtimeClasspath) and drop the Gradle/build-tooling graph, so the advisory gate fires on
// deployed deps — not on test-only or buildscript dependencies we never serve. The aggregate
// `cyclonedxBom` task derives from this direct task; CI scans its JSON at the pinned path.
tasks.named<org.cyclonedx.gradle.CyclonedxDirectTask>("cyclonedxDirectBom") {
    includeConfigs.set(listOf("runtimeClasspath"))
    includeBuildEnvironment.set(false)
}
tasks.named<org.cyclonedx.gradle.CyclonedxAggregateTask>("cyclonedxBom") {
    jsonOutput.set(layout.buildDirectory.file("reports/cyclonedx/bom.json"))
}
