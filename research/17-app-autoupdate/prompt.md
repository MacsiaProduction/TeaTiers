# 17-app-autoupdate — in-app self-update for a GMS-free, Russia-first sideloaded Android APK distributed via GitHub Releases

english text only report with max effort, max coverage and details.

## Context

Project: **TeaTiers** — a local-first Android (Kotlin + Jetpack Compose) tier-list app for teas. Users
create boards, rank teas into custom tiers, attach a purchase location, notes, photos, and a 0–5 flavor
profile. **All user data lives on-device (Room); there are no accounts and no server-side user data.** A
small Kotlin/Spring Boot catalog+enrichment backend runs on a **single Yandex Cloud VM** behind Caddy
(TLS) at `https://tea.macsia.fun/api/v1/`; the app already makes anonymous HTTPS calls to it (catalog
search, `/resolve`, opt-in OCR). The audience is **Russia-first**.

**Distribution decision (just locked):** the MVP ships as a **signed release APK published on GitHub
Releases** — **no Google Play, no RuStore, no other app marketplace** (RuStore/marketplaces are deferred
post-MVP). Because there's no store, the app needs its **own in-app auto-update** mechanism. This run
decides how to build it.

Hard constraints already locked by prior decisions — your answer MUST respect these:

- **No Google Play Services (GMS).** The app must run on de-Googled / no-GMS devices. Anything that
  depends on GMS is out: the Play **In-App Updates** library (`com.google.android.play:app-update`) is
  **GMS-only and cannot be used**. Flag any proposed library/SDK that pulls in GMS.
- **No VPN, no Western egress proxy.** Every network call is direct. The app talks to the Yandex backend
  and (for now) GitHub. **Reachability from inside Russia without a VPN is a first-class concern** —
  GitHub (api.github.com, github.com, `objects.githubusercontent.com` release-asset CDN) has at times
  been throttled/blocked for Russian ISPs. Treat "is GitHub reliably reachable in RU?" as something to
  verify, not assume, and design for the case where it isn't.
- **Local-first / no accounts / no PII.** The update check must not introduce accounts, logins, device
  identifiers, or any PII. An anonymous version check + anonymous APK download only.
- **Single 4 GB / 2 vCPU VM** already runs Caddy + Spring + Postgres + an OCR sidecar (~3.4 GB
  committed). Any "host it ourselves" option must fit there (a static file + a tiny JSON manifest is
  fine; a heavy update server is not). Yandex Object Storage is available as a cheap static host.
- **Android targets:** `minSdk = 26`, `targetSdk = 36`, `applicationId = com.macsia.teatiers`,
  current `versionCode = 1`, `versionName = "0.1.0"`. Single-APK (not an App Bundle — we control
  signing and ship one universal APK).

The app is **not public yet** — autoupdate is being built now as a feature for internal/tester
distribution, ahead of an eventual public launch. So "good enough + safe" beats "perfect", but the
**security of self-installing an APK** must be right from day one (a self-updater is a code-execution
channel).

## Objective

Decide **how TeaTiers should implement in-app auto-update** for a signed APK distributed via GitHub
Releases to Russia-first, GMS-free devices: where the version-check + APK download come from (and how to
stay reachable from RU), the Android install mechanism, how to verify the APK is authentic before
installing, the manifest/version-check API shape, the update UX, and what (if any) open-source library
to reuse vs. build a small custom updater.

## Questions

1. **Android install mechanism (GMS-free), API 26→36.** What is the correct, current way for an app to
   install an APK it downloaded — the `PackageInstaller` Session API vs. the legacy
   `ACTION_INSTALL_PACKAGE`/`ACTION_VIEW` intent? Cover: the `REQUEST_INSTALL_PACKAGES` permission, the
   per-source **"install unknown apps"** user grant (`canRequestPackageInstalls()` / Settings
   `MANAGE_UNKNOWN_APP_SOURCES`), behavior and any new restrictions across API 26, 29, 31, 33, 34, 35,
   36, the FileProvider/content-URI requirement, whether an install can proceed without a foreground UI,
   the user-confirmation dialog, and how the app relaunches after a successful self-update. Give a
   minimal correct Kotlin `PackageInstaller` flow. Flag any OEM (Xiaomi/MIUI, Huawei, Samsung) quirks
   common on RU devices.

2. **Update authenticity & security (this is the critical one).** A self-updater that installs a
   downloaded APK is a code-execution path. How do we guarantee the downloaded APK is the genuine
   TeaTiers build and not a swapped/malicious one? Cover: Android's **same-signing-certificate**
   enforcement on update (does it fully protect us, and what are the edge cases — e.g. first install vs
   update, key rotation), whether/how to **verify the APK's signer cert in-app before invoking the
   installer** (e.g. `PackageManager.getPackageArchiveInfo` + `GET_SIGNING_CERTIFICATES`, pinning our
   release cert's SHA-256), **SHA-256 hash pinning** of the APK from a trusted manifest, **TLS** for the
   download, **downgrade protection** (monotonic `versionCode`, reject older), and how to sign/verify the
   **manifest** itself so a MITM can't point us at an attacker APK. What's the minimum bar vs. the
   belt-and-suspenders bar?

3. **Where the version check + APK live, and RU reachability.** Compare hosting the **version manifest**
   and the **APK download** across: (a) GitHub Releases (`api.github.com/repos/.../releases/latest` for
   the check + the release `.apk` asset on `objects.githubusercontent.com`); (b) our **first-party
   Yandex backend** serving a tiny JSON manifest from `tea.macsia.fun` (the app already talks to it) that
   points at the APK; (c) the APK mirrored to **Yandex Object Storage**; (d) a self-hosted **F-Droid
   repository** (fdroidserver) the app subscribes to. For each: **reachability from inside Russia without
   a VPN** (is GitHub's API + asset CDN reliably reachable? is there throttling/SNI-blocking? cite recent
   evidence), the GitHub anonymous API rate limit (60 req/hr/IP) and how it interacts with a polling
   check, caching/ETag, and operational cost on the 4 GB VM. Recommend a primary + fallback (e.g.
   first-party manifest on the always-reachable backend pointing at a GitHub asset, with a Yandex
   Object Storage mirror when GitHub is throttled).

4. **Version-check / update manifest design.** Specify the JSON manifest the app polls: fields
   (`latestVersionCode`, `latestVersionName`, `minSupportedVersionCode`, `apkUrl`, `apkSha256`,
   `releaseNotes`, `mandatory`, mirror URLs), how the app decides *update available* vs *forced update*
   (tie to a `minSupportedVersionCode` so we can hard-cut very old clients), polling cadence + ETag/If-
   None-Match to respect rate limits, and how this should be **served by the existing Spring backend**
   (this run feeds an `/api/v1` version/upgrade endpoint the app will also use for general API-version
   gating). Keep it anonymous + no-PII.

5. **Open-source libraries vs. build-our-own.** Enumerate the maintained, **GMS-free** options for
   sideloaded self-update and rate each: e.g. **AppUpdater** (javiersantos), **Obtainium**'s approach
   (how it actually installs), **F-Droid** client/`fdroidserver`, **DownloadManager + PackageInstaller**
   hand-rolled, any current Kotlin libraries. For each: exact maven/repo coords + latest version,
   license (SPDX), last-release date, whether it pulls in GMS, whether it supports our manifest/security
   model, and RU-viability. **Explicitly flag any that are unmaintained, GMS-dependent, or that you
   cannot confirm exist.** Then recommend: a small well-understood custom updater (DownloadManager/OkHttp
   download → SHA-256 + signer verify → PackageInstaller) vs. a library.

6. **Update UX & policy (Russia-first, respectful).** How should the update prompt behave: optional
   "update available" vs. `mandatory`/below-`minSupported` forced update; not nagging; deferring on
   metered/roaming networks; a foreground download with progress + the "install unknown apps" first-run
   grant flow + a clear explanation; what to do when the user has denied `REQUEST_INSTALL_PACKAGES`;
   and whether to offer a manual "download from GitHub" fallback link. Note any Russian-market UX
   expectations.

7. **Recommended architecture + phased plan.** Tie it together into a concrete, buildable design for
   TeaTiers: the manifest source(s) + fallback, the download+verify+install pipeline, the security
   checks (signer-pin + sha256 + same-signer + downgrade protection), the backend endpoint, the UX, and
   the library-or-custom call — sequenced (what to build first for an internal-tester release, what to
   harden before public). Call out anything that would require relaxing a locked constraint (GMS, VPN,
   accounts) and whether it's worth it.

## Evidence standards

- Prefer maintained upstream source / official Android docs (`developer.android.com`), the actual
  library repos/POMs, and primary sources over blog posts. Cite every claim with a link + its
  publication/access date; prefer recent sources (API 34–36 era).
- **Pin exact versions** (library coords + latest version + SPDX license + last-release date) and
  **explicitly flag anything you are not certain exists** or whose maintenance/GMS-status you couldn't
  confirm — do not assume a library is GMS-free or maintained.
- Be concrete about **RU reachability** (GitHub API + `objects.githubusercontent.com` from Russian
  networks without a VPN): cite recent evidence of throttling/blocking or its absence; don't hand-wave.
- For the security question, distinguish what **Android enforces for you** (same-signer on update) from
  what **we must enforce ourselves** (manifest integrity, sha256 pin, signer pin, downgrade protection).

## Return

1. A **decision: primary + fallback** for the version-check manifest and the APK download host, with the
   RU-reachability reasoning.
2. A **minimal correct Kotlin pipeline**: download → verify (sha256 + signer/same-signer + downgrade) →
   `PackageInstaller` install → relaunch, with the exact permissions/manifest entries and the API-level
   caveats.
3. A **manifest JSON schema** + the matching `/api/v1` version/upgrade endpoint shape (anonymous, no-PII,
   ETag-cached).
4. A **library comparison table** (name · coords+version · license · last release · GMS-free? · supports
   our security model? · RU-viable? · verdict) and a custom-vs-library recommendation.
5. A **security checklist** (what Android enforces vs. what we must) + the update **UX/policy** rules.
6. A **phased build plan** (internal-tester first → public-hardening) and any locked-constraint conflicts.
7. 5–8 high-quality reference links (Android PackageInstaller/REQUEST_INSTALL_PACKAGES docs, the chosen
   library repos/licenses, RU-reachability evidence, F-Droid/Obtainium precedent).
