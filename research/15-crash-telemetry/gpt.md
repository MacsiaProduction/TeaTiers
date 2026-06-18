# Verdict

**Keep crash/error telemetry for the public MVP.** Shipping a local-first app with Room migrations, `fallbackToDestructiveMigration`, `exportSchema=false`, release minification planned, and no diagnostic path is too risky.

**Recommended MVP pick: ACRA + a tiny first-party Spring Boot receiver on the existing TeaTiers backend.** This is the best fit for TeaTiers’ actual constraint: not “maximum observability UI,” but **GMS-free, self-hosted, data-minimized, cheap, and able to report migration/data-loss signals without sending user content**.

**Do not use self-hosted Sentry on the current 4 GB / 2 vCPU Yandex VM.** Official Sentry self-host docs list **4 CPU cores, 16 GB RAM, 16 GB swap, and 20 GB free disk** as minimum requirements; that does not fit beside Postgres, JVM backend, Caddy, and OCR sidecar. ([develop.sentry.dev][1])

**Runner-up:** GlitchTip + Sentry Android SDK, if you want a Sentry-like dashboard and can tolerate an extra service. GlitchTip is much lighter than Sentry and Sentry-SDK-compatible, but it still adds an error-tracking product to operate, and ProGuard/deobfuscation should be verified with a real obfuscated test event before release. GlitchTip’s own docs state it can use Sentry SDKs and can run on your own server; its install docs list PostgreSQL 14+, optional Redis/Valkey, and a **512 MB RAM recommended** app footprint. ([GlitchTip][2]) ([GlitchTip][3])

---

# Candidate comparison table

| Candidate                                                        | Android artifact / current version                                                                                                                                 |                                                                                          License | GMS-free status                                                                                                                               | Self-host fit                                                                                        | Default PII / content risk                                                                                                                             | R8 / ProGuard story                                                                                                                                                                                     | Maintenance health                                                                           | TeaTiers fit                                                                       |
| ---------------------------------------------------------------- | ------------------------------------------------------------------------------------------------------------------------------------------------------------------ | -----------------------------------------------------------------------------------------------: | --------------------------------------------------------------------------------------------------------------------------------------------- | ---------------------------------------------------------------------------------------------------- | ------------------------------------------------------------------------------------------------------------------------------------------------------ | ------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- | -------------------------------------------------------------------------------------------- | ---------------------------------------------------------------------------------- |
| **Sentry self-host + Sentry Android SDK**                        | `io.sentry:sentry-android:8.44.0`; Gradle plugin `io.sentry.android.gradle:6.12.0`                                                                                 | Android SDK MIT; self-hosted Sentry is **FSL-1.1-Apache-2.0**, not classic OSI open source today | **Yes for SDK.** The Maven POM for `sentry-android` shows Sentry modules, not Firebase/GMS; still enforce by Gradle dependency check          | **No on current VM.** Official minimum: 4 cores, 16 GB RAM + 16 GB swap                              | Medium/high unless configured: stack traces, app/device context, breadcrumbs, optional traces/logcat/replay/screenshot/view hierarchy depending config | Best-in-class: Sentry Android Gradle plugin uploads ProGuard/R8 mappings automatically; `sentry-cli upload-proguard` also exists                                                                        | Very active; Android SDK 8.44.0 and Gradle plugin 6.12.0 are current Maven Central artifacts | Good product, bad footprint. Use only on a separate stronger VM or hosted Sentry   |
| **GlitchTip + Sentry Android SDK**                               | GlitchTip server `glitchtip/glitchtip:6`; Android via `io.sentry:sentry-android:8.44.0` or preferably minimal `io.sentry:sentry-android-core:8.44.0` after testing |                                                            GlitchTip MIT; Sentry Android SDK MIT | **Yes for SDK path**, subject to dependency-tree CI check                                                                                     | **Possible.** GlitchTip recommends 512 MB RAM for the app; can reuse Postgres; Redis/Valkey optional | Same SDK-side risk as Sentry; must disable PII, traces, breadcrumbs, screenshots, view hierarchy, replay                                               | GlitchTip docs show Sentry Android SDK usage and GlitchTip CLI debug-symbol upload; Java/Kotlin ProGuard deobfuscation needs a release test because docs are less turnkey than Sentry’s own plugin path | Active: GlitchTip 6.x in 2026, Docker image recently updated, GitLab project MIT             | Best “dashboard” option, but still more ops than TeaTiers needs for MVP            |
| **ACRA + own Spring endpoint**                                   | `ch.acra:acra-http:5.13.1`; optionally `ch.acra:acra-limiter:5.13.1`; latest package published Sep 28, 2025                                                        |                                                                                       Apache-2.0 | **Yes.** Official setup needs ACRA modules only; no Firebase/GMS requirement in the documented setup                                          | **Excellent.** No new service required: one POST endpoint + one DB table on existing backend         | Lowest if configured with `reportContent`; do not include logcat, shared prefs, files, email, device ID                                                | Manual: archive `mapping.txt` per release and use Android R8 `retrace`; can automate later                                                                                                              | Maintained, smaller/slower-moving than Sentry but current enough                             | **Recommended MVP pick**                                                           |
| **Bugsnag Android**                                              | `com.bugsnag:bugsnag-android:6.26.1`                                                                                                                               |                                                             SDK MIT; backend/service proprietary | Core SDK appears GMS-free in docs, but it is SaaS-first                                                                                       | Hosted SaaS by default; on-prem is enterprise/commercial, not an open self-host path                 | Medium; SaaS receives crash data unless heavily filtered                                                                                               | Good: Bugsnag has ProGuard/R8 mapping upload tooling                                                                                                                                                    | Very active; latest GitHub release v6.26.1 on Jun 18, 2026                                   | Technically good SDK, strategically wrong for local-first/self-host MVP            |
| **Countly Android crash reporting**                              | `ly.count.android:sdk:26.1.3`                                                                                                                                      |                                       Android SDK MIT; Countly Lite/Flex/Enterprise server model | Core can run without GMS, but Advertising ID support explicitly requires Google Play Services and push/messaging modules can pull Google deps | Self-host exists, but Countly is analytics-first, not crash-only                                     | Higher local-first risk: default device identity/analytics model is broader than needed                                                                | Not as clean as Sentry/Bugsnag/ACRA for crash-only R8 workflow in the retrieved docs                                                                                                                    | Active; latest SDK release 26.1.3 on May 12, 2026                                            | Avoid unless you also want analytics, which TeaTiers does not                      |
| **Plain `Thread.setDefaultUncaughtExceptionHandler` → `/crash`** | None                                                                                                                                                               |                                                                                        Your code | Yes                                                                                                                                           | Excellent                                                                                            | Lowest possible if carefully implemented                                                                                                               | Manual retrace only                                                                                                                                                                                     | No third-party maintenance risk, but you own reliability                                     | Useful as a migration-signal sender, but too primitive as the only crash collector |

Sources: Sentry Android 8.44.0 and MIT metadata from Maven Central; its POM lists Sentry Android modules as dependencies. ([Maven Central][4]) Sentry self-host requirements and FSL licensing are from official Sentry self-host docs and repo license. ([develop.sentry.dev][1]) ([GitHub][5]) ACRA setup/senders/privacy are from ACRA docs and package metadata. ([acra.ch][6]) ([acra.ch][7]) ([acra.ch][8]) ([GitHub][9]) Bugsnag and Countly versions/features are from their upstream docs/repos. ([docs.bugsnag.com][10]) ([GitHub][11]) ([support.countly.com][12]) ([GitHub][13])

---

# Why “skip telemetry” is the wrong MVP decision

The specific TeaTiers failure mode is not just a crash. It is **silent local data loss**. Android’s Room migration docs explicitly emphasize preserving existing on-device data when schema changes, and automated migrations depend on exported schemas; if `exportSchema=false`, automated migrations fail. ([Android Developers][14])

Telemetry is therefore worth adding before the first public APK, but it must be scoped narrowly:

1. **Fatal crashes** after release.
2. **Non-fatal migration errors**.
3. **Explicit destructive-migration/data-loss signals**.
4. App version, DB schema version, Android version, device model class.
5. No tea names, notes, ratings, photos, photo paths, marketplace URLs, purchase places, board names, or location coordinates.

Crash telemetry alone would **not reliably surface a silent Room wipe**. You need an explicit signal around database migration/opening. Room has had a destructive-migration callback API since Room 2.2.0-alpha02, and destructive fallback behavior can drop and recreate tables if no valid migration path is available. ([Android Developers][15])

---

# Recommendation

## Adopt this for MVP

Use:

```kotlin
implementation("ch.acra:acra-http:5.13.1")
implementation("ch.acra:acra-limiter:5.13.1") // optional but recommended
```

`acra-http` is the right ACRA module because TeaTiers already has a Spring Boot backend and wants no Google dependency, no SaaS, and no extra heavy error-tracking stack. ACRA’s official docs describe HTTP POST reporting, JSON format, email sender, custom senders, and configurable report fields. ([acra.ch][6]) ([acra.ch][7])

## Backend shape

Do **not** add a new telemetry product for MVP. Add one endpoint to the existing backend:

```text
POST /api/v1/client-diagnostics
Content-Type: application/json
Max body: 32 KiB or 64 KiB
Auth: none, but strict rate limits + schema validation
Storage: existing PostgreSQL
Retention: 30–90 days
```

Minimal table:

```sql
create table client_diagnostic_event (
    id uuid primary key,
    received_at timestamptz not null default now(),

    event_type text not null, -- fatal_crash, nonfatal_exception, room_migration_signal
    app_version_name text not null,
    app_version_code integer not null,
    build_type text not null,
    git_sha text,

    android_version text,
    sdk_int integer,
    device_brand text,
    device_model text,

    db_schema_version integer,
    previous_app_version_code integer,
    migration_from integer,
    migration_to integer,

    exception_class text,
    exception_message_hash text,
    stacktrace text,
    stacktrace_hash text not null,

    local_counts_json jsonb, -- numeric counts only: boards, teas, notes, photos
    custom_json jsonb,

    processed boolean not null default false
);

create index client_diagnostic_event_received_idx
    on client_diagnostic_event (received_at desc);

create index client_diagnostic_event_fingerprint_idx
    on client_diagnostic_event (stacktrace_hash, app_version_code);
```

Store **numeric counts only**, never object names or free text. For example, `{ "boards": 3, "teas": 127, "notes": 82, "photos": 24 }` is acceptable. `{ "teaName": "..." }`, note bodies, photo paths, marketplace URLs, and coordinates are not.

---

# ACRA privacy configuration outline

ACRA’s privacy docs explicitly warn that system logs, shared preferences, files, email, and device identifiers can contain private data; they recommend selecting report fields and excluding sensitive preferences. ([acra.ch][8])

Use a strict allowlist. Do not rely on “scrub after capture”; avoid collecting sensitive fields in the first place.

```kotlin
class TeaTiersApp : Application() {

    override fun attachBaseContext(base: Context) {
        super.attachBaseContext(base)

        if (!DiagnosticsPrefs.isEnabled(base)) return

        initAcra {
            buildConfigClass = BuildConfig::class.java

            reportFormat = StringFormat.JSON

            reportContent = arrayOf(
                ReportField.APP_VERSION_CODE,
                ReportField.APP_VERSION_NAME,
                ReportField.PACKAGE_NAME,
                ReportField.ANDROID_VERSION,
                ReportField.SDK_VERSION,
                ReportField.BRAND,
                ReportField.PHONE_MODEL,
                ReportField.STACK_TRACE,
                ReportField.CUSTOM_DATA
            )

            httpSender {
                uri = "https://api.teatiers.example/api/v1/client-diagnostics"
                httpMethod = HttpSender.Method.POST
                compress = true
                connectionTimeout = 5_000
                socketTimeout = 5_000
            }
        }
    }
}
```

Do **not** include:

```text
LOGCAT
EVENTSLOG
RADIOLOG
SHARED_PREFERENCES
DEVICE_ID
USER_EMAIL
APPLICATION_LOG
MEDIA_CODEC_LIST
FILE_PATH
CUSTOM file attachments
```

Also add a backend sanitizer that rejects reports containing suspicious tokens:

```text
content://
file://
/storage/
DCIM/
latitude
longitude
note
photoUri
marketplaceUrl
purchasePlace
```

This is defensive, not a substitute for client-side minimization.

---

# Migration/data-loss signal design

You should fix the root migration issue regardless of telemetry:

1. Remove `fallbackToDestructiveMigration` before public release, except maybe in debug builds.
2. Set `exportSchema=true`.
3. Commit Room schema JSON files.
4. Add `androidx.room:room-testing` migration tests.
5. Add manual or auto migrations for every public DB version.
6. Archive every release APK/AAB, git SHA, Room schema, and R8 mapping file.

Room’s migration docs state that migrations should be tested and that the `room-testing` artifact helps test automated and manual migrations, but exported schema is required. ([Android Developers][14])

Telemetry should then add two explicit signals:

```kotlin
data class RoomMigrationDiagnostic(
    val eventType: String = "room_migration_signal",
    val appVersionCode: Int,
    val previousAppVersionCode: Int?,
    val dbSchemaVersionBefore: Int?,
    val dbSchemaVersionAfter: Int,
    val destructiveMigrationCallbackSeen: Boolean,
    val countsBefore: LocalEntityCounts?,
    val countsAfter: LocalEntityCounts?,
    val suspicion: String?
)
```

Recommended detection:

```text
On clean app start:
1. Before opening Room, read last known local counts from DataStore/SharedPreferences.
2. Open Room.
3. Query current numeric counts.
4. If previous counts were non-zero and current counts are all zero after an app/db version change:
   send room_migration_signal with suspicion="possible_data_loss_after_schema_change".
5. Update last known counts after successful open.
```

Use `RoomDatabase.Callback.onDestructiveMigration` where available to set a local flag, but do not rely only on the callback. The safer detector is the **before/after count sentinel** stored outside Room.

This diagnostic will surface the exact risk you described. A normal crash SDK would not.

---

# R8 / ProGuard handling

Enable release minification, but treat mapping files as release artifacts:

```kotlin
android {
    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
}
```

For ACRA/custom backend:

```text
Archive:
app/build/outputs/mapping/release/mapping.txt
app/build/outputs/apk/release/*.apk
Room schema JSON
git SHA
versionCode
versionName
```

Use Android’s official R8 retrace tool to deobfuscate collected stack traces. Android docs state that R8 retrace reconstructs original stack traces from the generated mapping file and can be run as `retrace path-to-mapping-file path-to-stack-trace-file`. ([Android Developers][16])

For Sentry self-host:

```kotlin
plugins {
    id("io.sentry.android.gradle") version "6.12.0"
}
```

Sentry’s Gradle plugin supports ProGuard/R8 mapping upload, native debug symbols, source context, dependency reports, logcat breadcrumbs, and auto-instrumentation; mapping upload is enabled by default unless configured otherwise. ([Sentry Docs][17])

For GlitchTip:

GlitchTip’s Android docs say to use the Sentry Android SDK unchanged with a GlitchTip DSN, and GlitchTip CLI can upload debug symbols. ([GlitchTip][18]) Its CLI is marked **beta** and says it can upload source maps/debug symbols and manage releases. ([GlitchTip][19]) For Java/Kotlin ProGuard specifically, Sentry’s own CLI supports `upload-proguard`, but I would not rely on GlitchTip deobfuscation until you verify it with an obfuscated release test event. ([Sentry Docs][20])

---

# GMS-free verification

For every release build, add a CI gate:

```bash
./gradlew :app:dependencies --configuration releaseRuntimeClasspath \
  | grep -E "com.google.android.gms|com.google.firebase|firebase-crashlytics" \
  && exit 1 || true
```

Candidate-specific notes:

**ACRA:** no Google Play Services or Firebase dependency is part of the official HTTP setup. The docs require Android Gradle Plugin 4.0.0+ and Java 11, then add ACRA modules such as `ch.acra:acra-http`. ([acra.ch][6])

**Sentry Android SDK:** the current `sentry-android` Maven metadata shows MIT licensing and Sentry Android module dependencies; no Firebase/Play Services dependency is visible in the fetched POM. ([Maven Central][4]) Use the CI gate anyway, especially if enabling integrations.

**GlitchTip:** Android side is the Sentry Android SDK pointed at a GlitchTip DSN, so GMS status follows the chosen Sentry artifact. GlitchTip’s docs explicitly instruct adding `io.sentry:sentry-android` and configuring the DSN. ([GlitchTip][18])

**Countly:** the SDK docs explicitly say Google Advertising ID requires Google Play Services 4.0+ and falls back to OpenUDID if unavailable. That means core crash reporting can avoid GMS, but you must not enable Advertising ID or messaging/push modules for TeaTiers. ([support.countly.com][12])

**Bugsnag:** the Android SDK docs and repo do not indicate a Firebase/Crashlytics dependency for core crash capture; however, the backend path is SaaS/enterprise, not open self-host. ([docs.bugsnag.com][10])

---

# Self-hosting options

## Recommended MVP hosting: no new container

Existing stack:

```yaml
services:
  caddy:
    # existing
  backend:
    # existing Spring Boot service
  postgres:
    # existing
  ocr:
    # existing
```

Add only:

```text
Spring controller: ClientDiagnosticsController
Spring service: ClientDiagnosticsSanitizer
Postgres table: client_diagnostic_event
Caddy route: /api/v1/client-diagnostics
Retention job: delete rows older than 90 days
```

Operational cost:

```text
RAM: negligible beyond backend heap already allocated
CPU: negligible
Disk: roughly 10–30 KB/event; 100k events ≈ 1–3 GB before indexes
Network: tiny
```

## GlitchTip optional compose shape

Use this only if you want a UI, grouping, issue states, and Sentry-like workflow.

```yaml
services:
  glitchtip:
    image: glitchtip/glitchtip:6
    restart: unless-stopped
    depends_on:
      - postgres
    environment:
      SECRET_KEY: "${GLITCHTIP_SECRET_KEY}"
      DATABASE_URL: "postgres://glitchtip:${GLITCHTIP_DB_PASSWORD}@postgres:5432/glitchtip"
      GLITCHTIP_DOMAIN: "https://errors.teatiers.example"
      DEFAULT_FROM_EMAIL: "errors@teatiers.example"
      EMAIL_URL: "consolemail://"
      ENABLE_USER_REGISTRATION: "False"

      # Keep MVP footprint low.
      VALKEY_URL: ""
      GLITCHTIP_ENABLE_UPTIME: "False"
      GLITCHTIP_ENABLE_LOGS: "False"
      GLITCHTIP_MAX_EVENT_LIFE_DAYS: "90"
    expose:
      - "8000"

  # If not reusing existing Postgres:
  # postgres:
  #   image: postgres:16-alpine
  #   volumes:
  #     - glitchtip_pg:/var/lib/postgresql/data
```

GlitchTip docs state PostgreSQL 14+ is required, Redis/Valkey is optional, all-in-one mode is supported, and retention defaults can be configured. ([GlitchTip][3])

## Sentry self-host shape

Not recommended here. Even before traffic, it is a multi-service Compose stack with ClickHouse/Kafka/Redis/Postgres/Snuba/Relay/workers and official minimum requirements far above the current VM. ([develop.sentry.dev][1])

---

# Privacy and consent recommendation

Use **explicit opt-in**, not silent opt-out. TeaTiers’ product promise is stronger than ordinary apps: notes, photos, ratings, places, and local tea records never leave the device. Crash diagnostics are an exception to that promise unless disclosed clearly.

Suggested first-run / Settings copy:

```text
Optional diagnostics

TeaTiers can send privacy-preserving crash and migration diagnostics to the developer’s server. This helps detect crashes and local database migration problems after updates.

When enabled, reports may include: app version, Android version, device model, stack trace, database schema version, and small numeric counters such as how many boards or teas existed before/after a migration.

Reports never include tea names, notes, ratings, photos, photo paths, marketplace links, purchase places, map coordinates, board names, or account data. TeaTiers has no user accounts.

You can turn this off at any time.
```

Retention:

```text
Default: 30 days
During first public beta: 90 days
After stable release: 30 days again
```

Server logging:

```text
Disable or minimize access logs for /api/v1/client-diagnostics.
Do not store raw IP addresses in the event table.
Rate-limit by IP at the edge/runtime, but do not persist IP.
Reject oversized reports.
Reject reports with free-text app content keys.
```

Sentry’s own terms make clear that customers control what service data and personal information are submitted through SDK configuration, and that customer configuration/scrubbing choices are the customer’s responsibility. ([Sentry][21]) This is another reason the TeaTiers MVP should prefer an allowlisted first-party receiver.

---

# Hosted SaaS and Russia reachability / compliance

For TeaTiers, do **not** depend on hosted Sentry, hosted Bugsnag, or hosted GlitchTip for the public MVP.

Reasons:

1. The app is Russia-first and distributed outside Google Play.
2. Crash reports are user-derived telemetry.
3. Hosted services may be blocked, degraded, or affected by sanctions/export-control policies.
4. Sentry and SmartBear/Bugsnag terms include export-control / sanctions compliance language, so availability and eligibility are not something to assume for a Russia-hosted product. ([Sentry][21]) ([smartbear.com][22])
5. Self-hosting on Yandex Cloud keeps the operational and jurisdictional model aligned with the rest of TeaTiers.

I cannot certify live reachability from Russian mobile networks without testing from those networks. Treat SaaS as a non-MVP fallback only.

---

# Exact implementation plan

## Phase 0: remove the known data-loss footgun

Before telemetry:

```text
- Remove fallbackToDestructiveMigration from release builds.
- Set exportSchema=true.
- Commit schemas.
- Add migration tests.
- Add backup/export feature later if local-first data becomes valuable.
```

Telemetry reduces blindness; it does not make destructive migration acceptable.

## Phase 1: ACRA crash capture

Dependencies:

```kotlin
val acraVersion = "5.13.1"

dependencies {
    implementation("ch.acra:acra-http:$acraVersion")
    implementation("ch.acra:acra-limiter:$acraVersion")
}
```

Build gate:

```bash
./gradlew :app:dependencies --configuration releaseRuntimeClasspath \
  | tee deps.txt

! grep -E "com.google.android.gms|com.google.firebase|firebase-crashlytics" deps.txt
```

## Phase 2: first-party diagnostics endpoint

Accept only this schema family:

```json
{
  "eventType": "fatal_crash",
  "appVersionCode": 42,
  "appVersionName": "1.0.0",
  "androidVersion": "15",
  "sdkInt": 35,
  "deviceBrand": "Xiaomi",
  "deviceModel": "2312DRA50G",
  "dbSchemaVersion": 7,
  "exceptionClass": "java.lang.IllegalStateException",
  "stacktrace": "...",
  "custom": {
    "screen": "unknown",
    "migrationFrom": null,
    "migrationTo": null
  }
}
```

Reject any unexpected keys by default.

## Phase 3: migration sentinel

Store outside Room:

```text
lastAppVersionCode
lastDbSchemaVersion
lastKnownBoardCount
lastKnownTeaCount
lastKnownNoteCount
lastKnownPhotoCount
lastSuccessfulOpenTimestamp
```

On startup:

```text
- Read sentinel.
- Open Room.
- Query current counts.
- If previous counts were non-zero and current counts are zero after version/schema change:
  send room_migration_signal.
- Update sentinel.
```

## Phase 4: release mapping archive

For every release:

```text
release-artifacts/
  1.0.0-42/
    app-release.apk
    mapping.txt
    room-schemas/
    git-sha.txt
    dependency-tree.txt
```

Use `retrace mapping.txt stacktrace.txt` when investigating obfuscated reports. ([Android Developers][16])

---

# When to choose GlitchTip instead

Choose GlitchTip instead of ACRA if you want:

```text
- Web UI
- Issue grouping
- Release tracking
- Assigned/resolved states
- Sentry-compatible SDK behavior
- Possible future backend error tracking in the same product
```

Use:

```kotlin
implementation("io.sentry:sentry-android:8.44.0")
```

Or start smaller and test:

```kotlin
implementation("io.sentry:sentry-android-core:8.44.0")
```

Privacy outline:

```kotlin
SentryAndroid.init(this) { options ->
    options.dsn = BuildConfig.GLITCHTIP_DSN
    options.isSendDefaultPii = false

    options.tracesSampleRate = 0.0
    options.isAttachScreenshot = false
    options.isAttachViewHierarchy = false
    options.maxBreadcrumbs = 0

    options.beforeSend = SentryOptions.BeforeSendCallback { event, _ ->
        event.user = null
        event.request = null
        event.breadcrumbs?.clear()

        event.extra?.keys
            ?.filterNot { it in setOf("db_schema_version", "migration_from", "migration_to") }
            ?.forEach { event.extra?.remove(it) }

        event
    }
}
```

Gradle plugin hardening:

```kotlin
sentry {
    includeProguardMapping = true
    autoUploadProguardMapping = true

    includeSourceContext = false
    includeDependenciesReport = false
    telemetry = false

    tracingInstrumentation {
        enabled = false
        logcat {
            enabled = false
        }
    }
}
```

Sentry Android defaults are not all dangerous: `sendDefaultPii` is false by default, request body capture defaults to none, screenshots and view hierarchy are false by default, and session tracking is true. But for TeaTiers, explicitly disable traces, logcat breadcrumbs, screenshots, view hierarchy, replay, and dependency reports unless you have a specific reason. ([Sentry Docs][23]) ([Sentry Docs][17])

---

# Final decision

**Keep telemetry for MVP.**

**Implement ACRA + first-party Spring Boot receiver now.**

**Do not deploy self-hosted Sentry on the existing VM.**

**Keep GlitchTip as the upgrade path** if ACRA reports become too painful to triage manually.

This gives TeaTiers the most important protection before public release: visibility into crashes and, more importantly, explicit visibility into possible local Room migration/data-loss events, while preserving the local-first promise and avoiding Google, Firebase, Crashlytics, external SaaS, and a heavy self-host stack.

[1]: https://develop.sentry.dev/self-hosted/ "Self-Hosted Sentry"
[2]: https://glitchtip.com/ "Open Source Error Tracking | GlitchTip"
[3]: https://glitchtip.com/documentation/install "Install — Documentation | GlitchTip"
[4]: https://central.sonatype.com/artifact/io.sentry/sentry-android "Maven Central: io.sentry:sentry-android"
[5]: https://github.com/getsentry/sentry/blob/master/LICENSE.md "sentry/LICENSE.md at master · getsentry/sentry · GitHub"
[6]: https://www.acra.ch/docs/Setup "Setup | ACRA"
[7]: https://www.acra.ch/docs/Senders "Senders | ACRA"
[8]: https://www.acra.ch/docs/AdvancedUsage "Advanced Usage | ACRA"
[9]: https://github.com/ACRA/acra/packages/700724 "Package ch.acra.acra-http · ACRA/acra · GitHub"
[10]: https://docs.bugsnag.com/platforms/android/ "BugSnag docs › Platforms › Android"
[11]: https://github.com/bugsnag/bugsnag-android "GitHub - bugsnag/bugsnag-android: BugSnag crash monitoring and reporting tool for Android apps · GitHub"
[12]: https://support.countly.com/hc/en-us/articles/11104014467737-Android-22-02 "Android (22.02) – Countly"
[13]: https://github.com/Countly/countly-sdk-android "GitHub - Countly/countly-sdk-android: Countly Digital Analytics Android SDK · GitHub"
[14]: https://developer.android.com/training/data-storage/room/migrating-db-versions "Migrate your Room database  |  App data and files  |  Android Developers"
[15]: https://developer.android.com/jetpack/androidx/releases/room "Room  |  Jetpack  |  Android Developers"
[16]: https://developer.android.com/tools/retrace "R8 retrace  |  Android Studio  |  Android Developers"
[17]: https://docs.sentry.io/platforms/android/configuration/gradle/ "Gradle | Sentry for Android"
[18]: https://glitchtip.com/sdkdocs/java-android "Android SDK | GlitchTip"
[19]: https://glitchtip.com/documentation/cli "Cli — Documentation | GlitchTip"
[20]: https://docs.sentry.io/cli/dif/ "Debug Information Files"
[21]: https://sentry.io/terms/ "Terms of Service 3.0.0 (February 12, 2024) | Sentry"
[22]: https://smartbear.com/terms-of-use/ "
	Terms of Use | SmartBear Software
"
[23]: https://docs.sentry.io/platforms/android/configuration/options/ "Options | Sentry for Android"
