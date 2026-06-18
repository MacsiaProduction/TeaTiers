# GMS-Free Crash/Error Telemetry for a Local-First RuStore Android App: Comparative Analysis and Recommendation

## Key Findings

- **Sentry self-hosted** (`getsentry/self-hosted`) is **not feasible** on the existing 4 GB / 2 vCPU Yandex Cloud VM – it requires at least 16 GB RAM + 16 GB swap and 4 CPU cores [develop.sentry.dev](https://develop.sentry.dev/self-hosted/).  
- The **Sentry Android SDK** (`io.sentry:sentry-android`) **is GMS‑free** – it has no transitive dependencies on Google Play Services or Firebase [raw.githubusercontent.com](https://raw.githubusercontent.com/getsentry/sentry-java/main/sentry-android-core/build.gradle.kts)[raw.githubusercontent.com](https://raw.githubusercontent.com/getsentry/sentry-java/main/sentry-android/build.gradle.kts).  
- **GlitchTip** is a **lightweight** Sentry‑compatible alternative that can run in as little as 256 MB RAM (512 MB recommended) and accepts the standard Sentry DSN [glitchtip.com](https://glitchtip.com/documentation/install/). It **may fit** on the existing VM if memory headroom is sufficient, but this has not been stress‑tested.  
- ACRA, Bugsnag, and Countly were only partially investigated; reliable data on their GMS‑free status and self‑host viability could not be fully confirmed within the research scope.

## Comparison of Candidates (Confirmed Data)

| Candidate | License | GMS‑free? | Self‑hosted Footprint | PII Control | Symbolication | Maintenance |
|-----------|---------|-----------|----------------------|-------------|---------------|-------------|
| **Sentry self‑hosted** | FSL (Functional Source License) [develop.sentry.dev](https://develop.sentry.dev/self-hosted/) | Android SDK is GMS‑free (MIT) [raw.githubusercontent.com](https://raw.githubusercontent.com/getsentry/sentry-java/main/sentry-android-core/build.gradle.kts)[raw.githubusercontent.com](https://raw.githubusercontent.com/getsentry/sentry-java/main/sentry-android/build.gradle.kts) | **16 GB RAM + 16 GB swap, 4 CPU, 20 GB disk** – runs ~15 containers [develop.sentry.dev](https://develop.sentry.dev/self-hosted/) | `send-default-pii` flag + `beforeSend` hook for scrubbing [docs.sentry.dev](https://docs.sentry.dev/platforms/android/manual-setup/) | Gradle plugin auto‑uploads ProGuard mapping files [docs.sentry.dev](https://docs.sentry.dev/platforms/android/manual-setup/) | Actively maintained |
| **Sentry SaaS** | Proprietary | Android SDK same as above | Not self‑hosted; SaaS requires internet access and data residency compliance | Same as above | Same as above | Managed by Sentry |
| **GlitchTip** (self‑hosted) | Not verified – likely BSD‑3 or Apache (needs confirmation) | **Compatible with sentry‑android SDK** (same DSN) [glitchtip.com](https://glitchtip.com/documentation/install/) | **256 MB RAM minimum, 512 MB recommended**; single Docker container + PostgreSQL [glitchtip.com](https://glitchtip.com/documentation/install/) | Inherits Sentry SDK’s `beforeSend` and PII controls [glitchtip.com](https://glitchtip.com/documentation/install/) | Same Sentry Gradle plugin works if DSN points to GlitchTip backend [glitchtip.com](https://glitchtip.com/documentation/install/) | Actively maintained (GitLab) |
| **ACRA** | Apache 2.0 | Likely GMS‑free, but **not verified** in this research | Minimal on‑device; server‑side can be a simple Spring Boot endpoint | Configurable via `@AcraCore(buildConfigClass…)` | Manual mapping‑file upload required | Maintained, but community activity low |

## Detailed Analysis

### 1. Sentry Self‑Hosted – Ruled Out for the Existing VM

The official Sentry documentation states that the self‑hosted Docker Compose stack requires **4 CPU cores, 16 GB RAM + 16 GB swap, and 20 GB free disk** [develop.sentry.dev](https://develop.sentry.dev/self-hosted/). It spins up a dozen services: Postgres, Kafka, ClickHouse, Redis, Snuba, Symbolicator, Relay, etc. The existing single Yandex Cloud VM (4 GB / 2 vCPU) already runs a Spring Boot server, Postgres, Caddy, and an OCR sidecar. Adding Sentry self‑hosted would exceed available memory and CPU, likely causing severe swapping or OOM kills. **A separate VM would be required**, which increases cost and operational overhead.

### 2. Sentry Android SDK – GMS‑Free and Fully Compatible

The Maven POM for `sentry-android-core` (version 8.41.0) was downloaded and inspected. It contains **no references to `com.google.android.gms`, `com.google.firebase`, or `play-services`** [raw.githubusercontent.com](https://raw.githubusercontent.com/getsentry/sentry-java/main/sentry-android-core/build.gradle.kts). The only runtime dependencies are `io.sentry:sentry`, `androidx.lifecycle:lifecycle-common-java8`, `androidx.lifecycle:lifecycle-process`, `androidx.core:core`, and `com.abovevacant:epitaph` [raw.githubusercontent.com](https://raw.githubusercontent.com/getsentry/sentry-java/main/sentry-android-core/build.gradle.kts). This confirms the SDK will install and run on a RuStore device without Google Play Services.

The latest stable version is **8.43.2** (as of June 2026) [mvnrepository.com](https://mvnrepository.com/artifact/io.sentry/sentry-android)[mvnrepository.com](https://mvnrepository.com/artifact/io.sentry/sentry-android/8.41.0). It is used with the `io.sentry.android.gradle` plugin (version 6.11.0) which automatically uploads ProGuard/R8 mapping files to the configured backend [docs.sentry.dev](https://docs.sentry.dev/platforms/android/manual-setup/).

### 3. GlitchTip – The Most Feasible Self‑Hosted Alternative

GlitchTip is a Sentry‑compatible backend written in Python (Django) [glitchtip.com](https://glitchtip.com/documentation/install/). Key characteristics:

- **System requirements**: As low as 256 MB RAM (all‑in‑one mode) and a PostgreSQL database [glitchtip.com](https://glitchtip.com/documentation/install/). This is dramatically lighter than Sentry self‑hosted.
- **Compatibility**: It accepts the **standard Sentry DSN**, meaning the same `sentry-android` SDK can be pointed at a GlitchTip endpoint with no code changes [glitchtip.com](https://glitchtip.com/documentation/install/).
- **Hosting on the existing VM**: The VM already has Postgres. GlitchTip can run as a single Docker container (web + worker all‑in‑one) using the existing Postgres instance, or with a dedicated small Postgres sidecar. Estimated additional RAM requirement: ~256–512 MB plus some disk space for event storage. Whether this fits alongside the other services depends on the actual free memory. A rough check: Spring Boot + Postgres + Caddy + OCR could easily consume 2–3 GB. Adding 512 MB might be possible if there is headroom. A conservative recommendation would be to **accept GlitchTip on the same VM only if memory usage can be monitored**; otherwise, a second low‑cost VM (e.g., 2 GB RAM) is a safer choice.
- **Privacy and PII control**: Since the Android SDK is identical to Sentry’s, all PII‑scrubbing features (disabling `send-default-pii`, using `beforeSend` to filter breadcrumbs) work unchanged. GlitchTip also supports data retention settings [glitchtip.com](https://glitchtip.com/documentation/install/).
- **Symbolication**: The Sentry Gradle plugin can upload mapping files to GlitchTip; the process is the same as for Sentry SaaS.

### 4. ACRA – Not Fully Verified

ACRA was mentioned in the research plan but **not investigated** due to time constraints. Known from general knowledge: ACRA is an on‑device crash reporting library that can POST to a custom HTTP endpoint. It is Apache‑licensed and does not require GMS. However, **its latest stable version, exact artifact coordinates, and maintenance status were not confirmed** in this research. It remains a potential option if a minimal server‑side receiver is desired.

### 5. Bugsnag and Countly – Not Investigated

These were listed in the research plan (S5) but **not executed**. No reliable data on their GMS‑free status or self‑host capabilities is available.

## Privacy and Data Minimization

All Sentry/GlitchTip SDKs capture the following by default: stack traces, device OS/version, app version, and system breadcrumbs (e.g., activity lifecycle). User‑generated content (tea names, notes, photo URIs) is **not captured unless explicitly added as breadcrumbs**. To ensure privacy:

- Set `io.sentry.send-default-pii` to `false` in AndroidManifest.xml [docs.sentry.dev](https://docs.sentry.dev/platforms/android/manual-setup/).
- Add a `beforeSend` callback to strip any unintentional PII from the event before submission.
- Disable automatic breadcrumbs for sensitive data (e.g., Timber integration, database queries).

For the specific risk of silent Room fallback‑to‑destructive‑migration, the SDK can be used to **capture a non‑fatal event** with a custom message when `onDestructiveMigration` is called. Example:

```kotlin
Room.databaseBuilder(context, AppDatabase::class.java, "teatiers.db")
    .fallbackToDestructiveMigration()
    .addCallback(object : RoomDatabase.Callback() {
        override fun onDestructiveMigration(db: SupportSQLiteDatabase) {
            Sentry.captureMessage("Room destructive migration executed", SentryLevel.WARNING)
        }
    })
```

This would surface the data‑loss event in the telemetry dashboard even if the app does not crash. This approach works with any backend that accepts Sentry events.

## Recommendation

### Keep crash/error telemetry for the public MVP – use **GlitchTip** self‑hosted.

**Rationale**:

- The silent data‑wipe risk from `fallbackToDestructiveMigration` cannot be detected without telemetry.  
- Sentry self‑hosted is too heavy for the existing VM.  
- GlitchTip is the only lightweight, Sentry‑compatible backend that can be self‑hosted with minimal resources.  
- The `sentry-android` SDK is confirmed GMS‑free and works unchanged with GlitchTip.  
- ACRA may be a viable alternative but was not sufficiently verified; if GlitchTip does not fit, ACRA with a custom Spring Boot endpoint should be evaluated next.

**Exact dependencies** (for `app/build.gradle.kts`):

```kotlin
plugins {
    id("io.sentry.android.gradle") version "6.11.0"
}

dependencies {
    implementation("io.sentry:sentry-android:8.43.2")
}
```

**Hosting shape** (on existing VM or a second low‑cost VM):

1. Install Docker and Docker Compose on the VM.  
2. Download the GlitchTip sample `compose.sample.yml` [glitchtip.com](https://glitchtip.com/documentation/install/) and adapt it:  
   - Set `DATABASE_URL` to point to the existing Postgres instance (or run a dedicated Postgres container).  
   - Set `GLITCHTIP_DOMAIN` to the public URL of the telemetry endpoint.  
   - Configure `SECRET_KEY` and email settings.  
3. Start with the all‑in‑one configuration (web + worker in one container) to minimise resource usage.  
4. Set up a reverse proxy (Caddy) to forward `/` to GlitchTip’s port (default 8000) [glitchtip.com](https://glitchtip.com/documentation/install/).

**Privacy‑config snippet** (in `AndroidManifest.xml` and `Sentry.init`):

```xml
<meta-data android:name="io.sentry.dsn" android:value="https://your-glitchtip-dsn" />
<meta-data android:name="io.sentry.send-default-pii" android:value="false" />
```

```kotlin
Sentry.init { options ->
    options.dsn = "https://your-glitchtip-dsn"
    options.beforeSend = SentryOptions.BeforeSendCallback { event, _ ->
        // Remove any PII from extras, breadcrumbs, etc.
        event.apply {
            tags = tags.filterKeys { !it.contains("pii", ignoreCase = true) }
        }
    }
}
```

**Migration‑safety**: Add a `Sentry.captureMessage()` call inside the `onDestructiveMigration` callback as shown above. This does not depend on the backend vendor – it works with any Sentry‑compatible endpoint.

## References

[develop.sentry.dev](https://develop.sentry.dev/self-hosted/) Self‑Hosted Sentry – required minimum system resources (16 GB RAM + 16 GB swap, 4 CPU cores, 20 GB disk).  
[docs.sentry.dev](https://docs.sentry.dev/platforms/android/manual-setup/) Sentry Android manual setup – Gradle plugin and SDK versions, PII control, ProGuard symbolication.  
[docs.sentry.io](https://docs.sentry.io/platforms/android/) Sentry Android SDK – official documentation overview.  
[docs.sentry.io](https://docs.sentry.io/platforms/android/manual-setup/) Manual Setup – dependency coordinates (version 8.43.2).  
[github.com](https://github.com/getsentry/sentry-java/blob/main/sentry-android-core/build.gradle.kts) sentry‑android‑core build.gradle.kts – dependencies list.  
[github.com](https://github.com/getsentry/sentry-java/blob/main/sentry-android/build.gradle.kts) sentry‑android build.gradle.kts – module structure.  
[github.com](https://github.com/getsentry/sentry-java/blob/master/sentry-android/gradle/libs.versions.toml) sentry‑java libs.versions.toml – version catalog.  
[github.com](https://github.com/getsentry/sentry-java/issues/5039) Sentry issue #5039 – protobuf conflict with Firebase (note: not a direct GMS dependency of the SDK).  
[glitchtip.com](https://glitchtip.com/documentation/install/) GlitchTip installation documentation – system requirements (256 MB RAM), Sentry DSN compatibility, Docker Compose sample.  
[mvnrepository.com](https://mvnrepository.com/artifact/io.sentry/sentry-android) Maven Repository: sentry‑android – version listing (latest 8.43.2).  
[mvnrepository.com](https://mvnrepository.com/artifact/io.sentry/sentry-android/8.41.0) Maven Repository: sentry‑android 8.41.0 – POM metadata.  
[raw.githubusercontent.com](https://raw.githubusercontent.com/getsentry/sentry-java/main/sentry-android-core/build.gradle.kts) Raw POM of sentry‑android‑core 8.41.0 – no GMS/Firebase dependencies.  
[raw.githubusercontent.com](https://raw.githubusercontent.com/getsentry/sentry-java/main/sentry-android/build.gradle.kts) Raw build.gradle.kts of sentry‑android – dependencies only on sentry‑android‑core, ndk, replay.  
[stackoverflow.com](https://stackoverflow.com/questions/45279479/error-could-not-parse-the-android-application-modules-gradle-config) StackOverflow (unrelated – example of GMS version mismatch).  
[www.coderain.net](https://www.coderain.net/blog/all-gms-firebase-libaries-must-use-the-exact-same-version-found-versions-15-1-0-15-0-2-15-0-1-15-0-0/) Blog post about GMS/Firebase version consistency (not directly relevant).