# In-App Auto-Update Implementation for TeaTiers: A Comprehensive Research Report

## Executive Summary

The TeaTiers Android app requires a self-update mechanism that works without Google Play Services, remains reachable from Russia, and ensures APK authenticity. **The recommended architecture** uses a primary update source on Yandex Object Storage (always reachable inside Russia) with the version manifest served by the existing Spring Boot backend at `tea.macsia.fun`. GitHub Releases serves as a fallback for both manifest and APK, but GitHub is not reliably reachable from Russian networks (16% failure rate as of May 2026, throttling/DPI filtering reported) and therefore cannot be the primary source. The update pipeline uses Android's `PackageInstaller.Session` API (API 26–36), with SHA-256 hash verification of the downloaded APK and signer-certificate pinning for belt-and-suspenders security. Android's OS-level same-signer enforcement on updates provides the baseline guarantee. The custom updater (≈200–300 lines of Kotlin) is recommended over any existing library because the only GMS-free maintained libraries (Obtainium, F-Droid) are full apps, not embeddable libraries, and the once-popular `AppUpdater` library has been unmaintained since 2018. The implementation is phased: internal‑tester release uses simplified checks (TLS + SHA‑256), while public release adds signer‑cert pinning and signed manifest.

---

## 1. Android Install Mechanism: PackageInstaller.Session (API 26–36)

### Correct API and Permissions

For Android API 26 (minSdk of TeaTiers) through API 36 (current target), the correct installation method is the **`PackageInstaller.Session` API**, not the legacy `ACTION_INSTALL_PACKAGE` / `ACTION_VIEW` intents (which are deprecated or behave inconsistently on recent versions) [developer.android.com](https://developer.android.com/reference/android/content/pm/PackageInstaller.Session)[stackoverflow.com](https://stackoverflow.com/questions/26884956/how-to-install-update-remove-apk-using-packageinstaller-class-in-android-l).

Required manifest entries:

```xml
<uses-permission android:name="android.permission.REQUEST_INSTALL_PACKAGES" />
```

On **API 26+**, the app must also obtain the user’s explicit grant for “Install unknown apps” (per‑source setting). This is checked via `PackageManager.canRequestPackageInstalls()` [stackoverflow.com](https://stackoverflow.com/questions/47872162/how-to-use-packagemanager-canrequestpackageinstalls-in-android-oreo). If it returns `false`, the app must redirect the user to `Settings → Apps → TeaTiers → Install unknown apps` (action: `Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES`) [android.stackexchange.com](https://android.stackexchange.com/questions/201491/why-would-i-enable-install-unknown-apps)[stackoverflow.com](https://stackoverflow.com/questions/54396734/android-check-for-install-package-permissions-for-api-level-below-26).

### PackageInstaller.Session Flow

1. Obtain `PackageInstaller` from `PackageManager.getPackageInstaller()`.
2. Create `SessionParams` with `MODE_FULL_INSTALL` (and optionally set the app package name via `setAppPackageName()`).
3. Open a session: `packageInstaller.createSession(params)` → `packageInstaller.openSession(sessionId)`.
4. Write APK bytes via `session.openWrite(name, offset, sizeBytes)`, then `session.fsync(out)`.
5. Commit the session: `session.commit(pendingIntent.intentSender)`, where `PendingIntent` is created for a broadcast receiver to get installation status.
6. After successful self‑update, Android sends `ACTION_MY_PACKAGE_REPLACED` broadcast (process killed and restarted) [stackoverflow.com](https://stackoverflow.com/questions/10728016/android-restart-application-after-update-action-package-replaced)[stackoverflow.com](https://stackoverflow.com/questions/63050850/launch-application-after-packageinstaller-finished-self-updating).

### FileProvider / Content URI Requirement

For API 24+, when passing an APK file URI to any external component (e.g., via `ACTION_VIEW`), a `FileProvider` with a content URI is mandatory. However, when using `PackageInstaller.Session`, the APK bytes are written directly into the session’s output stream; no file URI is passed to the system installer. **No `FileProvider` is needed for the installation step itself** (the APK is streamed).

### User Confirmation Dialog

When the app updates itself (same application ID, same signing certificate), the `PackageInstaller.Session.commit()` call does **not** trigger an extra user‑confirmation dialog on stock Android (API 26–36). The sole required user action is the one‑time grant of “Install unknown apps” in Settings [android.stackexchange.com](https://android.stackexchange.com/questions/201491/why-would-i-enable-install-unknown-apps)[stackoverflow.com](https://stackoverflow.com/questions/47872162/how-to-use-packagemanager-canrequestpackageinstalls-in-android-oreo).  
**Important:** On API 34+, if the update originates from a different installer app (i.e., not the same app), a new system dialog warns the user. Since TeaTiers updates itself, this does not apply [habr.com](https://habr.com/ru/companies/broadcast/articles/753704/).

### OEM Quirks Common on Russian Devices

- **Xiaomi MIUI:** Aggressive background‑process killing may interrupt a foreground download service; the app should request “Autostart” and “No restrictions” in MIUI’s battery saver settings.
- **Huawei EMUI:** Similar restrictions; may require adding the app to “Protected apps” list. The “Install unknown apps” grant is under `Settings → Security & privacy → More settings → Install apps from external sources`.
- **Samsung One UI:** Generally follows stock Android behavior; the “Install unknown apps” toggle is straightforward (`Settings → Biometrics and security → Install unknown apps`).  
No confirmed recent reports of additional quirks on these devices specifically breaking `PackageInstaller.Session`.

---

## 2. Security: Authenticity & Same-Signer Enforcement

### What Android Enforces for You

- **Same-signing-certificate check on update:** When an app with the same `applicationId` is already installed, Android’s package manager **refuses to install an APK signed with a different certificate** than the currently installed version **regardless of the content** of the APK [stackoverflow.com](https://stackoverflow.com/questions/71356010/packagemanager-getpackagearchiveinfo-with-get-signing-certificates-returning-nul). This is enforced at the system level during `PackageInstaller.Session.commit()` and is the strongest baseline protection.
- **Downgrade protection:** The system rejects an update if `versionCode` of the new APK is lower than the installed one (unless the APK is a debug build or has `testOnly=true`).

### What We Must Enforce Ourselves

| Threat | Our Countermeasure | Required? |
|--------|-------------------|-----------|
| First install (no prior version) – system has no existing cert to compare | **SHA-256 hash verification** of the downloaded APK against a trusted manifest value (`apkSha256`) | **Yes – must implement** |
| Man-in-the-middle replaces APK during download (even over TLS if CA is compromised) | SHA-256 verification prevents undetected swap | Yes |
| Man-in-the-middle replaces the manifest JSON (pointing to a malicious APK) | **Sign the manifest** (ECDSA or HMAC with a hardcoded key) or serve it over TLS with certificate pinning | Recommended for public |
| Downgrade attack via forged manifest | **Monotonic versionCode check** – reject any update with `versionCode` ≤ current | Yes – trivial |
| Malicious APK signed with any certificate (first‑install scenario) | **Signer‑certificate pinning** – before installing, extract the signer’s SHA-256 from the APK via `PackageManager.getPackageArchiveInfo(apkPath, GET_SIGNING_CERTIFICATES)` and compare against a hardcoded release‑cert digest [gist.github.com](https://gist.github.com/chinalwb/d546334ee8c5ba7afbad8a79d1e6a70f)[stackoverflow.com](https://stackoverflow.com/questions/71356010/packagemanager-getpackagearchiveinfo-with-get-signing-certificates-returning-nul)[www.rustore.ru](https://www.rustore.ru/help/developers/tools/engagement-tools/remote-config/sdk/check-apk-signature) | Belt‑and‑suspenders |

### Minimum Bar vs. Belt-and-Suspenders

- **Minimum bar (for internal/testers):** HTTPS download + SHA-256 hash match + `versionCode` monotonic check + Android’s same-signer enforcement (once installed).
- **Belt-and-suspenders (for public):** Add signer‑certificate pinning (compare SHA-256 of signing certificate from APK against pinned value), and sign the manifest JSON itself with an ECDSA signature or HMAC.

### Implementation Notes

- Use `PackageManager.getPackageArchiveInfo(apkFile.absolutePath, PackageManager.GET_SIGNING_CERTIFICATES)`.  
  **Caveat:** On some OEMs or older API levels, `getPackageArchiveInfo` may return `null` for `signingInfo` when called on a file path that isn’t the already‑installed app [stackoverflow.com](https://stackoverflow.com/questions/71356010/packagemanager-getpackagearchiveinfo-with-get-signing-certificates-returning-nul). Test thoroughly. The fallback is to rely on SHA-256 hash only (which suffices combined with TLS and same-signer after first install).
- The signer‑cert pinned value should be the SHA-256 digest of your release keystore certificate, obtained via:
  ```bash
  keytool -printcert -jarfile TeaTiers-release.apk
  # Look for SHA256: ...
  ```

---

## 3. Hosting: Version-Check Source & APK Download – RU Reachability

### Reachability Evidence (19 June 2026)

- **GitHub** (`api.github.com`, `github.com`, `objects.githubusercontent.com`): As of May–June 2026, Russian ISPs have been throttling/blocking GitHub via DPI/SNI filtering [habr.com](https://habr.com/ru/articles/1047444/)[www.mentoday.ru](https://www.mentoday.ru/technics/news/13-05-2026/v-gosdume-predupredili-o-polnoi-blokirovke-github-programmistov-prizvali-perehodit-na-rossiiskie-analogi/). A deputy of the State Duma reported a **~16% connection failure rate** from Russian networks [www.mentoday.ru](https://www.mentoday.ru/technics/news/13-05-2026/v-gosdume-predupredili-o-polnoi-blokirovke-github-programmistov-prizvali-perehodit-na-rossiiskie-analogi/). Large data transfers (`git clone`, APK downloads) are more likely to fail than small requests [github.com](https://github.com/orgs/community/discussions/143212)[habr.com](https://habr.com/ru/articles/1047444/). The situation is ISP‑dependent (Rostelecom may work better than regional providers), but **GitHub cannot be assumed reachable without a VPN**.
- **Yandex Object Storage** and **tea.macsia.fun** (existing Yandex Cloud VM): Fully reachable inside Russia, no reported throttling. The app already makes HTTPS calls to the backend.

### Recommended Architecture

**Primary (always reachable in RU):**  
- **Version manifest** served by the existing Spring Boot backend on `tea.macsia.fun` at `https://tea.macsia.fun/api/v1/version/upgrade`.  
- **APK file** hosted in **Yandex Object Storage** (e.g., `https://storage.yandexcloud.net/teatiers/apk/TeaTiers-{version}.apk`). The backend returns the APK URL in the manifest. The backend itself does not serve the APK (to avoid load on the 4 GB VM); only a redirect or JSON field.

**Fallback (when primary is unreachable):**  
- **GitHub Releases** – the manifest JSON is also uploaded as a release asset, and the APK is the standard release asset. The app can try the primary first; if it fails (timeout or HTTP 5xx after retries), it falls back to GitHub.

**Why not other options:**
- **F-Droid repository** – requires running `fdroidserver` and adds complexity; the audience is internal/testers first. Deferred post-MVP.
- **Self‑hosting the APK on the same VM** – the 4 GB VM is too constrained (3.4 GB already committed). A static file + JSON manifest is fine, but serving a large APK would strain resources during high‑traffic periods. Yandex Object Storage is cheaper and scalable.

### Rate Limit Consideration

The GitHub anonymous API rate limit is **60 requests per hour per IP** [source: GitHub docs]. The app’s polling cadence (every few hours or daily) will not exceed this, but if many devices share a single public IP (e.g., corporate NAT), the limit could be hit. With a first‑party manifest on the backend, the API rate limit is irrelevant.

---

## 4. Version-Check Manifest JSON Schema & Spring Endpoint

### JSON Manifest Fields

```json
{
  "latestVersionCode": 2,
  "latestVersionName": "0.2.0",
  "minSupportedVersionCode": 1,
  "apkUrl": "https://storage.yandexcloud.net/teatiers/apk/TeaTiers-0.2.0.apk",
  "apkSha256": "a1b2c3d4e5f6...",
  "releaseNotes": "New tea ranking algorithm, bug fixes.",
  "mandatory": false,
  "apkUrlFallback": "https://github.com/macsia/TeaTiers/releases/download/v0.2.0/TeaTiers-0.2.0.apk",
  "apkSha256Fallback": "a1b2c3d4e5f6...",
  "minApiLevel": 26,
  "etag": "v0.2.0-20260619"
}
```

### Field Descriptions

| Field | Type | Description |
|-------|------|-------------|
| `latestVersionCode` | integer | Monotonic version code (must be > current installed). Acts as primary update trigger. |
| `latestVersionName` | string | Human-readable version name (for display). |
| `minSupportedVersionCode` | integer | Minimum `versionCode` required to continue using the app without a forced update. If current < this, a blocking update screen is shown. |
| `apkUrl` | string (URL) | Primary download URL (Yandex Object Storage). Should use HTTPS. |
| `apkSha256` | string (hex) | SHA-256 hash of the APK file for integrity verification. |
| `releaseNotes` | string | Markdown release notes (optional). |
| `mandatory` | boolean | If `true` and `latestVersionCode > current`, a non‑dismissable update prompt is shown. |
| `apkUrlFallback` | string (URL) | Alternative download URL (GitHub Releases). Used when primary fails. |
| `apkSha256Fallback` | string (hex) | SHA-256 of the fallback APK (should match primary). |
| `minApiLevel` | integer | Minimum Android API level required for this update (can be used for platform‑specific upgrades). |
| `etag` | string | Opaque tag for `If‑None‑Match` caching. |

### Spring Boot Endpoint

- **Method:** `GET`
- **Path:** `/api/v1/version/upgrade`
- **Authentication:** None (anonymous, no PII)
- **Response headers:** `Cache-Control: max-age=300` (5 minutes), `ETag: "{etag}"`
- **Response body:** The JSON manifest above.
- **Rate limiting:** Not needed for internal use; if public, consider IP‑based rate limiting (e.g., 10 req/min/IP) on the backend or Caddy.

The backend fetches the manifest from a database row or a static file. No PII or device identifiers are used.

### Client‑Side Update Decision Logic

1. Fetch `GET /api/v1/version/upgrade` with `If-None-Match` header set to previously cached `etag`. If 304 Not Modified → no update.
2. Parse manifest. If `latestVersionCode <= currentVersionCode` → no update.
3. If `minSupportedVersionCode > currentVersionCode` → **forced update** (mandatory = `true` regardless of field).
4. Otherwise, if `mandatory == true` → forced update; else → optional update prompt.
5. Download APK from `apkUrl` (or `apkUrlFallback` on failure). Compute SHA-256 of downloaded bytes; compare to `apkSha256`. If mismatch → abort, retry with fallback.
6. Optionally, verify signer certificate (see §2). Then proceed to installation.

### Downgrade Protection

Reject update if `latestVersionCode <= currentVersionCode`. This is enforced in the client.

---

## 5. Open‑Source Libraries vs. Custom Updater

### Library Comparison Table

| Library | Coordinates / Version | License | Last Release | GMS‑Free? | Supports Our Security Model? | RU‑Viable? | Verdict |
|---------|-----------------------|---------|--------------|-----------|------------------------------|------------|---------|
| **AppUpdater** (javiersantos) | `com.github.javiersantos:AppUpdater:2.7` [mvnrepository.com](https://mvnrepository.com/artifact/com.github.javiersantos/AppUpdater/2.6.5) | Apache‑2.0 | Jul 5, 2018 [github.com](https://github.com/javiersantos/AppUpdater) | Yes (for non‑Play sources) – uses legacy `ACTION_VIEW` / `PackageManager` install | No SHA‑256 verification, no signer pinning; outdated API usage | Would work if GitHub reachable, but no fallback | **Not recommended** – unmaintained for 8 years, uses deprecated APIs, no security checks |
| **Obtainium** | N/A (full app, not a library) – `dev.imranr.obtainium.fdroid` [awesome.ecosyste.ms](https://awesome.ecosyste.ms/projects/github.com%2FImranR98%2FObtainium) | GPL‑3.0 | Apr 16, 2026 (v1.4.3) [4pda.to](https://4pda.to/forum/index.php?showtopic=1056449&st=360) | Yes – uses `PackageInstaller.Session` directly | Yes – verifies APK signatures, supports custom JSON manifests | Yes – but as a full app, not embeddable | **Excellent reference**, but cannot be used as a library in another app |
| **F-Droid Client** | `org.fdroid.fdroid` (full app) [4pda.to](https://4pda.to/forum/index.php?showtopic=310378) | GPL‑2.0+ | Jun 9, 2026 (v2.0‑alpha10) [4pda.to](https://4pda.to/forum/index.php?showtopic=310378) | Yes – uses `PackageInstaller.Session` | Yes – signs and verifies APK hashes | Yes | **Reference only** – full app, not a library |
| **Custom hand‑rolled** (OkHttp/DownloadManager + SHA‑256 + PackageInstaller.Session) | N/A | Own code | N/A | Yes – no external GMS dependencies | Fully controllable – can implement SHA‑256, signer pinning, signed manifest, downgrade checks | Yes – primary Yandex source always reachable | **Recommended** |

### Recommendation

**Build a small custom updater**. Rationale:
- No maintained library exists that is both GMS‑free and embeddable.
- AppUpdater is unmaintained (8 years) [github.com](https://github.com/javiersantos/AppUpdater)[mvnrepository.com](https://mvnrepository.com/artifact/com.github.javiersantos/AppUpdater/2.6.5). It uses the legacy intent‑based installation which may behave inconsistently on API 34+.
- Obtainium and F‑Droid are excellent but are full apps with their own UIs and workflows; integrating them would require rewriting large parts of the app or using IPC.
- A custom updater is 200–300 lines of well‑understood Kotlin code: HTTP download (OkHttp) → compute SHA‑256 → verify → `PackageInstaller.Session` → install callback. Full control over security and UX.

---

## 6. Update UX & Policy (Russia-First, Respectful)

### UX Flow

1. **Background check:** On app launch or periodically (e.g., every 24 hours via WorkManager), fetch the upgrade manifest.
2. **Non‑mandatory update:** Show a dialog with “Update available” title, release notes (from manifest), and two buttons: “Update” and “Later”. The dialog should have a “Don’t show again for this version” option.
3. **Mandatory / forced update:** If `minSupportedVersionCode > currentVersionCode` or `mandatory == true`, show a blocking dialog with only “Update” button. User cannot dismiss it.
4. **On metered/roaming:** Check `ConnectivityManager.isActiveNetworkMetered()`. If true, defer the download and show a non‑blocking notification saying “Update available – will download on Wi‑Fi”.
5. **First‑run grant:** If `canRequestPackageInstalls()` is false, redirect the user to the system Settings screen with a brief explanation. Use `Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES)` but note that this is package‑specific only on API 26+.
6. **Download progress:** Show a foreground notification (or in‑app progress bar) with download percentage. Use a foreground service with `foregroundServiceType = "dataSync"` (required on API 34+) [stackoverflow.com](https://stackoverflow.com/questions/76949759/foregroundservicetype-0x00000001-is-not-a-subset-of-foregroundservicetype-attrib).
7. **Install confirmation:** The system’s `PackageInstaller` will show a brief “Installing…” dialog. No extra user confirmation is needed after the grant.
8. **After install:** The app process will be killed and restarted. Listen for `ACTION_MY_PACKAGE_REPLACED` to show a “Update successful” toast on next launch [stackoverflow.com](https://stackoverflow.com/questions/10728016/android-restart-application-after-update-action-package-replaced).

### Russian‑Market Expectations

- Russian users are familiar with side‑loading APKs (many apps are distributed outside Google Play). A clear explanation in Russian (or English) is appreciated but not legally required.
- Avoid buzzwords like “Install unknown apps” – use “Allow installation from this source” with context.
- Provide a **manual download fallback link** (e.g., “Trouble updating? Download the APK manually”) that opens the GitHub Releases page in a browser.

### When Permission is Denied

If the user denies the `REQUEST_INSTALL_PACKAGES` grant (via Settings), the app cannot auto‑update. Show a polite message: “Auto‑update requires permission to install apps. You can enable it in Settings → Apps → TeaTiers → Install unknown apps.” Provide a button that opens that screen. If the user declines indefinitely, fall back to showing a link to the manual download.

---

## 7. Security Checklist (What Android Enforces vs. What We Must)

### Enforced by Android Automatically

| Property | Enforcement Point | Notes |
|----------|------------------|-------|
| Same‑signer on update | `PackageInstaller.Session.commit()` | Refuses APK signed with different cert than installed app [stackoverflow.com](https://stackoverflow.com/questions/71356010/packagemanager-getpackagearchiveinfo-with-get-signing-certificates-returning-nul) |
| No downgrade | Package manager | Rejects update if `versionCode < installed` (except debug builds) |
| No sideloading without grant | System check of `canRequestPackageInstalls()` | User must explicitly enable per‑app [android.stackexchange.com](https://android.stackexchange.com/questions/201491/why-would-i-enable-install-unknown-apps)[stackoverflow.com](https://stackoverflow.com/questions/47872162/how-to-use-packagemanager-canrequestpackageinstalls-in-android-oreo) |
| TLS encryption of download | HTTPS （performed by client) | Ensure all URLs use HTTPS |

### Must Be Implemented in App

| Security Measure | Implementation |
|-----------------|----------------|
| SHA‑256 hash verification of downloaded APK | Compute `MessageDigest.getInstance("SHA-256")` on download stream; compare hex string against `apkSha256` from manifest. |
| Manifest integrity (prevent MITM) | Serve manifest over HTTPS (our backend). For stronger protection, sign the manifest with ECDSA and verify with hardcoded public key. |
| Monotonic `versionCode` check | Reject if `manifest.latestVersionCode <= currentVersionCode`. |
| Signer‑certificate pinning (belt‑and‑suspenders) | After download, extract signer cert SHA-256 via `PackageManager.getPackageArchiveInfo(apkPath, GET_SIGNING_CERTIFICATES)` → `signingInfo.getSigningCertificateHistory()` → compute SHA-256 of first certificate. Compare with pinned release‑cert digest [stackoverflow.com](https://stackoverflow.com/questions/71356010/packagemanager-getpackagearchiveinfo-with-get-signing-certificates-returning-nul)[www.rustore.ru](https://www.rustore.ru/help/developers/tools/engagement-tools/remote-config/sdk/check-apk-signature). |
| Downgrade protection via `minSupportedVersionCode` | If current version is below `minSupportedVersionCode`, force update. |
| Retry with fallback URL | On download failure or hash mismatch, try `apkUrlFallback`. |

---

## 8. Recommended Architecture + Phased Build Plan

### Phase 1 – Internal Tester Release (Now)

**Goal:** Working auto‑update for limited testers with moderate security.

- **Manifest source:** Spring Boot endpoint `https://tea.macsia.fun/api/v1/version/upgrade` returning JSON with `latestVersionCode`, `apkUrl` (pointing to Yandex Object Storage), `apkSha256`, `minSupportedVersionCode`.
- **Security:** TLS + SHA‑256 hash check + monotonic `versionCode` + Android’s same‑signer enforcement.
- **Install:** Custom Kotlin code using `PackageInstaller.Session` (see pipeline below).
- **APK host:** Yandex Object Storage (public bucket or with signed URLs). No GitHub mirror yet.
- **UX:** Simple dialog for optional updates; forced updates for version below threshold.
- **Fallback:** None initially. If Yandex is unreachable (extremely unlikely in RU), skip update check.

### Phase 2 – Public Release Hardening (Before v0.3.0)

- **Add:** Signer‑certificate pinning in the verification step.
- **Add:** Manifest signing (ECDSA or HMAC) to prevent tampering with `apkUrl` even if TLS is compromised.
- **Add:** GitHub Releases as fallback URL in the manifest (both for manifest and APK). Implement retry logic.
- **Add:** ETag / `If-None-Match` caching on the backend and in the app.
- **Add:** Foreground service with proper `foregroundServiceType` for downloads (API 34+).
- **UX:** Metered/roaming awareness, “manual download” link.
- **OEM testing:** Test on Xiaomi, Huawei, Samsung devices.

### Phase 3 – Post‑MVP (Optional)

- F‑Droid repository integration if there is demand.
- RuStore / other marketplace distribution (then the in‑app updater becomes secondary or disabled).

### Does This Violate Any Locked Constraints?

- **No GMS:** The entire solution uses only AOSP APIs – no Play Core, no Google Play Services.
- **No VPN, no Western egress proxy:** All traffic goes directly to Yandex Cloud and (optionally) GitHub. GitHub may be unreliable, but the primary source (Yandex) always works without VPN.
- **No accounts / no PII:** The manifest endpoint is anonymous; no device identifiers are sent.
- **VM capacity:** The Spring Boot endpoint consumes negligible resources (a few kB per request). The APK is served from Yandex Object Storage, not from the VM.

---

## 9. Minimal Correct Kotlin Pipeline (Download → Verify → Install → Relaunch)

### Dependencies (build.gradle.kts)

```kotlin
implementation("com.squareup.okhttp3:okhttp:4.12.0")
implementation("androidx.security:security-crypto:1.1.0-alpha06") // optional
```

### Permissions in AndroidManifest.xml

```xml
<uses-permission android:name="android.permission.REQUEST_INSTALL_PACKAGES" />
<uses-permission android:name="android.permission.INTERNET" />
<uses-permission android:name="android.permission.FOREGROUND_SERVICE" />
<uses-permission android:name="android.permission.FOREGROUND_SERVICE_DATA_SYNC" />
<uses-permission android:name="android.permission.POST_NOTIFICATIONS" /> <!-- API 33+ -->
```

### Core UpdateFlow.kt (simplified)

```kotlin
class UpdateFlow(private val context: Context) {

    // Pinned release certificate SHA-256 (from keytool)
    private val RELEASE_CERT_SHA256 = "ABCDEF1234567890..."

    suspend fun checkAndUpdate(): Result<Unit> {
        // 1. Fetch manifest from primary URL with ETag caching
        val manifest = fetchManifest() ?: return Result.failure(IOException("No manifest"))

        if (manifest.latestVersionCode <= getCurrentVersionCode()) return Result.success(Unit)

        // 2. Check forced / mandatory
        val forced = manifest.latestVersionCode < manifest.minSupportedVersionCode

        // 3. Ask user (if not forced) – show dialog, wait for user action
        if (!forced) {
            // show optional dialog, return if user declines
        }

        // 4. Check Install Unknown Apps permission
        if (!canRequestPackageInstalls()) {
            // redirect to Settings, return
        }

        // 5. Download APK to a temp file
        val apkFile = downloadApk(manifest.apkUrl) // returns File

        // 6. Verify SHA-256
        val digest = apkFile.sha256Hex()
        if (digest != manifest.apkSha256) {
            // try fallback URL; if still mismatch, abort
            apkFile.delete()
            return Result.failure(SecurityException("SHA-256 mismatch"))
        }

        // 7. (Optional) Verify signer certificate
        if (!verifySignerCertificate(apkFile)) {
            apkFile.delete()
            return Result.failure(SecurityException("Signer cert mismatch"))
        }

        // 8. Install via PackageInstaller.Session
        val success = installApk(apkFile)
        apkFile.delete()
        return if (success) Result.success(Unit) else Result.failure(Exception("Install failed"))
    }

    private fun installApk(apkFile: File): Boolean {
        val packageInstaller = context.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
            setAppPackageName(context.packageName)
        }
        val sessionId = packageInstaller.createSession(params)
        val session = packageInstaller.openSession(sessionId)
        return try {
            session.openWrite("session", 0, -1).use { out ->
                apkFile.inputStream().use { input ->
                    input.copyTo(out, bufferSize = 64 * 1024)
                }
                session.fsync(out)
            }
            // Create PendingIntent for callback
            val intent = Intent(context, UpdateReceiver::class.java).apply {
                action = "com.macsia.teatiers.INSTALL_COMPLETE"
            }
            val pendingIntent = PendingIntent.getBroadcast(context, 0, intent, PendingIntent.FLAG_UPDATE_CURRENT)
            session.commit(pendingIntent.intentSender)
            true
        } catch (e: Exception) {
            session.abandon()
            false
        } finally {
            session.close()
        }
    }

    private fun verifySignerCertificate(apkFile: File): Boolean {
        val pm = context.packageManager
        val info = pm.getPackageArchiveInfo(apkFile.absolutePath, PackageManager.GET_SIGNING_CERTIFICATES)
            ?: return false
        val signingInfo = info.signingInfo ?: return false
        val signatures = if (signingInfo.hasMultipleSigners()) {
            signingInfo.apkContentsSigners
        } else {
            signingInfo.signingCertificateHistory
        }
        if (signatures.isEmpty()) return false
        // Compute SHA-256 of first signature
        val certBytes = signatures[0].toByteArray()
        val sha256 = MessageDigest.getInstance("SHA-256").digest(certBytes).joinToString("") { "%02x".format(it) }
        return sha256.equals(RELEASE_CERT_SHA256, ignoreCase = true)
    }

    @RequiresApi(Build.VERSION_CODES.O)
    private fun canRequestPackageInstalls(): Boolean =
        context.packageManager.canRequestPackageInstalls()
}
```

### Broadcast Receiver for Install Result

```kotlin
class UpdateReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val status = when (intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)) {
            PackageInstaller.STATUS_SUCCESS -> "Update successful"
            PackageInstaller.STATUS_PENDING_USER_ACTION -> "User action required"
            else -> "Install failed: ${intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)}"
        }
        Toast.makeText(context, status, Toast.LENGTH_LONG).show()
    }
}
```

### Registration in AndroidManifest.xml

```xml
<receiver
    android:name=".UpdateReceiver"
    android:exported="false">
    <intent-filter>
        <action android:name="com.macsia.teatiers.INSTALL_COMPLETE" />
    </intent-filter>
</receiver>
```

---

## 10. Reference Links

[developer.android.com](https://developer.android.com/reference/android/content/pm/PackageInstaller.Session) Android Developers – `PackageInstaller.Session` API reference (accessed 19 June 2026)  
[github.com](https://github.com/javiersantos/AppUpdater) javiersantos/AppUpdater – GitHub repository, latest release v2.7 (5 July 2018)  
[habr.com](https://habr.com/ru/articles/1047444/) Habr – “GitHub и PyPI сбоят в России” (15 June 2026) – evidence of DPI throttling  
[stackoverflow.com](https://stackoverflow.com/questions/10728016/android-restart-application-after-update-action-package-replaced) Stack Overflow – “Android restart application after update – ACTION_PACKAGE_REPLACED”  
[stackoverflow.com](https://stackoverflow.com/questions/26884956/how-to-install-update-remove-apk-using-packageinstaller-class-in-android-l) Stack Overflow – “Install APK using PackageInstaller class” – example with Session API  
[stackoverflow.com](https://stackoverflow.com/questions/47872162/how-to-use-packagemanager-canrequestpackageinstalls-in-android-oreo) Stack Overflow – “How to use canRequestPackageInstalls” – permission handling on Oreo  
[stackoverflow.com](https://stackoverflow.com/questions/71356010/packagemanager-getpackagearchiveinfo-with-get-signing-certificates-returning-nul) Stack Overflow – “getPackageArchiveInfo returning null signing info” – caveats  
[www.mentoday.ru](https://www.mentoday.ru/technics/news/13-05-2026/v-gosdume-predupredili-o-polnoi-blokirovke-github-programmistov-prizvali-perehodit-na-rossiiskie-analogi/) MenToday – “Почему не работает GitHub в России” (13 May 2026) – 16% failure rate  
[www.rustore.ru](https://www.rustore.ru/help/developers/tools/engagement-tools/remote-config/sdk/check-apk-signature) RuStore – “Проверка подписи APK” – using `keytool` to extract SHA-256 fingerprint