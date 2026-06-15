plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.spring)
    alias(libs.plugins.spring.boot)
    alias(libs.plugins.spring.dependency.management)
}

group = "com.macsia.teatiers"
version = "0.1.0-SNAPSHOT"

dependencies {
    implementation(libs.spring.boot.starter.web)
    implementation(libs.spring.boot.starter.actuator)
    implementation(libs.jackson.module.kotlin)

    testImplementation(libs.spring.boot.starter.test)
    testImplementation(libs.kotlin.test.junit5)
    testImplementation(libs.mockk)
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
