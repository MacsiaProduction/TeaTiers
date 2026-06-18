## Executive Summary

**Recommendation: Adopt GlitchTip self-hosted + Sentry Android SDK.**

GlitchTip is the only solution that is simultaneously:
- **GMS-free** (uses standard Sentry SDK with zero Google dependencies)
- **Light enough** to run on your existing 4 GB / 2 vCPU VM alongside Postgres + Caddy + JVM + OCR
- **Sentry SDK-compatible** (no code changes vs Sentry)
- **MIT-licensed** (truly open source, unlike Sentry's BSL)
- **Feature-complete** for crash/error telemetry with a polished UI

Sentry self-hosted is **infeasible** on your current VM (requires 8–16 GB RAM minimum). ACRA is viable but requires building/maintaining a custom backend and lacks modern symbolication workflows. Countly is an analytics platform first, with crash reporting as an add-on.

---

## 1. Candidate Comparison

| Candidate | License | GMS-Free? | Self-Host Footprint | PII Default | Symbolication | Maintenance Health |
|-----------|---------|-----------|---------------------|-------------|---------------|-------------------|
| **Sentry Self-Hosted** | BSL (source-available, not OSS) | ✅ SDK has no GMS deps | **8–16 GB RAM min**; 4+ CPU cores; 50+ GB storage | Full breadcrumbs, device info, custom context | ✅ Gradle plugin auto-uploads ProGuard mappings | Active; heavy ops burden |
| **GlitchTip** | MIT | ✅ (Sentry SDK, no GMS) | **256–512 MB RAM**; 1 vCPU; ~30 GB for 1M events/mo | Same as Sentry (SDK-level) | ✅ Same Sentry Gradle plugin | Active; v5.2 released Nov 2025 |
| **ACRA** | Apache-2.0 | ✅ No GMS deps | **Minimal** (HTTP endpoint only); backend (Acrarium) light | Fully customizable | ⚠️ Manual or custom; no built-in Gradle plugin | Active (v5.13.1 Sep 2025); backends unmaintained |
| **Countly** | AGPL (self-hosted) / commercial | ✅ SDK has no GMS deps | ~2 GB RAM min (server stack) | Full analytics + crash data | ✅ Plugin-based | Active; analytics-first |
| **Bugsnag** | Proprietary (not OSS) | ✅ SDK no GMS | SaaS-only (no self-host) | Full device/context | ✅ Built-in | Commercial only |

---

## 2. Detailed Analysis

### Sentry Self-Hosted — ❌ Not Feasible

**Resource Requirements:**
- Minimum: **4 CPU cores, 16 GB RAM, 50 GB storage**
- Production: 8–16 cores, 16–32 GB RAM
- Runs ~30+ containers in the `getsentry/self-hosted` Compose stack

Your VM: **2 vCPU, 4 GB RAM** → Sentry alone would exceed total memory by 4×. It cannot coexist with your existing Postgres, JVM, Caddy, and OCR sidecar.

**GMS Status:** ✅ The `sentry-android` SDK has **no Google Play Services dependency**. It works on no-GMS devices.

**License:** Business Source License (BSL) — source-available but not open source; converts to MIT after 3 years.

---

### GlitchTip — ✅ Recommended

**Resource Requirements:**
- **Recommended: 512 MB RAM, x86 or arm64 CPU**
- **Minimum: 256 MB RAM** (all-in-one setup)
- Can run on **128 MB + swap** with careful configuration
- Disk: ~30 GB for 1 million events/month
- PostgreSQL 14+ required; Valkey/Redis 7+ optional

**GMS Status:** ✅ Uses the official Sentry Android SDK, which has no GMS dependencies.

**Sentry SDK Compatibility:** ✅ "Every official Sentry SDK — for JavaScript, Python, PHP, Ruby, Go, Java, .NET, mobile, and more — works unchanged, just by pointing it at your GlitchTip DSN".

**License:** MIT — truly open source, allows commercial use, modification, and redistribution.

**Why GlitchTip over Sentry:** It's a lightweight reimplementation of Sentry's API, created after Sentry moved away from open source. Same workflow, same SDKs, **fraction of the resource footprint**.

---

### ACRA — ✅ Viable but Higher Effort

**Capabilities:**
- Sends crash reports via **HTTP/JSON to any endpoint**
- Also supports email, custom senders
- Fully customizable report fields
- Works on no-GMS devices

**Backend Status:**
- **Acrarium** — official backend, "still in active development"
- **Acralyzer** — unmaintained (CouchDB-based)
- No production-ready, polished self-host backend with UI — you'd need to build or adapt one

**Symbolication:** No built-in Gradle plugin for ProGuard mapping upload. You'd need to implement manual mapping upload or use `sentry-cli` separately.

**Why ACRA is not recommended:** While the library is actively maintained, the backend ecosystem is weak. You'd spend significant effort building/maintaining a receiver + UI + symbolication pipeline that GlitchTip gives you out of the box.

---

### Countly — ⚠️ Not a Dedicated Crash Solution

- **Analytics-first platform** with crash reporting as a feature
- Self-hosted server requires more resources (~2 GB RAM min)
- **AGPL license** for self-hosted (viral copyleft) vs commercial licensing
- Overkill for pure crash telemetry

---

### Bugsnag — ❌ No Self-Host Option

- Proprietary, SaaS-only
- Not suitable for a self-hosted/local-first privacy promise

---

## 3. GMS-Free Verification Summary

| SDK | GMS Dependencies? | Evidence |
|-----|-------------------|----------|
| `io.sentry:sentry-android` | ❌ None | "Sentry does not depend on GMS" |
| `org.acra:acra-*` | ❌ None | Works for apps not delivered via Google Play |
| Countly Android SDK | ❌ None | Flutter SDK "without GMS and Firebase" |
| Bugsnag Android SDK | ❌ None | NDK notifier has no GMS deps |

**All candidates are GMS-free.** The Sentry/GlitchTip path uses `sentry-android` which has zero Play Services dependencies.

---

## 4. Privacy & Data Minimization

### Default Capture (Sentry/GlitchTip SDK)
By default, the Sentry Android SDK captures:
- Stack traces
- Device model, OS version, app version
- **Breadcrumbs** (user interactions, lifecycle events)
- **Custom tags/context** you add
- **Environment** (release/debug)

### PII Scrubbing Configuration

```kotlin
// build.gradle.kts — Sentry SDK
implementation("io.sentry:sentry-android:8.12.0")

// Application.kt
Sentry.init { options ->
    options.dsn = "https://<key>@<glitchtip-host>/<project-id>"
    options.environment = BuildConfig.BUILD_TYPE
    
    // Before-send hook — scrub ALL user content
    options.beforeSend = BeforeSendCallback { event, hint ->
        // 1. Strip all breadcrumbs that might contain user data
        event.breadcrumbs?.clear()
        
        // 2. Remove any custom tags that could leak PII
        event.tags?.remove("user_id")
        event.tags?.remove("tea_name")
        event.tags?.remove("note_preview")
        
        // 3. Strip extra context
        event.extra?.clear()
        
        // 4. Keep only: stack trace + device info + app version
        // (event.contexts.device, event.contexts.app are fine)
        
        event
    }
    
    // Disable automatic breadcrumbs that could capture PII
    options.isEnableUserInteractionBreadcrumbs = false
    options.isEnableSystemEventBreadcrumbs = true  // Keep lifecycle only
    options.isEnableAutoSessionTracking = true
}
```

### Custom Non-Fatal Event for Room Migration

To surface the specific Room `fallbackToDestructiveMigration` risk:

```kotlin
// In your Room Database class or Migration callback
Room.databaseBuilder(context, AppDatabase::class.java, "app.db")
    .fallbackToDestructiveMigration()
    .addCallback(object : RoomDatabase.Callback() {
        override fun onDestructiveMigration(db: SupportSQLiteDatabase) {
            // Send as Sentry non-fatal event
            Sentry.captureMessage(
                "Room destructive migration occurred — data wiped!",
                SentryLevel.FATAL
            )
            // Also send a breadcrumb for context
            Sentry.addBreadcrumb("Database wiped via destructive migration")
        }
    })
    .build()
```

**Privacy Disclosure:** Since you're not sending user content (only stack traces + device/OS/app version), this likely **does not require new consent** under most privacy frameworks. However, the first-run/Settings screen should disclose:
> "The app sends anonymized crash reports (stack traces, device model, OS version) to help improve stability. No personal data, tea names, notes, or photos ever leave your device."

**Opt-in vs Opt-out:** Recommend **opt-out** (send by default with clear disclosure) for MVP crash telemetry, as it's essential for operational stability and contains no user data.

---

## 5. ProGuard/R8 Symbolication

### Sentry/GlitchTip Path (Recommended)

```kotlin
// build.gradle.kts (app module)
plugins {
    id("com.android.application")
    id("io.sentry.android.gradle") version "4.14.0"  // Latest as of 2025
}

sentry {
    autoUploadProguardMapping = true  // Auto-uploads ProGuard/R8 mappings
    uploadNativeSymbols = false
    includeDependencies = false
}
```

The Sentry Gradle plugin auto-uploads the mapping file during the release build. This works with **GlitchTip** because GlitchTip implements the Sentry API.

**No Google infra required** — the plugin uploads to your self-hosted GlitchTip instance.

---

## 6. Hosting Shape — GlitchTip on Your Existing VM

### Docker Compose Configuration

```yaml
# docker-compose.yml addition to your existing stack
services:
  glitchtip-web:
    image: glitchtip/glitchtip:latest
    environment:
      - DATABASE_URL=postgres://glitchtip:${GLITCHTIP_DB_PASS}@postgres:5432/glitchtip
      - SECRET_KEY=${GLITCHTIP_SECRET}
      - EMAIL_URL=smtp://...
      - DEBUG=false
      - DEFAULT_FROM_EMAIL=errors@teatiers.app
    ports:
      - "8000:8000"
    depends_on:
      - postgres
    restart: unless-stopped
    mem_limit: 512m

  glitchtip-worker:
    image: glitchtip/glitchtip:latest
    command: python manage.py rqworker default
    environment:
      - DATABASE_URL=postgres://glitchtip:${GLITCHTIP_DB_PASS}@postgres:5432/glitchtip
      - SECRET_KEY=${GLITCHTIP_SECRET}
    depends_on:
      - postgres
    restart: unless-stopped
    mem_limit: 256m

  # Add to your existing Postgres service:
  postgres:
    # ... existing config ...
    environment:
      - POSTGRES_DB=glitchtip  # additional DB
```

### Resource Impact on Your 4 GB / 2 vCPU VM

| Component | RAM | CPU |
|-----------|-----|-----|
| GlitchTip web | ~256–512 MB | Light |
| GlitchTip worker | ~128–256 MB | Light |
| PostgreSQL (existing) | Already running | — |
| **Total additional** | **~384–768 MB** | Minimal |

**Your 4 GB VM can comfortably run this** alongside Postgres, Caddy, the Spring Boot JVM, and OCR sidecar.

### Caddy Reverse Proxy

```caddy
# Caddyfile addition
errors.teatiers.app {
    reverse_proxy glitchtip-web:8000
}
```

---

## 7. Exact Dependencies

```kotlin
// build.gradle.kts (app module)
dependencies {
    // Sentry SDK — works with GlitchTip
    implementation("io.sentry:sentry-android:8.12.0")
}

// build.gradle.kts (project root)
plugins {
    id("io.sentry.android.gradle") version "4.14.0" apply false
}

// build.gradle.kts (app module)
plugins {
    id("io.sentry.android.gradle")
}

sentry {
    autoUploadProguardMapping = true
    tracingInstrumentation {
        enabled = false  // Disable performance tracing to save resources
    }
}
```

**Versions verified:**
- `io.sentry:sentry-android:8.12.0` — latest stable as of 2025
- `io.sentry.android.gradle:4.14.0` — latest as of 2025
- GlitchTip: latest (v5.2 as of Nov 2025)

---

## 8. Migration-Safety Angle

**Would GlitchTip surface a silent Room data-wipe?**

**Yes — if you add the explicit signal.** The standard crash handler would not catch `fallbackToDestructiveMigration` because it's not a crash — it's a graceful (but destructive) migration.

**Implementation:**

```kotlin
// 1. Register a destructive migration callback (as shown above)
// 2. This sends a non-fatal event to GlitchTip
// 3. You can also add a breadcrumb on every app start showing DB version

Sentry.addBreadcrumb(
    "DB version: ${AppDatabase.getVersion()}, schema hash: ${getSchemaHash()}"
)
```

**Without this explicit signal, no telemetry system would surface the wipe.** The app doesn't crash — it just loses data silently. The developer would never know. So regardless of vendor, you **must** add this custom non-fatal event.

---

## 9. Recommendation

### ✅ Adopt GlitchTip + Sentry Android SDK for MVP

**Rationale:**
- **Fits your VM** — 256–512 MB RAM vs Sentry's 16 GB
- **GMS-free** — works on RuStore/no-GMS devices
- **Sentry SDK compatible** — no code changes vs Sentry, rich ecosystem
- **MIT licensed** — truly open source, no commercial restrictions
- **Symbolication works** — Sentry Gradle plugin auto-uploads ProGuard maps
- **Privacy-controllable** — `beforeSend` hook scrubs all user content
- **Active maintenance** — v5.2 released Nov 2025
- **Self-hosted** — data never leaves your Yandex VM, fulfills local-first privacy promise

**Skip Sentry self-hosted** — too heavy for your 4 GB VM; would require a second large VM or cloud upgrade.

**Skip ACRA** — viable but requires building/maintaining a custom backend + symbolication pipeline; not worth the effort when GlitchTip gives you everything OOTB.

**Skip Countly/Bugsnag** — wrong fit (analytics-first or SaaS-only).

---

## 10. Reference Links

1. **GlitchTip Installation Guide** — system requirements (256–512 MB RAM)  
   https://glitchtip.com/documentation/install

2. **GlitchTip — Sentry SDK Compatibility** — "every official Sentry SDK works unchanged"  
   https://www.hostinger.com/applications/glitchtip

3. **Sentry Android SDK — No GMS Dependencies** — confirmed on StackOverflow  
   https://stackoverflow.com/questions/64113563/can-we-use-sentry-with-nogms

4. **Sentry Gradle Plugin — ProGuard Mapping Upload** — auto-upload configuration  
   https://docs.sentry.io/platforms/android/proguard/

5. **ACRA GitHub Repository** — active, Apache-2.0, works without Google Play  
   https://github.com/ACRA/acra

6. **Sentry Self-Hosted Resource Requirements** — 4 CPU / 16 GB RAM minimum  
   Various sources including GitHub self-hosted Sentry repos