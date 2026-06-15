plugins {
    // AGP 9 has built-in Kotlin, so the kotlin-android plugin is intentionally absent; the
    // Compose compiler plugin below pins the built-in Kotlin version to our catalog's 2.4.0.
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
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
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
    }

    buildFeatures {
        compose = true
    }

    lint {
        // Phase 0 can't run lint locally (no Android SDK on this host), so keep it reporting
        // in CI but don't fail the build on findings yet. A curated baseline + abortOnError
        // lands in M1 once the screens stabilize. (context/decisions.md)
        abortOnError = false
    }
}

kotlin {
    // AGP requires a JDK 17 toolchain (AGENTS.md / rule 20-android).
    jvmToolchain(17)
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.activity.compose)
    implementation(libs.kotlinx.coroutines.android)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.core)
    debugImplementation(libs.androidx.compose.ui.tooling)

    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)
    // Overrides the older kotlin-metadata-jvm bundled by hilt-compiler so the KSP/Hilt
    // processor can parse Kotlin 2.4.0 metadata (highest version on the processor classpath
    // wins). Mirrors android/nowinandroid's fix for google/dagger#5001.
    ksp(libs.kotlin.metadata.jvm)
    implementation(libs.androidx.hilt.navigation.compose)

    testImplementation(libs.junit)
}
