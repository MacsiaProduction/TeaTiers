# 15-crash-telemetry — GMS-free, Self-Hostable Crash/Error Telemetry for TeaTiers

Adding telemetry to a local-first, GMS-free (Google Play Services-free) app designed for **RuStore** and sideload distribution presents unique infrastructure, compliance, and privacy challenges. This report evaluates the keep vs. skip decision for crash telemetry, compares the viable open-source candidates, addresses symbolication, and provides a production-ready implementation plan.

---

## 1. Candidate Comparison Matrix

The table below evaluates four architecture directions for crash/error telemetry.

| Feature / Metric | **Sentry Self-Hosted** | **GlitchTip** (Sentry-Compatible) | **ACRA** + Custom Endpoint | **Custom Exception Handler** (Spring Boot POST) |
| :--- | :--- | :--- | :--- | :--- |
| **License** | Functional Source License (FSL) | MIT License | Apache 2.0 | Private / Proprietary (Own Code) |
| **GMS-Free?** | **Yes** (SDK allows reflection; GMS is optional) | **Yes** (Uses GMS-free Sentry Android SDK) | **Yes** (100% GMS-free library) | **Yes** (100% GMS-free native Java/Kotlin APIs) |
| **Self-Host Footprint** | **Fail** (Requires minimum **16 GB RAM**, 4 vCPUs, SSD) | **Pass** (Runs on **256MB–512MB RAM**, single-core) | **Excellent** (0MB extra RAM if running as custom Spring controller) | **Excellent** (0MB extra RAM; reuses current Spring Boot backend) |
| **PII-Default Exposure** | **High** (Default collects DB queries, device tags, IPs) | **High** (Inherits Sentry SDK default capture behavior) | **Medium** (Configurable fields; default captures system logs) | **None** (Explicit control; only collects what you manually serialize) |
| **Symbolication (R8/ProGuard)** | Server-side via `sentry-android-gradle-plugin` | Server-side (compatible with Sentry Gradle plugin) | Client-side/Offline manual deobfuscation via `retrace` | Client-side/Offline manual deobfuscation via standard `retrace` |
| **Maintenance Health** | Active (Maintained by Sentry) | Active (Latest version `v5.2` released late 2025) | Active (Latest version `5.13.1` released Sep 28, 2025) | High (No third-party upkeep required) |

---

## 2. GMS-Free Verification & Sanctions Risk Analysis

### GMS-Free SDK Compliance
*   **Sentry Android SDK (`io.sentry:sentry-android`):** The standard Sentry SDK is **GMS-free**. It uses Java reflection to dynamically probe the device for Google Play Services/Firebase components. If those components are missing (such as on custom ROMs, Huawei devices, or RuStore target devices), the SDK gracefully falls back to native GMS-free logging. 
*   **ACRA (`ch.acra:acra-core`):** ACRA is structurally **GMS-free**. It operates entirely on Android system API bindings (`java.lang.Thread` and basic file systems) and does not contain any code related to Google services.
*   **Custom Exception Handler:** Relies on `Thread.setDefaultUncaughtExceptionHandler`, which is a core Java Virtual Machine API. It is completely independent of the Android Play Store Ecosystem.

### US Sanctions & Russia-Reachability (Critical for RuStore Targets)
*   **The SaaS Blocker:** Sentry.io (SaaS) strictly complies with US OFAC sanctions. Effective **September 10, 2024**, Sentry terminated all paid/free accounts belonging to entities in the Russian Federation and blocked all traffic originating from Russian IP ranges. Sentry’s hosted SaaS is **unusable** for Russian users on RuStore.
*   **Self-Hosting is Mandatory:** Any telemetry solution matching the Sentry protocol (Sentry SDK, GlitchTip) must be hosted on your own non-blocked infrastructure (like your Yandex Cloud VM).
*   **Gradle Build Failures:** The Sentry Android Gradle Plugin automatically attempts to upload ProGuard mapping files to Sentry servers during release builds. If your local development machine is running inside Russia, these network requests to `sentry.io` will fail, causing build failures unless:
    1. You route your build machine through a VPN.
    2. You configure the Gradle plugin to point to your self-hosted GlitchTip URL in `sentry.properties` or inside the `build.gradle.kts` configuration block.

---

## 3. Infrastructure & Self-Host Sizing

Your existing Yandex Cloud VM runs on **4 GB RAM and 2 vCPUs**, hosting:
*   PostgreSQL Database
*   JVM Application (Kotlin/Spring Boot)
*   Caddy Reverse Proxy
*   OCR Sidecar container

### Sentry Self-Hosted: Hard Infrastructure Blocker
The official Sentry self-hosted stack (`getsentry/self-hosted`) runs over 20 Docker containers, including Apache Kafka, ClickHouse, ZooKeeper, Snuba, Redis, and custom workers. It requires **16 GB of RAM as an absolute minimum**. Attempting to run this alongside your existing JVM application on a 4 GB RAM VM will instantly trigger the Linux Out-Of-Memory (OOM) killer and crash the host machine. 

### Option A: GlitchTip (Lightweight Sentry Backend)
GlitchTip is a Python/Django alternative that is compatible with Sentry's client SDKs. It requires only a fraction of the resources. With the release of **GlitchTip v5.2 (late 2025)**, Redis/Valkey has become completely optional. If configured to run in "Postgres-only" mode, its memory footprint drops to **less than 256MB RAM**.

#### GlitchTip Docker Compose Fragment (`docker-compose.yml`)
To run GlitchTip alongside your existing setup, configure resource limits to prevent it from consuming JVM memory:

```yaml
version: '3.8'

services:
  # Reuse your existing Postgres container (add a 'glitchtip' database to it manually or via init scripts)
  
  glitchtip:
    image: glitchtip/glitchtip:v5.2
    container_name: glitchtip-web
    depends_on:
      - postgres
    environment:
      # Point to your existing Postgres DB instance
      DATABASE_URL: postgres://postgres:your_secure_password@postgres:5432/glitchtip
      SECRET_KEY: ${GLITCHTIP_SECRET_KEY} # Generate with: openssl rand -hex 32
      PORT: 8000
      GLITCHTIP_DOMAIN: https://telemetry.teatiers.ru
      VALKEY_URL: "" # Disables Redis/Valkey, drops RAM use to < 256MB
      ENABLE_USER_REGISTRATION: "False"
      ENABLE_ORGANIZATION_CREATION: "False"
    deploy:
      resources:
        limits:
          cpus: '0.5'
          memory: 300M
        reservations:
          memory: 150M
    restart: always

  # Single All-in-One Celery Worker/Scheduler for low volumes
  glitchtip-worker:
    image: glitchtip/glitchtip:v5.2
    container_name: glitchtip-worker
    command: bin/start-all-in-one.sh
    depends_on:
      - glitchtip
    environment:
      DATABASE_URL: postgres://postgres:your_secure_password@postgres:5432/glitchtip
      VALKEY_URL: ""
    deploy:
      resources:
        limits:
          cpus: '0.25'
          memory: 200M
    restart: always
```

### Option B: Custom Endpoint on Spring Boot (The Native Path)
This is the most secure and lightweight architecture for a local-first application. Instead of hosting an extra database and application framework, write a single REST controller in your existing Spring Boot application. 
*   **Infrastructure Overhead:** **0 MB extra RAM**, zero additional ports, zero extra containers, and zero external network configuration. It reuses your existing PostgreSQL server to store raw stack traces.

---

## 4. Privacy, Consent, & Data Minimization

The app's marketing claims that your user data never leaves the device. If telemetry is introduced, strict configurations must be enforced to keep this promise.

### Sentry PII Sanitization
By default, Sentry captures database queries, network breadcrumbs, IP addresses, and device IDs. A query containing raw SQLite parameters could easily leak custom tea names, user notes, and rating states. You must explicitly strip these components in your Android code:

```kotlin
import io.sentry.android.core.SentryAndroid
import io.sentry.android.core.SentryAndroidOptions

fun initSentry(context: Context) {
    SentryAndroid.init(context) { options: SentryAndroidOptions ->
        options.dsn = "https://your_public_glitchtip_key@telemetry.teatiers.ru/1"
        
        // 1. Disable Default PII Capture
        options.isSendDefaultPii = false
        
        // 2. Disable Automatic Breadcrumb Integrations that might leak local user data
        options.isEnableAppLifecycleBreadcrumbs = true  // Safe (App background/foreground status)
        options.isEnableActivityLifecycleBreadcrumbs = true // Safe (UI Screen navigations only)
        options.isEnableDatabaseBreadcrumbs = false      // CRITICAL: Wipes SQL query string logging
        options.isEnableNetworkEventBreadcrumbs = false  // Safe for local-first, but prevents outbound URL leaks
        options.isEnableSystemEventBreadcrumbs = false   // Prevents system log parameters from leaking
        
        // 3. Before-Send Hook to strip messages and stack parameters of unexpected PII
        options.setBeforeSend { event, _ ->
            // Scrub user IP address completely
            event.user = null 
            
            // Scrub file paths/URIs that could match local tea photo names
            event.breadcrumbs?.forEach { breadcrumb ->
                if (breadcrumb.message != null) {
                    breadcrumb.message = sanitizeMessage(breadcrumb.message!!)
                }
            }
            event
        }
    }
}

private fun sanitizeMessage(message: String): String {
    // Regex targeting file paths, URIs, and potential SQL elements
    return message.replace(Regex("/data/user/\\d+/com\\.teatiers/\\S+"), "[LOCAL_FILE_PATH_REDACTED]")
}
```

### Privacy Consent Strategy (Opt-In vs. Opt-Out)
Even though the telemetry contains no PII, starting a network upload to your endpoint constitutes an analytical payload.
1.  **Strict "Opt-In" Flow:** To respect local-first values, present an optional toggle on the onboarding screen. Sentry/GlitchTip or your custom endpoint should not initialize until consent is explicitly granted.
2.  **Toggle Settings:** Provide a clear "Send Crash Reports" toggle switch in the application Settings menu.

```kotlin
// Check preference state on application initialization
val isTelemetryEnabled = sharedPrefs.getBoolean("consent_telemetry", false)
if (isTelemetryEnabled) {
    initSentry(this)
}
```

---

## 5. ProGuard / R8 Deobfuscation (Symbolication)

Because minification (`isMinifyEnabled=true`) must be enabled, stack traces will arrive obfuscated. You have two methods for mapping obfuscated class names (e.g., `a.b.c.d.a`) back to real source files.

### Method A: Sentry Gradle Plugin (For GlitchTip/Sentry)
In your Android build process, the Gradle plugin reads your `mapping.txt` file and automatically uploads it to your self-hosted GlitchTip API. 

Add the plugin to your project-level `build.gradle.kts`:
```kotlin
plugins {
    id("io.sentry.android.gradle") version "4.14.0" apply false
}
```

Then in your app-level `build.gradle.kts`:
```kotlin
plugins {
    id("com.android.application")
    id("io.sentry.android.gradle")
}

sentry {
    // Crucial: Point Gradle to your Yandex Cloud VM, NOT sentry.io (which is blocked)
    url.set("https://telemetry.teatiers.ru/")
    authToken.set(providers.environmentVariable("GLITCHTIP_AUTH_TOKEN"))
    org.set("teatiers")
    projectName.set("android")
    
    // Auto-generate UUID and upload R8 mappings
    autoUploadProguardMapping.set(true)
    uploadNativeSymbols.set(false)  // Skip to reduce build time unless you use custom C++
}
```

### Method B: Offline Retrace (For Custom Endpoint/ACRA)
If you build a custom endpoint to keep resource overhead to absolute zero, you do not need server-side mapping uploads. Instead, you can map the traces locally on your development machine.

Every time you build a release APK, Android Studio generates a ProGuard mapping file at:
`app/build/outputs/mapping/release/mapping.txt`

When you fetch an obfuscated stack trace from your database, run Google's `retrace` tool (bundled with the Android SDK Command-line tools):

```bash
# Path to your Android SDK cmdline tools
~/Android/Sdk/tools/proguard/bin/retrace.sh mapping.txt obfuscated_trace.txt > decoded_trace.txt
```

This method keeps build steps entirely offline, eliminates any Gradle network configuration, and keeps the build server independent of API keys.

---

## 6. Room Database Destructive Migration Safety Callback

You mentioned that `fallbackToDestructiveMigration` is enabled in Room. If a future schema change has a mismatch, **Room will silently drop all database tables and recreate them without raising any uncaught exceptions.** Your app will boot successfully but will contain zero data. 

Standard crash reporters (Sentry, ACRA, Firebase) will **never capture this event** because no uncaught thread exception is thrown. 

To prevent this from going unnoticed, you must implement an explicit tracking mechanism utilizing Room's `onDestructiveMigration` callback:

### Client-Side Room Detection Implementation
```kotlin
import android.content.Context
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.sqlite.db.SupportSQLiteDatabase

fun provideDatabase(context: Context): AppDatabase {
    return Room.databaseBuilder(
        context.applicationContext,
        AppDatabase::class.java,
        "teatiers.db"
    )
    .fallbackToDestructiveMigration() // The dangerous wipe setting
    .addCallback(object : RoomDatabase.Callback() {
        override fun onDestructiveMigration(db: SupportSQLiteDatabase) {
            super.onDestructiveMigration(db)
            
            // Mark a flag inside SharedPreferences immediately before tables are dropped
            context.getSharedPreferences("telemetry_prefs", Context.MODE_PRIVATE)
                .edit()
                .putBoolean("wiped_by_destructive_migration", true)
                .putInt("wipe_detected_db_version", db.version)
                .apply()
        }
    })
    .build()
}
```

### Startup Flag Check & Telemetry Payload Delivery
On your main activity launch (or whenever your crash reporting client is initialized), check for this persistent flag, deliver a custom error, and then clear the flag:

```kotlin
import android.os.Build
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        checkAndReportDatabaseWipes()
    }

    private fun checkAndReportDatabaseWipes() {
        val prefs = getSharedPreferences("telemetry_prefs", Context.MODE_PRIVATE)
        val hasWiped = prefs.getBoolean("wiped_by_destructive_migration", false)
        
        if (hasWiped) {
            val dbVersion = prefs.getInt("wipe_detected_db_version", -1)
            
            lifecycleScope.launch(Dispatchers.IO) {
                // Construct the payload
                val traceMessage = "CRITICAL: Database was destructively wiped on version: $dbVersion"
                
                // If using Sentry/GlitchTip:
                // Sentry.captureException(Exception(traceMessage))
                
                // If using Custom Spring Controller:
                sendCrashToCustomEndpoint(
                    appVersion = BuildConfig.VERSION_NAME,
                    errorMessage = traceMessage,
                    stackTrace = "RoomDatabase.Callback.onDestructiveMigration invoked."
                )
                
                // Clear the persistence flag after successful queueing/sending
                prefs.edit().putBoolean("wiped_by_destructive_migration", false).apply()
            }
        }
    }
}
```

---

## 7. Strategic Recommendations

### Keep vs. Skip Decision
**Decision: KEEP crash/error telemetry for the public MVP.** 
Flying blind on a local-first app is extremely dangerous. Without telemetry, you will not receive any user crash reports when severe updates or silent Room migrations erase local data. However, to preserve the app's local-first privacy commitments and fit onto your constrained **4 GB Yandex Cloud VM**, you should implement a **custom crash endpoint** rather than introducing heavy third-party logging platforms.

---

### Implementation Spec: Custom Crash Endpoint (Recommended MVP Option)

```
[Android UncaughtExceptionHandler] (Captures crashes & Destructive Migrations)
                │
                ▼ (Offline File Buffer: queue payload in pending_crash.json)
                │
                ▼ (On Next Boot, if Privacy Consent == Opt-In)
                │
         [HTTPS POST JSON]
                │
                ▼ (Caddy Reverse Proxy)
                │
       [Spring Boot REST Controller] (Saves to crash_reports table)
```

#### Step 1: Add a Crash Report Table to PostgreSQL
```sql
CREATE TABLE crash_reports (
    id SERIAL PRIMARY KEY,
    timestamp TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    app_version VARCHAR(50) NOT NULL,
    os_version INT NOT NULL,
    device_model VARCHAR(100) NOT NULL,
    error_message TEXT NOT NULL,
    stack_trace TEXT NOT NULL
);
```

#### Step 2: Implement the Spring Boot Controller Endpoint
```kotlin
import org.springframework.web.bind.annotation.*
import org.springframework.jdbc.core.JdbcTemplate
import java.time.LocalDateTime

@RestController
@RequestMapping("/api/telemetry")
class TelemetryController(private val jdbcTemplate: JdbcTemplate) {

    @PostMapping("/crash")
    fun receiveCrashReport(@RequestBody payload: CrashPayload) {
        jdbcTemplate.update(
            "INSERT INTO crash_reports (app_version, os_version, device_model, error_message, stack_trace) VALUES (?, ?, ?, ?, ?)",
            payload.appVersion,
            payload.osVersion,
            payload.deviceModel,
            payload.errorMessage,
            payload.stackTrace
        )
    }
}

data class CrashPayload(
    val appVersion: String,
    val osVersion: Int,
    val deviceModel: String,
    val errorMessage: String,
    val stackTrace: String
)
```

#### Step 3: Implement the Android Custom Exception Handler
```kotlin
import android.content.Context
import android.os.Build
import org.json.JSONObject
import java.io.File

class GlobalCrashReporter(private val context: Context) : Thread.UncaughtExceptionHandler {
    private val defaultHandler = Thread.getDefaultUncaughtExceptionHandler()

    override fun uncaughtException(thread: Thread, throwable: Throwable) {
        try {
            // Check privacy consent before saving any telemetry
            val isConsented = context.getSharedPreferences("telemetry_prefs", Context.MODE_PRIVATE)
                .getBoolean("consent_telemetry", false)

            if (isConsented) {
                val crashFile = File(context.filesDir, "pending_crash.json")
                val payload = JSONObject().apply {
                    put("appVersion", context.packageManager.getPackageInfo(context.packageName, 0).versionName)
                    put("osVersion", Build.VERSION.SDK_INT)
                    put("deviceModel", "${Build.MANUFACTURER} ${Build.MODEL}")
                    put("errorMessage", throwable.localizedMessage ?: "Unknown Error")
                    put("stackTrace", throwable.stackTraceToString())
                }
                crashFile.writeText(payload.toString())
            }
        } catch (e: Exception) {
            // Prevent recursive crashes
        } finally {
            // Force standard system crash behavior so the user knows the app stopped
            defaultHandler?.uncaughtException(thread, throwable)
        }
    }
}
```

Initialize this handler inside your custom `Application` subclass:
```kotlin
class TeaTiersApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        Thread.setDefaultUncaughtExceptionHandler(GlobalCrashReporter(this))
        
        // Scan for pending_crash.json and trigger upload task if consent is true
    }
}
```

---

### Alternative Implementation Spec: GlitchTip
If your crash volume scales and you require a dedicated dashboard with visual metrics, use **GlitchTip** (instead of Sentry) for lightweight management on your Yandex Cloud VM.

#### 1. Android Coordinates Pinned
```kotlin
// app/build.gradle.kts
dependencies {
    implementation("io.sentry:sentry-android:7.22.6")
}
```

#### 2. Caddy Reverse Proxy Configuration (`Caddyfile`)
Routing telemetry directly to your VM through Caddy:

```caddy
telemetry.teatiers.ru {
    reverse_proxy localhost:8000
    
    log {
        output file /var/log/caddy/telemetry_access.log
    }
}
```

---

## 8. High-Quality References

1. **Sentry Sanctions Compliance Policy (September 2024):** Details on the mandatory block of Sentry SaaS services in Russia.
2. **GlitchTip Official Install Documentation:** Architecture options and memory resource requirements.
3. **Room Database Callback Guide:** Overview of Room's lifecycle callbacks, including destructive migrations.
4. **Android Command-line Retrace Tool Reference:** Documentation on off-line symbolication utilizing standard R8 mapping files.