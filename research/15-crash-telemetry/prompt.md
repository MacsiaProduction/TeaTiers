# 15-crash-telemetry — GMS-free, self-hostable crash/error telemetry for a local-first RuStore Android app

english text only report with max effort, max coverage and details.

## Context

Project: **TeaTiers** — a **local-first** Android (Kotlin + Jetpack Compose, min/target SDK pinned)
tier-list app for teas. All user data (boards, tiers, teas, notes, photos, ratings, purchase places)
lives **on-device in Room**; there are **no accounts and no cloud user data**. A small Kotlin/Spring Boot
catalog+enrichment backend runs on a **single Yandex Cloud VM** (Postgres + Caddy + the server in
`docker-compose`, OpenTofu IaC, ~4 GB / 2 vCPU). Distribution target is **RuStore + sideload**, i.e.
**no Google Play Services (no GMS)** on the device, so Crashlytics/Firebase and any GMS-dependent SDK are
out. The app currently ships with `isMinifyEnabled=false` and **no crash/error reporting of any kind**.

The specific risk driving this: the app uses Room with `fallbackToDestructiveMigration` and
`exportSchema=false` today (a known pre-public blocker). If a future schema bump is mishandled after public
release, it **silently wipes the user's local data** — and with no telemetry the developer would **never
know**. Local-first + no backend session means a post-release crash or data-loss event is currently
invisible. We need to decide whether to add GMS-free, ideally self-hostable, privacy-respecting crash/error
telemetry before the first public APK, and if so, which solution.

## Objective

Decide **keep vs skip** crash/error telemetry for the public MVP, and if keep, **which open-source,
GMS-free solution** to adopt and how to host it (ideally on or alongside the existing single Yandex VM,
without a Google dependency and without violating the local-first privacy promise).

## Questions

1. **Candidates.** Compare, with exact current versions/artifacts and licenses:
   - **Sentry** self-hosted (the `getsentry/self-hosted` Docker Compose stack) + the `sentry-android`
     Kotlin SDK — is the Android SDK GMS-free? What are its resource requirements (the self-hosted stack
     is famously heavy — does it fit alongside a 4 GB / 2 vCPU VM already running Postgres + a JVM + Caddy +
     an OCR sidecar, or does it need its own box)?
   - **GlitchTip** (Sentry-SDK-compatible, lighter self-host) — does the official `sentry-android` SDK point
     at a GlitchTip DSN unchanged? Resource footprint vs Sentry self-hosted? License?
   - **ACRA** (`org.acra:acra-*`) — fully on-device crash collector; what backends can it POST to without
     GMS (HTTP/JSON to our own Spring endpoint, email, etc.)? What does a minimal own-backend receiver cost
     to build/operate?
   - Any other credible GMS-free option (e.g. **Bugsnag** OSS tiers, **Countly** crash add-on, a plain
     `Thread.setDefaultUncaughtExceptionHandler` → our own `/crash` endpoint). Note which are dead/unmaintained.
2. **GMS-free verification.** For each Android SDK, confirm it has **no Google Play Services / Firebase
   transitive dependency** (so it installs and runs on a no-GMS RuStore device). Flag any that secretly pull GMS.
3. **Self-host fit.** Which option is realistically self-hostable on the existing single small Yandex VM
   (or a second cheap VM), with rough RAM/CPU/disk needs and the minimal Docker Compose shape? Where does
   each fall back to a hosted SaaS, and is that SaaS reachable/compliant from Russia (egress, data
   residency, ToS on storing user-derived data)?
4. **Privacy / data-minimization.** TeaTiers' promise is that notes/photos/ratings/locations never leave
   the device. A crash report must therefore **not** carry user content (tea names, notes, photo URIs,
   breadcrumbs with PII). For each candidate: what does it capture by default, and how do you scrub/limit
   it to stack traces + device/OS/app-version only (PII-free breadcrumbs, before-send hooks, sampling)?
   How does this interact with the existing first-run/Settings privacy disclosure (would adding telemetry
   require new consent copy, opt-in vs opt-out)?
5. **ProGuard/R8.** With release minification (which we must enable), how is symbolication/deobfuscation
   handled per candidate (mapping-file upload, Gradle plugin), and does that toolchain need GMS or Google
   infra?
6. **Recommendation.** A clear pick (or a clear "skip for MVP, add later") with the rationale, the exact
   dependency coordinates + version, the hosting shape, and the privacy-config snippet outline. Include the
   migration-safety angle: would this telemetry actually have surfaced a silent Room data-wipe (e.g. via a
   custom non-fatal event on a destructive-migration callback), or do we also need an explicit
   migration/breadcrumb signal regardless of vendor?

## Evidence standards

- Prefer maintained upstream source / official docs over blog posts.
- Pin exact versions; explicitly flag anything you are not certain exists or is unmaintained.
- Cite every claim with a link and its publication date; prefer recent sources.
- Be explicit about GMS-free status and about Russia-reachability/ToS for any hosted fallback.

## Return

A comparison table (candidate × license, GMS-free?, self-host footprint, PII-default, symbolication,
maintenance health) + a single recommended pick (or skip-for-MVP) + the exact dependency/version + a
minimal hosting/Compose outline + a PII-scrubbing config outline + 3–5 high-quality reference links.