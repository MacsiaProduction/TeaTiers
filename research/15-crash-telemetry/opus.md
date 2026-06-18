# Crash/Error Telemetry for TeaTiers: KEEP — Adopt Self-Hosted GlitchTip with the Sentry Android SDK

## TL;DR
- **KEEP telemetry for the public MVP, but adopt GlitchTip (self-hosted), not Sentry self-hosted.** Pair it with the official `io.sentry:sentry-android` SDK (MIT, GMS-free — its dependency tree contains no Google Play Services or Firebase) pointed at your own GlitchTip DSN. Sentry's own self-hosted stack is disqualified: its installer hard-fails below 7 GB RAM (errors-only) or 14 GB RAM (feature-complete), which cannot run on a 4 GB VM.
- **Self-host GlitchTip on a second small VM (or carefully alongside the existing one).** GlitchTip is MIT-licensed and its official docs recommend just 1 GB RAM / 1 CPU (256 MB is possible with Valkey disabled), running four lightweight containers (Django web, Celery worker, Redis/Valkey, Postgres). Sentry SaaS is unusable from Russia under OFAC sanctions; a self-hosted instance also sidesteps 152-FZ cross-border concerns.
- **Privacy is preserved by configuration, not by vendor.** `sendDefaultPii` defaults to `false`; add a `beforeSend` hook, disable breadcrumbs/screenshots/attachments, make telemetry opt-in, and fire an explicit non-fatal event from Room's `onDestructiveMigration` callback so a silent data-wipe becomes visible.

## Key Findings

### The migration-safety problem is real and telemetry alone won't fully solve it
A destructive Room migration does **not** crash the app — `fallbackToDestructiveMigration()` deliberately drops and recreates tables, then the app continues normally. So a generic crash reporter would capture **nothing**. Regardless of vendor, you must add an *explicit* signal: override `RoomDatabase.Callback.onDestructiveMigration(db)` (registered via `addCallback`) and fire a manual non-fatal event/breadcrumb. This is the single most important instrumentation decision for TeaTiers, and it is vendor-independent.

### Candidate comparison

| Candidate | License | GMS-free? | Self-host footprint | PII by default | Symbolication (R8/ProGuard) | Maintenance health |
|---|---|---|---|---|---|---|
| **Sentry self-hosted + sentry-android** | Server: FSL (→ Apache-2.0 after 2 yrs); SDK: MIT | **Yes** — SDK deps are only `io.sentry:sentry` + two optional `androidx.lifecycle` modules; no `com.google.android.gms`/Firebase | **Too big.** Installer hard floor 7 GB RAM (`MIN_RAM_HARD=7000`)/2 CPU (errors-only) or 14 GB (`MIN_RAM_HARD=14000`)/4 CPU (feature-complete); ~10–30+ containers (Kafka, ClickHouse, Snuba, Redis, Postgres) | `sendDefaultPii=false` default; rich breadcrumbs/device context captured | Best-in-class; `sentry-android-gradle-plugin` auto-uploads mapping; works against self-hosted URL; no Google infra | Very active |
| **GlitchTip (self-hosted) + sentry-android** | **MIT** (server); SDK MIT | **Yes** (same SDK) | **Fits.** Recommended 1 GB RAM/1 CPU (256 MB possible with Valkey off); 4 containers (Django, Celery, Redis/Valkey, Postgres 14+) | Same SDK defaults (PII-free unless enabled) | Drop-in SDK; mapping upload via `sentry-cli`/gradle plugin pointed at GlitchTip URL — **but JVM ProGuard/R8 deobfuscation support is less mature than Sentry's (verify/flag)** | Active; GlitchTip 6 (2026) |
| **ACRA + own Spring `/crash` endpoint** | Apache-2.0 | **Yes** — on-device only; deps are androidx/Kotlin, no GMS | **Smallest.** No new service — POST JSON to existing Spring Boot backend; you build the receiver/UI | You choose exact `ReportField`s — most privacy-controllable | Manual: keep `mapping.txt`, deobfuscate offline with ProGuard ReTrace; no Google infra | Active: `ch.acra:acra-*` 5.13.1 (published Sep 28, 2025) |
| **ACRA + Acrarium** | Apache-2.0 | Yes | Acrarium needs JVM + MySQL 8 (heavier; no arm64 image) | Same as ACRA | ReTrace-based | Active (same maintainer as ACRA) |
| **Acralyzer (ACRA backend)** | Apache-2.0 | Yes | CouchDB | — | — | **Unmaintained** (per acra.ch docs) — avoid |
| **Firebase Crashlytics** | Proprietary | **No — requires GMS/Firebase** | SaaS only | — | — | Active but disqualified (GMS) |
| **Bugsnag** | Proprietary; on-prem enterprise only | SDK GMS-free | On-prem is enterprise/heavy | — | Good (CLI uploads mapping) | Active; SaaS ToS + Russia reachability uncertain |
| **Countly crash add-on / Embrace / Instabug** | Mixed | Countly self-hostable | Countly heavier (Node+Mongo) | — | — | Countly active; others SaaS-oriented |

### GMS-free verification (primary source)
The Sentry Android SDK is **confirmed GMS-free**. The published POM for `io.sentry:sentry-android-core:8.x` declares only `io.sentry:sentry` (the core Java SDK, MIT); Gradle module metadata adds only `androidx.lifecycle:lifecycle-process` and `androidx.lifecycle:lifecycle-common-java8` (both optional — Sentry's official docs show the verbatim Gradle `exclude` lines for `lifecycle-process` and `lifecycle-common-java8`). There is **no `com.google.android.gms` and no `com.google.firebase` anywhere** in the tree. This installs and runs cleanly on a no-GMS RuStore device. ACRA is likewise pure on-device with no Google dependency (it only needs the `INTERNET` permission to POST). Crashlytics is the opposite — it is built on Firebase/GMS and is out.

### Self-host fit on the 4 GB / 2 vCPU Yandex VM
- **Sentry self-hosted: does not fit.** The installer's `install/_min-requirements.sh` sets `MIN_RAM_HARD=7000` MB / `MIN_CPU_HARD=2` for `errors-only` and `MIN_RAM_HARD=14000` MB / `4` CPU otherwise, and `check-minimum-requirements.sh` aborts the install below those (confirmed via the repo file and DeepWiki's documentation of it). Real-world GitHub issues show even a clean modern install consuming 13+ GB and post-upgrade installs hovering at ~22 GB. It would need its own large box. Also requires SSE4.2 (ClickHouse) and Docker ≥19.03.6 / Compose ≥2.32.2.
- **GlitchTip: fits, but tight on the shared box.** Official docs recommend 1 GB RAM / 1 CPU; the 5.2 release notes (Nov 13, 2025) state that with Valkey disabled (Postgres-only) "you can now run GlitchTip on as low as 256mb ram" (with slower performance). The existing VM already runs Postgres + a JVM server + Caddy + an OCR sidecar inside 4 GB, so the cleanest path is a **second cheap VM** (1–2 GB) — or reuse the existing Postgres (GlitchTip supports an external `DATABASE_URL`, Postgres 14+) and add only the web+worker+Redis containers if you must consolidate.
- **ACRA → own endpoint: fits trivially** — it's just another HTTP route on your existing Spring Boot server writing to the existing Postgres.

### Russia-specific reachability and law
- **Sentry SaaS (sentry.io) is not a viable fallback.** Sentry's official FAQ ("Sentry's Response to U.S.-Russia Sanctions") states: *"The OFAC sanctions prohibit U.S. persons from providing certain 'cloud-based services' to or for the benefit of any person located in Russia. Thus, as of September 10, 2024, no persons located in Russia can access Sentry,"* and Sentry *"no longer accepts payment from any Russian customer effective September 10, 2024."* This stems from OFAC's "Prohibition on Certain Information Technology and Software Services" determination under E.O. 14071, issued June 12, 2024 (effective 12:01 a.m. EDT September 12, 2024), which covers IT support and cloud-based services for enterprise management software. Self-hosting the (FSL-licensed) Sentry software is the only Sentry path, and it's too heavy.
- **152-FZ (data localization):** if crash reports are scrubbed to stack trace + device/OS/app-version only and carry **no** personal data, they are not "personal data of Russian citizens," so the localization mandate is largely moot — but self-hosting on the Russian VM removes all ambiguity and avoids cross-border transfer entirely. Under Law 420-FZ (Nov 2024, effective May 30, 2025), localization fines are 1–6 million rubles (initial) and 6–18 million rubles (repeat) for legal entities, with escalated breach penalties that can reach 1–3% of annual revenue (min 20M, max 500M rubles) — so erring toward in-country self-hosting is the safe call. Hosted GlitchTip (EU/Frankfurt instance, `eu.glitchtip.com`) is a possible fallback but reintroduces cross-border transfer.

### Privacy / data-minimization
- The Sentry Android SDK does **not** send headers/cookies/user identity by default (`sendDefaultPii=false`; on Android `options.isSendDefaultPii`). However, it auto-captures breadcrumbs (including logcat at WARNING+, navigation, UI interactions) and can capture screenshots/view-hierarchy if those integrations are enabled — these are the PII risk for TeaTiers (tea names, notes). Mitigate with: `beforeSend`/`beforeBreadcrumb` hooks that strip or drop data, disabling auto-breadcrumbs/screenshots/attachments, low/no `tracesSampleRate`, plus optional **server-side scrubbing** (and Relay) which both Sentry and GlitchTip can apply.
- **Consent:** crash telemetry that leaves the device is new processing not covered by your current "data stays on device" promise. Make it **opt-in** (off by default), gated behind a clear first-run/Settings toggle, and only call `SentryAndroid.init`/enable ACRA after consent. Opt-in is the defensible posture under both GDPR-style norms and Russian consent rules (Roskomnadzor treats most online identifiers as personal data requiring explicit consent); opt-out crash telemetry that transmits any identifiers is legally riskier.

### ProGuard/R8 (the app must enable `isMinifyEnabled=true` before release)
- **Sentry/GlitchTip:** the `sentry-android-gradle-plugin` auto-generates and uploads the R8 `mapping.txt` on `assembleRelease`; set the plugin `url` to your self-hosted instance and an `authToken` (from a `sentry.properties` / env var). No Google infrastructure is involved. If you prefer, set `autoUploadProguardMapping=false` and upload manually with `sentry-cli upload-proguard` using the generated `sentry-debug-meta.properties` UUID. GlitchTip accepts the same `sentry-cli` uploads; **flag:** GlitchTip's Android JVM deobfuscation has historically lagged Sentry's, so validate end-to-end on a test crash before release.
- **ACRA:** ships its own `proguard.cfg` and keeps `SourceFile,LineNumberTable`; you deobfuscate received reports offline with ProGuard/R8 **ReTrace** against the build's `mapping.txt`. No Google infra.

## Details

### Recommended pick: GlitchTip self-hosted + Sentry Android SDK (opt-in, PII-scrubbed)
Rationale: it is the only option that simultaneously (a) is GMS-free and RuStore-safe, (b) is realistically self-hostable next to your existing stack, (c) avoids the OFAC-sanctioned Sentry SaaS, (d) gives you a real dashboard with grouping/release tracking out of the box, and (e) lets you reuse the mature `sentry-android` SDK and Gradle plugin. Sentry self-hosted is a clear **SKIP** (resource floor). Pure ACRA→own-endpoint is the **ultra-minimal fallback** if you want zero third-party server code and are willing to forgo a UI / build your own receiver — it is the most privacy-maximal because you enumerate exactly which fields leave the device.

### Exact dependency / version coordinates
- SDK: `io.sentry:sentry-android:8.43.2` (latest stable line per Sentry's Android releases page, MIT; June 2026). Initialize with `SentryAndroid.init` (using `Sentry.init` on Android throws `IllegalArgumentException` since 8.0.0).
- Gradle plugin (optional, for mapping upload): `io.sentry.android.gradle` (e.g. `6.9.0`, which auto-adds SDK 8.43.x if not pinned).
- GlitchTip server image: `glitchtip/glitchtip:latest` (MIT; GlitchTip 6, 2026), Postgres 14+, Redis/Valkey 7+ optional.
- Ultra-minimal alternative: `ch.acra:acra-http:5.13.1` (+ `ch.acra:acra-toast` or `acra-dialog` for consent UX), Apache-2.0, published Sep 28, 2025.

### Minimal GlitchTip Compose outline (second small VM, or consolidated)
```yaml
services:
  glitchtip-web:
    image: glitchtip/glitchtip:latest
    environment:
      DATABASE_URL: postgres://glitchtip:PASS@glitchtip-postgres:5432/glitchtip  # or point at existing Postgres
      REDIS_URL: redis://glitchtip-redis:6379/0
      SECRET_KEY: <random>
      GLITCHTIP_DOMAIN: https://errors.example.com
      DEFAULT_FROM_EMAIL: info@example.com
      EMAIL_URL: consolemail://
      ENABLE_USER_REGISTRATION: "false"
      GLITCHTIP_MAX_EVENT_LIFE_DAYS: "30"
    depends_on: [glitchtip-postgres, glitchtip-redis]
  glitchtip-worker:
    image: glitchtip/glitchtip:latest
    command: ./bin/run-celery-with-beat.sh
    environment: *same_env
    depends_on: [glitchtip-postgres, glitchtip-redis]
  glitchtip-postgres:
    image: postgres:15
    environment: { POSTGRES_USER: glitchtip, POSTGRES_PASSWORD: PASS, POSTGRES_DB: glitchtip }
    volumes: [glitchtip-pg:/var/lib/postgresql/data]
  glitchtip-redis:
    image: valkey/valkey:7
volumes: { glitchtip-pg: {} }
```
Front it with your existing Caddy (reverse proxy + TLS) on a subdomain like `errors.example.com`. To consolidate onto the existing VM, drop the bundled Postgres and point `DATABASE_URL` at the existing Postgres, and consider `GLITCHTIP_EMBED_WORKER`/all-in-one mode (and disabling Valkey) to shave RAM toward the documented 256 MB floor. For normal operation budget ~512 MB–1 GB.

### PII-scrubbing config outline (recommended SDK)
```kotlin
// Only call after explicit opt-in consent is granted.
SentryAndroid.init(context) { options ->
    options.dsn = "https://<key>@errors.example.com/<id>"   // GlitchTip DSN
    options.isSendDefaultPii = false                         // default; keep explicit
    options.isAttachScreenshot = false                       // no UI capture
    options.isAttachViewHierarchy = false
    options.tracesSampleRate = 0.0                           // errors only, no perf PII
    options.maxBreadcrumbs = 20
    options.environment = "production"
    options.release = "teatiers@${BuildConfig.VERSION_NAME}"

    // Drop/clean breadcrumbs that could contain tea names, notes, queries, URIs
    options.setBeforeBreadcrumb { crumb, _ ->
        if (crumb.category in listOf("ui.click", "navigation", "query")) null else crumb
    }
    // Strip anything user-derived before the event leaves the device
    options.setBeforeSend { event, _ ->
        event.user = null
        event.request = null
        event.contexts.remove("app_notes")
        event                                                // return null to drop entirely
    }
}
```
Also disable the Gradle plugin's logcat/DB/file-IO instrumentation breadcrumbs (or set logcat `minLevel` high) so SQL containing tea/note text never becomes a breadcrumb. Add server-side scrubbing rules in GlitchTip/Sentry as defense-in-depth.

### Instrumenting the destructive-migration event (per candidate)
- **Sentry/GlitchTip:**
```kotlin
val callback = object : RoomDatabase.Callback() {
    override fun onDestructiveMigration(db: SupportSQLiteDatabase) {
        Sentry.captureMessage(
            "ROOM_DESTRUCTIVE_MIGRATION from v${db.version}",
            SentryLevel.ERROR
        )
    }
}
Room.databaseBuilder(ctx, AppDatabase::class.java, "teatiers.db")
    .fallbackToDestructiveMigration()   // ideally fallbackToDestructiveMigrationFrom(specific versions)
    .addCallback(callback)
    .build()
```
- **ACRA:** call `ACRA.errorReporter.handleSilentException(IllegalStateException("ROOM_DESTRUCTIVE_MIGRATION v${db.version}"))` from the same callback.
- Because the wipe is non-fatal, **this explicit signal is mandatory in every vendor** — a passive crash handler would miss it. Strongly consider also moving off blanket `fallbackToDestructiveMigration()` to `fallbackToDestructiveMigrationFrom(...)` with real migrations and `exportSchema=true` before public release.

## Recommendations
1. **Before public release (blocker):** enable `exportSchema=true`, commit schema JSONs, write real `Migration` objects, and restrict destructive fallback to enumerated versions. Telemetry is a safety net, not a substitute for migrations.
2. **Adopt GlitchTip self-hosted + `io.sentry:sentry-android:8.43.2`, opt-in.** Stand up GlitchTip on a small second VM (preferred) or consolidate using the existing Postgres. Front with Caddy/TLS.
3. **Ship PII-scrubbed config** (snippet above): `sendDefaultPii=false`, no screenshots/attachments, breadcrumb stripping, `beforeSend` nulling user/request/contexts, `tracesSampleRate=0`.
4. **Instrument the destructive-migration non-fatal event** in the Room callback, plus a breadcrumb on every successful migration for context.
5. **Wire mapping upload:** add the Sentry Gradle plugin with `url` → your GlitchTip instance; **validate deobfuscation on a real release-build test crash** (this is the one GlitchTip risk to confirm). If GlitchTip deobfuscation proves unreliable, keep the `mapping.txt` per release and ReTrace manually.
6. **Consent copy:** add a first-run + Settings toggle ("Help improve TeaTiers by sending anonymous crash reports — no notes, photos, or tea data are ever included"), default OFF; only init the SDK after opt-in.

**Thresholds that change the recommendation:** If you later need native (NDK) symbolication, deep tracing, or Session Replay, or if event volume grows past a few million/month, revisit Sentry self-hosted on a dedicated ≥16 GB box. If you want zero third-party server software and no dashboard, switch to ACRA→own Spring `/crash` endpoint. If a managed option becomes acceptable and cross-border transfer is cleared, hosted GlitchTip (EU) is the low-ops fallback (Sentry SaaS remains off-limits while sanctions stand).

## Caveats
- **GlitchTip Android ProGuard/R8 deobfuscation maturity** is the main open risk — its native/debug-symbol tooling (`glitchtip-cli debug-files upload`) is documented, but JVM mapping deobfuscation has historically trailed Sentry. Verify on a test crash before relying on it; the `mapping.txt`+ReTrace fallback always works.
- **Resource headroom:** GlitchTip's recommended 1 GB (or 256 MB tuned) is for low volume; co-tenanting on the already-loaded 4 GB VM risks OOM under bursty crash storms. A second small VM is safer; rate-limit the SDK and cap event retention (`GLITCHTIP_MAX_EVENT_LIFE_DAYS`).
- **Sentry self-hosted RAM floors** (7 GB/14 GB, from `_min-requirements.sh`) are enforced minimums in the installer, not soft suggestions; community reports of 13–22 GB actual usage confirm it is unrealistic for this project.
- **Sanctions are a moving target:** the Sentry SaaS unavailability stems from OFAC's June 12, 2024 determination (effective Sept 2024); treat any hosted U.S. SaaS (Sentry, Bugsnag) as potentially unreachable/non-compliant for a Russian operator and re-check before depending on it.
- **152-FZ** analysis assumes reports are genuinely PII-free; if you ever attach user identifiers or content, localization and breach-notification obligations (24h to Roskomnadzor, 72h follow-up) attach and self-hosting in Russia becomes mandatory rather than merely advisable.
- Version numbers are current as of June 2026 (`sentry-android` 8.43.2; `ch.acra` 5.13.1; GlitchTip 6); pin exact versions and re-verify before build.

## References
- getsentry/self-hosted system requirements (`_min-requirements.sh`, `check-minimum-requirements.sh`): https://deepwiki.com/getsentry/self-hosted/3.1-system-requirements and https://develop.sentry.dev/self-hosted/
- GlitchTip install docs (resource requirements, Compose, external Postgres): https://glitchtip.com/documentation/install/ and Android SDK setup: https://glitchtip.com/sdkdocs/java-android/
- Sentry Android SDK — manual setup, scrubbing, Gradle/mapping upload: https://docs.sentry.io/platforms/android/manual-setup/ , https://docs.sentry.io/platforms/android/data-management/sensitive-data/ , https://docs.sentry.io/platforms/android/configuration/gradle/
- ACRA senders/backends and current artifacts: https://www.acra.ch/docs/Senders , https://www.acra.ch/docs/Backends , `ch.acra:acra-http:5.13.1` on Maven Central: https://central.sonatype.com/artifact/ch.acra/acra-mail
- Sentry sanctions FAQ + Russia 152-FZ penalties (2025): https://sentry.zendesk.com/hc/en-us/articles/28038067843739-FAQ-Sentry-s-Response-to-U-S-Russia-Sanctions and https://www.recordinglaw.com/world-laws/world-data-privacy-laws/russia-data-privacy-laws/