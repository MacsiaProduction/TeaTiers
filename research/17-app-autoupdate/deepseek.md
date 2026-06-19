# TeaTiers Auto-Update Implementation Report

## Executive Summary

**Decision:** Primary manifest + APK hosted on the first-party Yandex backend (`tea.macsia.fun`), with GitHub Releases as a fallback manifest source and Yandex Object Storage as an APK mirror. **Build a small custom updater** using OkHttp/DownloadManager + SHA-256 verification + signer certificate pinning + `PackageInstaller` Session API — no third-party update libraries (all are unmaintained, GMS-dependent, or unsuitable for our security model).


## 1. Android Install Mechanism (GMS-free, API 26→36)

### Correct API: `PackageInstaller` Session API (NOT legacy intents)

The legacy `Intent.ACTION_INSTALL_PACKAGE` / `ACTION_VIEW` is **deprecated** for API 26+. The correct modern API is `PackageInstaller.Session`.

### Required Permission & User Grant

Add to `AndroidManifest.xml`:
```xml
<uses-permission android:name="android.permission.REQUEST_INSTALL_PACKAGES" />
```

This permission **only takes effect when targeting API 26+** on devices running Android 8+. The user must grant **"install unknown apps"** permission via `Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES` — checked with `canRequestPackageInstalls()`.

### API-Level Behavior

| API Level | Behavior |
|-----------|----------|
| 26–30 | `REQUEST_INSTALL_PACKAGES` required; user must enable "Unknown sources" for your app |
| 31+ | `PackageInstaller.SessionParams.setRequireUserAction()` added — defaults to `REQUIRE_USER_ACTION_UNSPECIFIED` |
| 33+ | Stricter enforcement; `setRequireUserAction(REQUIRE_USER_ACTION_NOT_REQUIRED)` only works for system apps/device owners |
| 34–36 | No additional restrictions; user confirmation dialog remains mandatory for non-system apps |

**Critical:** A non-system app **cannot** install without a foreground UI user-confirmation dialog. `setRequireUserAction(REQUIRE_USER_ACTION_NOT_REQUIRED)` is ignored for normal apps.

### FileProvider Requirement (API 24+)

APK files must be served via `FileProvider` with a `content://` URI when using legacy intents. With `PackageInstaller.Session`, you **stream the APK bytes directly** — no FileProvider needed, no file URI issues.

### OEM Quirks (Common on RU Devices)

- **Xiaomi/MIUI:** Aggressive battery optimization may kill background download services. Users must add the app to "Autostart" and disable battery restrictions. The "Install unknown apps" setting is buried in Settings > Apps > Manage apps > [App] > Install unknown apps.
- **Huawei:** Similar battery optimization; may require enabling "Install via [App]" in Settings > Security > More settings.
- **Samsung:** Usually straightforward; `canRequestPackageInstalls()` works as documented.
- **General:** Some OEMs (Xiaomi, Oppo) display a second confirmation dialog after the system installer — this is unavoidable.

### Minimal Correct Kotlin Flow

```kotlin
// 1. Check permission
if (!packageManager.canRequestPackageInstalls()) {
    Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES).apply {
        data = Uri.parse("package:$packageName")
    }.let { startActivityForResult(it, INSTALL_PERMISSION_REQUEST) }
    return
}

// 2. Download + verify (see Section 2)
val apkFile = downloadAndVerify(manifest)

// 3. PackageInstaller session
val installer = packageManager.packageInstaller
val params = PackageInstaller.SessionParams(
    PackageInstaller.SessionParams.MODE_FULL_INSTALL
).apply {
    setAppPackageName(packageName)
    setInstallReason(PackageManager.INSTALL_REASON_USER)
}

val sessionId = installer.createSession(params)
val session = installer.openSession(sessionId)

// 4. Stream APK
apkFile.inputStream().use { input ->
    session.openWrite("base.apk", 0, apkFile.length()).use { output ->
        input.copyTo(output)
    }
}
session.fsync(output)

// 5. Commit — shows system confirmation dialog
val intent = Intent(context, InstallReceiver::class.java)
val pendingIntent = PendingIntent.getBroadcast(
    context, sessionId, intent, 
    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
)
session.commit(pendingIntent.intentSender)

// 6. Listen for status in BroadcastReceiver
class InstallReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val status = intent.getIntExtra(
            PackageInstaller.EXTRA_STATUS, 
            PackageInstaller.STATUS_FAILURE
        )
        when (status) {
            PackageInstaller.STATUS_SUCCESS -> { /* relaunch or notify */ }
            PackageInstaller.STATUS_FAILURE -> { /* handle error */ }
        }
    }
}
```

### App Relaunch After Update

After a successful `PackageInstaller.STATUS_SUCCESS`, the system **does not auto-relaunch** the app. The user must tap "Open" from the installation completion dialog, or you can:
- Schedule an `AlarmManager` / `WorkManager` task to relaunch after a delay (detectable by comparing `versionCode`)
- Use a foreground service to monitor installation completion, then launch the main activity via `Intent.FLAG_ACTIVITY_NEW_TASK`

**Recommendation:** Show a notification after successful install prompting the user to "Open" or "Restart Now".


## 2. Update Authenticity & Security

### What Android Enforces For You

Android **does enforce same-signing-certificate** for updates:
- If the new APK is signed with a different certificate, installation **fails** unless the old app is uninstalled first
- The update must have the same or higher `versionCode`
- This prevents a malicious actor from pushing a differently-signed APK as an "update"

**However**, this only protects **after** the APK is committed to the installer. It does NOT protect against:
- A MITM serving a malicious APK **with your signature** (if your signing key were compromised)
- A MITM serving an older, vulnerable version (downgrade attack)
- The manifest itself being tampered with to point to a malicious URL

### What We Must Enforce Ourselves (Belt-and-Suspenders)

| Check | Method | Required |
|-------|--------|----------|
| **Manifest integrity** | Serve manifest over HTTPS + sign manifest with Ed25519 (or HMAC) | ✅ Yes |
| **APK SHA-256** | Compare downloaded APK hash against manifest's `apkSha256` | ✅ Yes |
| **Signer certificate pinning** | Extract signing cert from APK via `PackageManager.getPackageArchiveInfo` with `GET_SIGNING_CERTIFICATES`, compare to pinned release cert SHA-256 | ✅ Yes |
| **Downgrade protection** | Compare `versionCode` from manifest against current `versionCode`; reject if lower | ✅ Yes |
| **TLS for download** | HTTPS only; certificate validation enabled | ✅ Yes |

### Implementation: Signer Certificate Verification

```kotlin
fun verifyApkSigner(apkPath: String): Boolean {
    val pm = context.packageManager
    val packageInfo = pm.getPackageArchiveInfo(
        apkPath, 
        PackageManager.GET_SIGNING_CERTIFICATES
    )
    val signingInfo = packageInfo?.signingInfo ?: return false
    
    val certs = if (signingInfo.hasMultipleSigners()) {
        signingInfo.apkContentsSigners
    } else {
        signingInfo.signingCertificateHistory
    }
    
    val pinnedCertHash = "SHA-256:..." // Your release cert's SHA-256
    return certs.any { cert ->
        MessageDigest.getInstance("SHA-256")
            .digest(cert.toByteArray())
            .encodeToHexString() == pinnedCertHash
    }
}
```

**Note:** Use `GET_SIGNING_CERTIFICATES` (not deprecated `GET_SIGNATURES`) for API 28+.

### Manifest Signing (Recommended)

To prevent manifest tampering:
1. Generate an Ed25519 key pair for manifest signing (separate from APK signing key)
2. Include `signature` field in manifest JSON: `sign(manifestWithoutSignature, privateKey)`
3. App verifies signature against embedded public key before trusting any field

**Minimum bar:** HTTPS + SHA-256 + signer pin + downgrade check.
**Belt-and-suspenders:** Add manifest signing.


## 3. Where Version Check + APK Live, and RU Reachability

### Option Comparison

| Host | RU Reachability | Rate Limit | Operational Cost | Verdict |
|------|----------------|------------|------------------|---------|
| **GitHub API** (`api.github.com`) | ❌ **Unreliable** — Roskomnadzor does not officially block, but GitHub has restricted accounts from Russia since 2022; Copilot and other services are blocked. Static CDN (`objects.githubusercontent.com`) is frequently throttled/rate-limited. | 60 req/hr/IP unauthenticated | Free | ❌ Not viable as primary |
| **Yandex Backend** (`tea.macsia.fun`) | ✅ **Always reachable** — app already communicates with it; first-party control | Unlimited (your VM) | Fits in 4GB VM (tiny JSON manifest) | ✅ **Primary** |
| **Yandex Object Storage** | ✅ Reachable (Yandex infrastructure) | Unlimited | ~$0.01/GB/month | ✅ **APK mirror** |
| **Self-hosted F-Droid repo** | ✅ Reachable | Unlimited | Extra service on 4GB VM; fdroidserver is heavy | ❌ Overkill |

### RU Reachability Evidence

- **Roskomnadzor** officially states GitHub is **not blocked**
- However, **de facto restrictions** exist: accounts from Russia have been blocked since 2022; by 2025, full account bans are reported
- `raw.githubusercontent.com` and `objects.githubusercontent.com` are subject to rate-limiting and geo-blocking
- **Obtainium** (a popular app-updater) has explicit issues tracking GitHub raw links being blocked in some countries and implements bypass mechanisms

### Recommendation: Primary + Fallback

```
┌─────────────────────────────────────────────────────────────────┐
│                    UPDATE CHECK FLOW                           │
├─────────────────────────────────────────────────────────────────┤
│                                                                 │
│  1. Primary: GET https://tea.macsia.fun/api/v1/upgrade         │
│     → Returns manifest with apkUrl (Yandex Object Storage)     │
│     → ETag caching, 1hr polling                                │
│                                                                 │
│  2. Fallback: GET https://api.github.com/repos/.../latest      │
│     → Only if primary fails (network error, 5xx)              │
│     → Rate limit: 60/hr — use sparingly                       │
│                                                                 │
│  3. APK download: Primary → Yandex Object Storage              │
│     Fallback → GitHub release asset                            │
│                                                                 │
│  4. Both manifest AND APK have SHA-256 verification            │
│                                                                 │
└─────────────────────────────────────────────────────────────────┘
```

**Why GitHub fallback is still useful:** If Yandex backend is down, GitHub provides a free backup. But it cannot be the primary due to rate limits and RU reliability concerns.


## 4. Version-Check / Update Manifest Design

### JSON Schema

```json
{
  "$schema": "https://json-schema.org/draft/07/schema#",
  "type": "object",
  "required": ["latestVersionCode", "latestVersionName", "minSupportedVersionCode", "apkUrl", "apkSha256"],
  "properties": {
    "latestVersionCode": {
      "type": "integer",
      "description": "Monotonic version code (must be > current for update)"
    },
    "latestVersionName": {
      "type": "string",
      "description": "Human-readable version (e.g., '0.2.0')"
    },
    "minSupportedVersionCode": {
      "type": "integer",
      "description": "Minimum client version that can still use the API; if current < this, force update"
    },
    "apkUrl": {
      "type": "string",
      "format": "uri",
      "description": "Primary APK download URL (Yandex Object Storage)"
    },
    "mirrorUrls": {
      "type": "array",
      "items": {"type": "string", "format": "uri"},
      "description": "Fallback download URLs (GitHub release asset, etc.)"
    },
    "apkSha256": {
      "type": "string",
      "pattern": "^[a-fA-F0-9]{64}$",
      "description": "SHA-256 hash of the APK"
    },
    "releaseNotes": {
      "type": "string",
      "description": "Optional release notes (localized via Accept-Language)"
    },
    "mandatory": {
      "type": "boolean",
      "default": false,
      "description": "If true, user cannot skip this update"
    },
    "signature": {
      "type": "string",
      "description": "Ed25519 signature of the manifest (excluding this field)"
    }
  }
}
```

### Update Decision Logic

```kotlin
fun checkForUpdate(manifest: Manifest, currentVersionCode: Int): UpdateAction {
    return when {
        // Hard cutoff: force update
        currentVersionCode < manifest.minSupportedVersionCode -> 
            UpdateAction.FORCE_UPDATE
            
        // New version available
        manifest.latestVersionCode > currentVersionCode -> 
            if (manifest.mandatory) UpdateAction.FORCE_UPDATE 
            else UpdateAction.OPTIONAL_UPDATE
            
        else -> UpdateAction.NO_UPDATE
    }
}
```

### Spring Boot Endpoint

```kotlin
@RestController
@RequestMapping("/api/v1")
class UpgradeController {
    
    @GetMapping("/upgrade")
    fun getUpgrade(
        @RequestHeader("If-None-Match") ifNoneMatch: String?,
        response: HttpServletResponse
    ): ResponseEntity<UpgradeManifest> {
        val manifest = loadManifest() // from file or DB
        val etag = "\"${manifest.apkSha256.take(16)}\""
        
        if (ifNoneMatch == etag) {
            response.status = HttpStatus.NOT_MODIFIED.value()
            return ResponseEntity(null, HttpStatus.NOT_MODIFIED)
        }
        
        return ResponseEntity.ok()
            .eTag(etag)
            .cacheControl(CacheControl.maxAge(1, TimeUnit.HOURS))
            .body(manifest)
    }
}
```

### Polling Cadence

- **Check once per day** (not per hour) — respects rate limits, reduces server load
- Use `WorkManager` with `PeriodicWorkRequest` (minimum interval ~15 min, but we schedule daily)
- Store `lastKnownEtag` in `SharedPreferences`; only fetch if ETag changed


## 5. Open-Source Libraries vs. Build-Our-Own

### Library Comparison Table

| Library | Coordinates | Version | License | Last Release | GMS-Free? | Supports Our Security Model? | RU-Viable? | Verdict |
|---------|-------------|---------|---------|--------------|-----------|------------------------------|------------|---------|
| **javiersantos/AppUpdater** | `com.github.javiersantos:AppUpdater` | 2.7 | Apache-2.0 | ~2022 (4+ years ago) | ✅ Yes (supports custom JSON/XML) | ❌ No — checks for update but doesn't handle APK verification/signer pinning | ⚠️ Partial | **❌ REJECT** — unmaintained |
| **Obtainium** | `dev.imranr.obtainium` | varies | GPL-3.0 | Active | ✅ Yes | ❌ No — designed as a user-facing app store, not an embeddable library | ⚠️ Partial (has GitHub bypass logic) | **❌ REJECT** — not embeddable |
| **F-Droid client** | `org.fdroid.fdroid` | varies | GPL-3.0+ | Active | ✅ Yes | ❌ No — uses Privileged Extension for auto-install; requires system-level permissions | ✅ Yes | **❌ REJECT** — not embeddable, requires privileged extension |
| **APKUpdater** | `com.rumboalla.apkupdater` | varies | Unknown | Unknown | ✅ Yes (uses PackageInstaller) | ❌ No — no manifest signing/signer pinning | ⚠️ Unknown | **❌ REJECT** — unclear maintenance status |
| **Custom (hand-rolled)** | N/A | N/A | Proprietary | N/A | ✅ Yes | ✅ **Full control** | ✅ Yes | **✅ RECOMMEND** |

### Detailed Library Analysis

**javiersantos/AppUpdater:**
- Last meaningful release: 2.7 (~2022)
- Supports custom JSON manifests
- **Does NOT** verify APK SHA-256 or signer certificates before installation
- Uses OkHttp 3.8.1 (ancient)
- **Verdict:** Unmaintained, security model insufficient

**Obtainium:**
- Not a library — it's a full app that users install
- Cannot be embedded as a dependency
- Does implement GitHub raw-link bypass for sanctioned regions
- **Verdict:** Not usable as a library

**F-Droid client:**
- Uses `Privileged Extension` installed as a `priv-app` for automatic updates
- Requires system-level permissions that normal apps cannot obtain
- **Verdict:** Not applicable

### Recommendation: Build Custom Updater

**Reasons:**
1. **Security:** No existing library provides APK signer verification + SHA-256 + manifest signing
2. **Maintenance:** All viable-seeming libraries are unmaintained (years since last release)
3. **Control:** Custom implementation is ~200-300 lines of Kotlin — trivial to build and audit
4. **RU-specific:** We can implement GitHub fallback + Yandex mirror logic exactly as needed
5. **No GMS:** We control every dependency

**Components to build:**
- `UpdateManager.kt` — orchestrates check → download → verify → install
- `ManifestVerifier.kt` — JSON parsing + signature verification + SHA-256 check
- `ApkInstaller.kt` — `PackageInstaller` session wrapper
- `UpdateWork.kt` — `WorkManager` periodic check


## 6. Update UX & Policy (Russia-First, Respectful)

### UX Flow

```
┌──────────────────────────────────────────────────────────────────────────┐
│                        UPDATE UX FLOW                                   │
├──────────────────────────────────────────────────────────────────────────┤
│                                                                          │
│  ┌─────────────────────┐                                                │
│  │  Periodic check      │  ← WorkManager (daily, Wi-Fi only optional)   │
│  └──────────┬──────────┘                                                │
│             ▼                                                            │
│  ┌─────────────────────┐                                                │
│  │  Update available?   │  ← Check manifest                              │
│  └──────────┬──────────┘                                                │
│             │                                                            │
│    ┌────────┴────────┐                                                  │
│    ▼                 ▼                                                  │
│  FORCED            OPTIONAL                                             │
│    │                 │                                                   │
│    ▼                 ▼                                                   │
│  ┌─────────────┐  ┌─────────────┐                                      │
│  │ Full-screen  │  │ Snackbar/   │  ← Non-intrusive notification        │
│  │ dialog with  │  │ Notification│     (can be dismissed)               │
│  │ "Update Now" │  │ with        │                                      │
│  │ (no dismiss) │  │ "Update"    │                                      │
│  └─────────────┘  └─────────────┘                                      │
│                                                                          │
│  ┌─────────────────────────────────────────────────────────────┐        │
│  │  Download progress (foreground notification, 0-100%)       │        │
│  └─────────────────────────────────────────────────────────────┘        │
│                                                                          │
│  ┌─────────────────────────────────────────────────────────────┐        │
│  │  "Allow [App] to install apps?" → System dialog             │        │
│  │  (if `canRequestPackageInstalls()` = false)                 │        │
│  └─────────────────────────────────────────────────────────────┘        │
│                                                                          │
│  ┌─────────────────────────────────────────────────────────────┐        │
│  │  System installation confirmation dialog                    │        │
│  └─────────────────────────────────────────────────────────────┘        │
│                                                                          │
│  ┌─────────────────────────────────────────────────────────────┐        │
│  │  Success notification: "Update installed. Tap to open."    │        │
│  └─────────────────────────────────────────────────────────────┘        │
│                                                                          │
└──────────────────────────────────────────────────────────────────────────┘
```

### Policy Rules

1. **No nagging:** Optional updates shown once, then dismissible. Check again after 24 hours.
2. **Metered networks:** Don't auto-download on cellular (respect `ConnectivityManager.isActiveNetworkMetered`).
3. **Roaming:** Don't download when roaming.
4. **Battery:** Don't download when battery < 15%.
5. **Install permission denied:** Guide user to Settings with clear explanation (Russian localization).
6. **Manual fallback:** Show "Download APK from GitHub" link if auto-install fails repeatedly.

### Russian Market UX Expectations

- Russian users are accustomed to **direct APK downloads** (RuStore, 4PDA, official sites)
- They understand "install unknown apps" permission flows
- **Localization is critical** — all dialogs must be in Russian
- Users expect **progress indicators** for downloads (slow connections are common)
- **Yandex** branding is trusted — using `tea.macsia.fun` domain is a plus

### Permission Denied Handling

```kotlin
if (!packageManager.canRequestPackageInstalls()) {
    AlertDialog.Builder(context)
        .setTitle("Разрешение на установку")
        .setMessage("Для обновления приложения необходимо разрешить установку из неизвестных источников. Перейти в настройки?")
        .setPositiveButton("Настройки") { _, _ ->
            Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES).apply {
                data = Uri.parse("package:$packageName")
            }.let { startActivity(it) }
        }
        .setNegativeButton("Отмена", null)
        .show()
}
```


## 7. Recommended Architecture + Phased Plan

### Architecture Diagram

```
┌─────────────────────────────────────────────────────────────────────────────┐
│                            TEA TIERS APP                                    │
├─────────────────────────────────────────────────────────────────────────────┤
│                                                                             │
│  ┌─────────────────┐     ┌─────────────────┐     ┌──────────────────────┐  │
│  │   WorkManager   │────▶│  UpdateManager  │────▶│  ManifestVerifier   │  │
│  │  (daily check)  │     │  (orchestrator) │     │  (JSON + signature) │  │
│  └─────────────────┘     └────────┬────────┘     └──────────────────────┘  │
│                                    │                                        │
│                                    ▼                                        │
│  ┌─────────────────┐     ┌─────────────────┐     ┌──────────────────────┐  │
│  │  OkHttp/        │────▶│  SHA-256        │────▶│  ApkInstaller       │  │
│  │  Downloader     │     │  Verifier       │     │  (PackageInstaller) │  │
│  └─────────────────┘     └─────────────────┘     └──────────────────────┘  │
│                                                                             │
│  ┌──────────────────────────────────────────────────────────────────────┐   │
│  │  Storage: SharedPreferences (ETag, lastCheck) + Internal storage    │   │
│  │           (downloaded APK, deleted after install)                   │   │
│  └──────────────────────────────────────────────────────────────────────┘   │
│                                                                             │
└─────────────────────────────────────────────────────────────────────────────┘
                                    │
                                    ▼
┌─────────────────────────────────────────────────────────────────────────────┐
│                         BACKEND (tea.macsia.fun)                            │
├─────────────────────────────────────────────────────────────────────────────┤
│  /api/v1/upgrade → JSON manifest (ETag-cached, 1hr max-age)                │
│                                                                             │
│  Yandex Object Storage → APK hosted at persistent URL                      │
│                                                                             │
│  GitHub Releases → Fallback manifest + APK (if primary fails)              │
└─────────────────────────────────────────────────────────────────────────────┘
```

### Phased Plan

#### Phase 1: Internal Tester Release (Week 1–2)

| Task | Priority | Details |
|------|----------|---------|
| Build `UpdateManager` core | 🔴 Critical | Check → download → install flow |
| Implement SHA-256 verification | 🔴 Critical | Compare downloaded APK hash |
| Add `PackageInstaller` integration | 🔴 Critical | Session API with broadcast receiver |
| Implement `REQUEST_INSTALL_PACKAGES` permission flow | 🔴 Critical | `canRequestPackageInstalls()` + Settings intent |
| Create manifest JSON | 🔴 Critical | Host on `tea.macsia.fun/api/v1/upgrade` |
| Basic UI (dialog + progress) | 🟡 High | Simple Material dialog |

**Phase 1 Deliverable:** Internal testers can update via the backend.

#### Phase 2: Security Hardening (Week 3)

| Task | Priority | Details |
|------|----------|---------|
| Signer certificate pinning | 🔴 Critical | `getPackageArchiveInfo` + `GET_SIGNING_CERTIFICATES` |
| Manifest signing (Ed25519) | 🟡 High | Prevent manifest tampering |
| Downgrade protection | 🟡 High | Reject if `versionCode < current` |
| GitHub fallback | 🟢 Medium | If primary manifest fails, try GitHub API |
| Yandex Object Storage mirror | 🟢 Medium | Host APK on Yandex S3-compatible storage |

**Phase 2 Deliverable:** Security-audited updater ready for public.

#### Phase 3: Public Hardening (Week 4)

| Task | Priority | Details |
|------|----------|---------|
| Full Russian localization | 🔴 Critical | All dialogs, notifications, error messages |
| Metered/roaming checks | 🟡 High | Respect user's data plan |
| Battery optimization exemption | 🟡 High | Guide users to disable battery optimization |
| Error recovery | 🟡 High | Retry logic, fallback URLs |
| Analytics (anonymous) | 🟢 Medium | Track update success/failure rates |
| Manual download fallback | 🟢 Medium | "Download from GitHub" link |

**Phase 3 Deliverable:** Production-ready auto-updater.

### Constraint Conflicts

| Constraint | Conflict? | Resolution |
|------------|-----------|------------|
| No GMS | ✅ None | `PackageInstaller` is AOSP, not GMS |
| No VPN | ⚠️ Partial | GitHub fallback may be unreachable from RU; that's why it's a *fallback* |
| No accounts/PII | ✅ None | No authentication, no device identifiers |
| 4GB VM limit | ✅ None | Manifest is ~1KB, served by existing Spring app |
| Single APK | ✅ None | Works with any APK |


## 8. Security Checklist

### What Android Enforces
- [x] Same signing certificate for updates
- [x] Version code monotonic (can't downgrade via system installer)

### What We Must Enforce
- [x] **TLS for all network calls** (HTTPS only)
- [x] **Manifest signature** (Ed25519) to prevent MITM pointing to malicious URL
- [x] **APK SHA-256** match against manifest
- [x] **Signer certificate pinning** — verify APK signer against pinned release cert before installing
- [x] **Downgrade protection** — reject if `manifest.latestVersionCode <= currentVersionCode`
- [x] **Delete APK after install** — remove from internal storage to prevent reuse
- [x] **Verify download source** — only trust URLs from signed manifest

### Recommended: APK Signature Scheme v2/v3
- Use **APK Signature Scheme v2** (or v3) when signing — provides faster verification and better integrity guarantees than v1 (JAR signing)
- Android 7.0+ (API 24+) verifies v2 signatures by default

### Manifest Signing Implementation

```kotlin
// Generate key (once, store securely)
val keyPair = Ed25519KeyPairGenerator.generateKeyPair()

// Sign manifest (backend)
fun signManifest(manifestJson: String, privateKey: PrivateKey): String {
    val signature = Ed25519.sign(manifestJson.toByteArray(), privateKey)
    return Base64.encodeToString(signature)
}

// Verify manifest (app)
fun verifyManifest(manifestJson: String, signature: String, publicKey: PublicKey): Boolean {
    val sigBytes = Base64.decode(signature, Base64.DEFAULT)
    return Ed25519.verify(manifestJson.toByteArray(), sigBytes, publicKey)
}
```


## 9. Reference Links

| # | Source | URL | Access Date |
|---|--------|-----|-------------|
| 1 | Android `PackageInstaller` API | https://developer.android.com/reference/android/content/pm/PackageInstaller | 2026-06-19 |
| 2 | `PackageInstaller.SessionParams` | https://developer.android.com/reference/android/content/pm/PackageInstaller.SessionParams | 2026-06-19 |
| 3 | `PackageInstaller.InstallConstraints` | https://developer.android.com/reference/android/content/pm/PackageInstaller.InstallConstraints | 2026-06-19 |
| 4 | GitHub REST API Rate Limits | https://docs.github.com/en/rest/using-the-rest-api/rate-limits-for-the-rest-api | 2026-06-19 |
| 5 | `REQUEST_INSTALL_PACKAGES` permission | https://support.google.com/googleplay/android-developer/answer/9844774 | 2026-06-19 |
| 6 | javiersantos/AppUpdater Releases | https://github.com/javiersantos/AppUpdater/releases | 2026-06-19 |
| 7 | Obtainium GitHub | https://github.com/ImranR98/Obtainium | 2026-06-19 |
| 8 | GitHub Russia access status | https://kod.ru/zablokirovan-li-github-v-rossii/ | 2026-06-19 |
| 9 | Russia GitHub sanctions impact | https://mosdigitals.ru/ispolzovanie-github-gitlab-v-usloviyah-sanktsij-v-rossii | 2026-06-19 |
| 10 | GitHub raw links blocked | https://github.com/community/community/discussions/157851 | 2026-06-19 |
| 11 | Android app updates (signing) | https://developer.android.com/google/play/developer-api/guides/app-updates | 2026-06-19 |
| 12 | F-Droid Privileged Extension | https://f-droid.org/en/docs/FAQ_-_Client/ | 2026-06-19 |


## Summary Decision Table

| Decision Point | Choice | Rationale |
|----------------|--------|-----------|
| **Install API** | `PackageInstaller.Session` | Modern, GMS-free, supports streaming, no FileProvider required |
| **Manifest host** | Yandex backend (`tea.macsia.fun/api/v1/upgrade`) | Always reachable from RU, app already uses it, tiny footprint |
| **APK host** | Yandex Object Storage (primary), GitHub Releases (fallback) | RU-reachable primary; free fallback |
| **Security** | SHA-256 + signer pinning + manifest signing + downgrade check | Belt-and-suspenders; Android only enforces same-signer |
| **Library** | **Custom** (hand-rolled) | All libraries are unmaintained or GMS-dependent or not embeddable |
| **Update UX** | Daily check, optional dialog, forced for old clients, Russian localization | Respectful, Russia-first |
| **Polling** | Daily + ETag | Respects GitHub 60/hr limit, reduces server load |