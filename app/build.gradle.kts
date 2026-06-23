plugins {
    // AGP 9 has built-in Kotlin, so the kotlin-android plugin is intentionally absent; the
    // Compose compiler plugin below pins the built-in Kotlin version to our catalog's 2.4.0.
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
    alias(libs.plugins.cyclonedx.bom)
}

android {
    namespace = "com.macsia.teatiers"
    // API 37 is published with a minor version (platform "android-37.0"), so the integer
    // `compileSdk = 37` form resolves "android-37" and fails on CI runners that only ship
    // "android-37.0". The release(37){minorApiLevel = 0} block targets it explicitly.
    // 37 is required: the pinned Compose BOM pulls androidx.core 1.19.0 (needs compileSdk >= 37).
    compileSdk {
        version = release(37) {
            minorApiLevel = 0
        }
    }

    defaultConfig {
        applicationId = "com.macsia.teatiers"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        // Catalog API base URL (M3). Not a secret. Defaults to the live deploy; override per build
        // with -PcatalogBaseUrl=... (e.g. http://10.0.2.2:8080/api/v1/ for a local server). A local
        // cleartext (http) URL also needs a debug network-security-config; the default is HTTPS.
        val catalogBaseUrl = (project.findProperty("catalogBaseUrl") as? String)
            ?: "https://tea.macsia.fun/api/v1/"
        buildConfigField("String", "CATALOG_BASE_URL", "\"$catalogBaseUrl\"")

        // Shared anti-spam token for the opt-in diagnostics endpoint (decision #111). NOT a secret in
        // the security sense — it ships in the APK — so it's a plain build input (env/-Pgradle prop),
        // defaulting to "" which makes the reporter a no-op. Must match teatiers.diagnostics.token on
        // the server. Blank by default so a stock build never sends diagnostics anywhere.
        val diagnosticsToken = (project.findProperty("diagnosticsToken") as? String)
            ?: System.getenv("DIAGNOSTICS_TOKEN") ?: ""
        buildConfigField("String", "DIAGNOSTICS_TOKEN", "\"$diagnosticsToken\"")
    }

    signingConfigs {
        create("release") {
            // CI is the signing authority (decision #118): the release workflow base64-decodes the
            // keystore to a file and passes its path + creds via env (from GitHub secrets). A local
            // `assembleRelease` without these env vars produces an UNSIGNED apk — the config is only
            // wired to the release build when the keystore env is present (see buildTypes.release) —
            // so day-to-day local/debug builds are unaffected and no secret lives in VCS.
            System.getenv("RELEASE_KEYSTORE_FILE")?.let { ksPath ->
                storeFile = file(ksPath)
                storePassword = System.getenv("RELEASE_KEYSTORE_PASSWORD")
                keyAlias = System.getenv("RELEASE_KEY_ALIAS")
                keyPassword = System.getenv("RELEASE_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
            // Sign only when CI supplied the keystore; left unset locally so a local release build
            // stays unsigned rather than failing on a missing keystore.
            if (System.getenv("RELEASE_KEYSTORE_FILE") != null) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
    }

    buildFeatures {
        compose = true
        // Settings "About" reads versionName from the generated BuildConfig (#28).
        buildConfig = true
    }

    lint {
        // Fail the build on lint errors (warnings still only report). If a future upgrade
        // introduces unavoidable findings, capture them in a committed baseline rather than
        // muting the check. (context/decisions.md)
        abortOnError = true
        warningsAsErrors = false
    }

    testOptions {
        // Robolectric needs merged Android assets/resources on the unit-test classpath so the Room
        // migration test can read the exported schema JSON (below).
        unitTests.isIncludeAndroidResources = true
        unitTests.all {
            it.useJUnitPlatform()
            // Surface expected/actual on a failed assertion in CI logs (default hides the message).
            it.testLogging {
                events("failed")
                exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
            }
        }
    }

    // The exported Room schema JSONs go in the DEBUG build-type assets, not main: that's the merged
    // asset dir Robolectric reads (`mergeDebugAssets`, per the generated test_config.properties), so
    // MigrationTestHelper can open the v6 baseline and validate future v6→vN upgrades (#130 / P1-4).
    // Debug-only keeps them out of the released (release-variant) APK. The unit-test source set's own
    // assets are NOT merged for local tests in AGP 9, so `test` here would silently find nothing.
    sourceSets.getByName("debug").assets.srcDir(layout.projectDirectory.dir("schemas"))
}

kotlin {
    // AGP requires a JDK 17 toolchain (AGENTS.md / rule 20-android).
    jvmToolchain(17)
}

// Export Room schemas so v6 (the public baseline, decision #130 / review P0-1) is committed and
// future migrations have a JSON to diff + test against. KSP writes app/schemas/<db>/<version>.json.
ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.viewmodel.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.kotlinx.coroutines.android)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.core)
    implementation(libs.coil.compose)
    implementation(libs.coil.network.okhttp)
    debugImplementation(libs.androidx.compose.ui.tooling)

    implementation(libs.androidx.datastore.preferences)
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.exifinterface)
    implementation(libs.kotlinx.serialization.json)

    implementation(platform(libs.okhttp.bom))
    implementation(libs.okhttp)
    implementation(libs.okhttp.logging.interceptor)
    implementation(libs.retrofit)
    implementation(libs.retrofit.converter.kotlinx.serialization)

    // In-app self-update installer (decision #119): GMS-free PackageInstaller wrapper.
    implementation(libs.ackpine.core)
    implementation(libs.ackpine.ktx)

    // Opt-in, GMS-free crash/diagnostics reporting (decision #111): posts an allowlisted report to
    // our first-party /client-diagnostics. acra-limiter throttles repeated reports.
    implementation(libs.acra.http)
    implementation(libs.acra.limiter)

    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)

    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)
    // Overrides the older kotlin-metadata-jvm bundled by hilt-compiler so the KSP/Hilt
    // processor can parse Kotlin 2.4.0 metadata (highest version on the processor classpath
    // wins). Mirrors android/nowinandroid's fix for google/dagger#5001.
    ksp(libs.kotlin.metadata.jvm)
    implementation(libs.androidx.hilt.lifecycle.viewmodel.compose)

    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platform.launcher)
    testImplementation(libs.mockk)
    testImplementation(libs.turbine)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.okhttp.mockwebserver)

    // Room migration test runs on the JVM under Robolectric (no emulator; Actions unavailable).
    // MigrationTestHelper needs an Android Context — AndroidJUnit4 delegates to Robolectric, and
    // junit-vintage-engine runs the JUnit4 test on our JUnit 5 platform.
    testImplementation(libs.androidx.room.testing)
    testImplementation(libs.androidx.test.ext.junit)
    testImplementation(libs.robolectric)
    testRuntimeOnly(libs.junit.vintage.engine)
}

// SBOM for the OSV-Scanner CI gate (decision #102). Scope to what the release APK actually ships
// (releaseRuntimeClasspath) and drop the Gradle/AGP build-tooling graph — otherwise the SBOM is
// flooded with build-only deps (netty, bouncycastle, httpclient, …) that never reach a user's
// device, drowning the advisory gate in unactionable findings. The aggregate `cyclonedxBom` task
// derives from this direct task; CI scans its JSON at the pinned path.
tasks.named<org.cyclonedx.gradle.CyclonedxDirectTask>("cyclonedxDirectBom") {
    includeConfigs.set(listOf("releaseRuntimeClasspath"))
    includeBuildEnvironment.set(false)
}
tasks.named<org.cyclonedx.gradle.CyclonedxAggregateTask>("cyclonedxBom") {
    jsonOutput.set(layout.buildDirectory.file("reports/cyclonedx/bom.json"))
}
