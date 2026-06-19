# Technical Report: Secure, GMS-Free In-App Auto-Update Architecture for TeaTiers

This report details the technical implementation, security topology, network reachability mitigations, and user experience paradigms for the in-app auto-update mechanism of **TeaTiers**.

---

## 1. Android Install Mechanism (GMS-Free), API 26→36

To achieve robust updates on GMS-free and de-Googled Android devices targeting API level 36, relying on legacy intent-based installations is highly discouraged. We must implement the **`PackageInstaller` Session API**, which is the modern Android standard for programmatically installing APKs.

### Legacy Intent vs. PackageInstaller Session API
*   **The Legacy Way (`ACTION_INSTALL_PACKAGE` / `ACTION_VIEW`):** Deprecated in Android 10 (API 29). It passes a file URI via an intent to the system package installer. This triggers high-friction, brand-inconsistent system prompts, doesn't easily support split APKs, and frequently fails on targetSdk 30+ due to strict `FileProvider` constraints.
*   **The Session API Way (`PackageInstaller`):** Operates as a transactional pipeline. The app creates an install session, streams the APK bytes into it, and commits the session.
    *   **Major Security & Simplicity Advantage:** Because the app reads the downloaded APK file from its private cache directory (`context.cacheDir`) and streams the raw bytes directly to the system installer, **it does not require a `FileProvider` or content-URI configuration**. No local file paths are exposed to external processes.

### Permission and User Grant Requirements
1.  **Manifest Declaration:** 
    ```xml
    <uses-permission android:name="android.permission.REQUEST_INSTALL_PACKAGES" />
    <!-- Required for unattended/silent updates on Android 12+ (API 31+) -->
    <uses-permission android:name="android.permission.UPDATE_PACKAGES_WITHOUT_USER_ACTION" />
    ```
2.  **Runtime Check:** Sideloading requires a per-source system-level authorization called "Install unknown apps". Sideloading apps must verify this grant before initiating a session:
    ```kotlin
    val canInstall = context.packageManager.canRequestPackageInstalls() // API 26+
    ```
    If `false`, the app must redirect the user directly to the system settings page for TeaTiers:
    ```kotlin
    val intent = Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES).apply {
        data = Uri.parse("package:${context.packageName}")
    }
    context.startActivity(intent)
    ```

### Behavior & Constraints Across API Levels
*   **API 26 (Android 8.0):** Introduces `canRequestPackageInstalls()` and `Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES`. Sideloading changes from a system-wide toggle to a strict per-app permission model.
*   **API 29 (Android 10):** Strict deprecation of legacy package installer intents.
*   **API 31 (Android 12):** Introduces **unattended updates**. Sideloading apps can update *themselves* completely silently (bypassing the user confirmation popup) if they meet the following criteria:
    1.  The app has `REQUEST_INSTALL_PACKAGES` and `UPDATE_PACKAGES_WITHOUT_USER_ACTION` permissions.
    2.  The update's target API level is 29 or higher.
    3.  The package name of the update matches the installing app (i.e., self-update).
    4.  The session parameter is configured with `setRequireUserAction(SessionParams.USER_ACTION_NOT_REQUIRED)`.
*   **API 34 (Android 14):** Introduces `PreapprovalDetails` and `requestUserPreapproval()`, allowing an app to prompt the user for permission *before* downloading the actual APK. Android 14 also blocks installing apps targeting below API 23 to mitigate old-SDK exploits.
*   **API 35 (Android 15):** Strict enforcement of Edge-to-Edge layout by default. System installation confirmation activities launch with fully transparent system bars, drawing over our app's visual structure.
*   **API 36 (Android 16):** Introduces severe Background Activity Launch (BAL) restrictions. The app *cannot* initiate a `PackageInstaller` session or trigger user confirmation dialogs if it is executing in the background without active UI. Update sequences must be fully foreground-driven.

### OEM-Specific Quirks (Russia-First Devices)
*   **Xiaomi (MIUI/HyperOS):** MIUI features a system option called **"MIUI Optimization"** and **"Secure Install"**. If "Secure Install" is enabled, the system intercepts local `PackageInstaller` commits and demands the user log in to a Xiaomi Account. To bypass, users may need to disable MIUI Optimization in Developer Options. Additionally, HyperOS forces a mandatory, non-skippable 10-second warning countdown before allowing the user to toggle "Install unknown apps".
*   **Huawei (EMUI/HarmonyOS):** These GMS-free devices have an active security mechanism called **"Pure Mode" (Чистый режим)**. If enabled, it completely blocks the installation of third-party apps outside of the Huawei AppGallery. Users must navigate to *Settings -> Security -> Pure Mode* to turn it off before our in-app self-updater can successfully commit.
*   **Samsung (One UI 6+ / Android 14+):** Features **"Auto Blocker" (Автоблокировка)**. If Auto Blocker is active, it completely disables sideloading, blocking any installation initiated by `PackageInstaller`. The app must detect failed sessions and inform the user to toggle Auto Blocker off under *Settings -> Security and privacy -> Auto Blocker*.

### Minimal Correct Kotlin `PackageInstaller` Pipeline
The following implementation demonstrates how to stream an APK from cache and execute the session. Sourced directly and modified for modern Kotlin Coroutines:

```kotlin
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageInstaller
import android.os.Build
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream

class SelfUpdater(private val context: Context) {

    companion object {
        private const val ACTION_INSTALL_STATUS = "com.macsia.teatiers.INSTALL_STATUS"
    }

    suspend fun installApk(apkFile: File) = withContext(Dispatchers.IO) {
        val packageManager = context.packageManager
        val packageInstaller = packageManager.packageInstaller

        // 1. Prepare Session Parameters
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
            setAppPackageName(context.packageName) // Enforce self-update only
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                // Try for silent update if API 31+
                setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
            }
        }

        var sessionId = -1
        var session: PackageInstaller.Session? = null
        try {
            // 2. Create and Open Session
            sessionId = packageInstaller.createSession(params)
            session = packageInstaller.openSession(sessionId)

            // 3. Stream APK Bytes into Session
            session.openWrite("teatiers_update", 0, apkFile.length()).use { outputStream ->
                FileInputStream(apkFile).use { inputStream ->
                    val buffer = ByteArray(65536)
                    var bytesRead: Int
                    while (inputStream.read(buffer).also { bytesRead = it } != -1) {
                        outputStream.write(buffer, 0, bytesRead)
                    }
                }
            }

            // 4. Create PendingIntent for Success/Failure Broadcast
            val intent = Intent(context, InstallStatusReceiver::class.java).apply {
                action = ACTION_INSTALL_STATUS
            }
            
            val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            } else {
                PendingIntent.FLAG_UPDATE_CURRENT
            }
            
            val pendingIntent = PendingIntent.getBroadcast(
                context,
                sessionId,
                intent,
                flags
            )

            // 5. Commit Session (Hands control to Android PackageInstaller)
            session.commit(pendingIntent.intentSender)

        } catch (e: Exception) {
            session?.abandon()
            throw e
        } finally {
            session?.close()
        }
    }
}

class InstallStatusReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)
        when (status) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                // Silent update requirements failed or API < 31. Launch system dialog.
                val confirmationIntent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    intent.getParcelableExtra(Intent.EXTRA_INTENT)
                }
                confirmationIntent?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)?.let {
                    context.startActivity(it)
                }
            }
            PackageInstaller.STATUS_SUCCESS -> {
                // Success! Android kills the process to complete the update.
                // We handle post-update relaunch using the ACTION_MY_PACKAGE_REPLACED receiver.
            }
            else -> {
                val message = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)
                // Log/Handle sideload failure (e.g. Signature Mismatch, Downgrade Block, Low Storage)
            }
        }
    }
}
```

### Relaunching Post-Update
Once `STATUS_SUCCESS` is achieved, Android forcibly kills our process. To automatically relaunch the application, we register a manifest-declared receiver targeting **`ACTION_MY_PACKAGE_REPLACED`**:

```xml
<receiver
    android:name=".UpdateRelaunchReceiver"
    android:exported="false">
    <intent-filter>
        <action android:name="android.intent.action.MY_PACKAGE_REPLACED" />
    </intent-filter>
</receiver>
```

```kotlin
class UpdateRelaunchReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_MY_PACKAGE_REPLACED) {
            val launchIntent = context.packageManager.getLaunchIntentForPackage(context.packageName)
            launchIntent?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(launchIntent)
        }
    }
}
```

---

## 2. Update Authenticity & Security

Sideloading an APK introduces severe attack surfaces (Remote Code Execution, malicious APK swaps). We must build a multi-layered security defense.

```
[Server/CI]  --> Signs Manifest (ECDSA SHA256) -> Serves JSON
                                                      |
[App Check]  --> Fetches JSON via Pinning TLS ------> Verified? (Yes)
                                                      |
[App Download]-> Downloads APK -> Validates SHA256 --> Local Signature Verification (Signer & Downgrade)
                                                      |
[Android OS] --> PackageInstaller enforces Same-Signer -> Successful Update!
```

### Android System-Level Protections & Limitations
Android natively enforces **Same-Signing-Certificate Check** on updates. If an attacker uploads a fake update to our server (or intercepts the download) and attempts to install it over a legitimate TeaTiers installation, Android's OS level will compare the new APK's signing certificate with the currently installed version's certificate. If they do not match, the installation fails immediately with `INSTALL_FAILED_UPDATE_INCOMPATIBLE`.
*   **The Limitation:** While this protects against overwriting an existing app, it does *not* prevent:
    1.  The app from wasting cellular data downloading a 15MB malicious payload.
    2.  The app triggering a system install prompt for a hostile app under a different package name or signature, confusing the user and risking a social-engineering attack.
    3.  Key Rotation (v3/v4 signatures): If we rotate keys, the lineage chain must be correctly declared, or update chains will break.

### The In-App Pre-Verify Shield (Mandatory Defense)
Before passing the file to the `PackageInstaller`, the app must execute local cryptographic verification on the downloaded APK.

1.  **Extract the Signer's Certificate Hash:**
    We must parse the downloaded file's raw certificate and compute its SHA-256 hash in-app.
2.  **Downgrade Protection:**
    Parse the `versionCode` of the local APK file and assert it is strictly higher than the running `versionCode`. This stops rollback attacks (reverting the app to an older version containing a known vulnerability).

```kotlin
import android.content.Context
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.os.Build
import java.io.File
import java.security.MessageDigest

class SecurityVerifier(private val context: Context) {

    // HARDCODED RELEASE SIGNATURE FINGERPRINT (SHA-256)
    private val EXPECTED_SIGNER_SHA256 = "6A:C5:11:0E:1F:B4:..." // Replace with actual SHA-256 of release cert

    fun verifyApkPreInstall(apkFile: File): Boolean {
        val pm = context.packageManager
        
        // 1. Parse Archive Metadata
        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            PackageManager.GET_SIGNING_CERTIFICATES
        } else {
            @Suppress("DEPRECATION")
            PackageManager.GET_SIGNATURES
        }
        
        val packageInfo = pm.getPackageArchiveInfo(apkFile.absolutePath, flags) ?: return false

        // 2. Assert Package Name Matches
        if (packageInfo.packageName != context.packageName) return false

        // 3. Prevent Downgrades
        val targetVersion = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            packageInfo.longVersionCode
        } else {
            @Suppress("DEPRECATION")
            packageInfo.versionCode.toLong()
        }
        val currentVersion = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            context.packageManager.getPackageInfo(context.packageName, 0).longVersionCode
        } else {
            @Suppress("DEPRECATION")
            context.packageManager.getPackageInfo(context.packageName, 0).versionCode.toLong()
        }
        if (targetVersion <= currentVersion) return false

        // 4. Extract and Compare Signer Certificate Fingerprint
        val signatures = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            packageInfo.signingInfo?.apkContentsSigners
        } else {
            @Suppress("DEPRECATION")
            packageInfo.signatures
        }

        if (signatures.isNullOrEmpty()) return false

        val signerBytes = signatures[0].toByteArray()
        val signerSha256 = computeSha256(signerBytes)

        return signerSha256.equals(EXPECTED_SIGNER_SHA256, ignoreCase = true)
    }

    private fun computeSha256(bytes: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val hash = digest.digest(bytes)
        return hash.joinToString(":") { String.format("%02X", it) }
    }
}
```

### Manifest Integrity (Verifying the JSON)
To prevent a compromised CDN or DNS hijack from supplying a tampered JSON manifest (pointing to a modified URL and modified SHA-256), we implement **Asymmetric Public Key Signing** of the update manifest.
*   **Process:** During build/release, the developer signs the manifest payload offline using a private ECDSA key. The signature is appended to the manifest JSON under the `signature` field.
*   **Verification:** The public key is embedded inside the TeaTiers app resource folder. Before processing any update, the app verifies the signature of the payload using this public key. If verification fails, the update process is aborted immediately.

---

## 3. Where the Version Check + APK Live, and RU Reachability

The distribution of a Russia-first Android app faces hostile networking realities. We must analyze where assets are hosted to guarantee reachability.

### Hosting Platform Comparison

| Option | Reachability in RU (No VPN) | Rate Limits | VM Footprint / Cost | Sideload Trust | Verdict |
| :--- | :--- | :--- | :--- | :--- | :--- |
| **(a) GitHub Releases API & CDN** | **Extremely Poor / Degrading**. Intermittent SNI blocks and active packet jamming. | Strict: 60 req/hr/IP anonymously. | $0 (Free) | Standard Sideload | Rejected as primary. |
| **(b) First-Party Yandex Backend (JSON only)** | **100% Reliable**. Fully inside RU peering. Unaffected by RKN blocks. | Set by our own Spring rate limiters. | Extremely Low (~5MB heap overhead) | Standard Sideload | **Approved (Primary Manifest)** |
| **(c) Yandex Object Storage (S3 Mirror)** | **100% Reliable**. High speed local CDN. | No throttling, pay-per-bandwidth. | Negligible cost (cents per GB) | Standard Sideload | **Approved (Primary APK)** |
| **(d) Self-Hosted F-Droid Repo** | Unstable (depends on hosting). | N/A | High (Requires `fdroidserver` stack) | Requires F-Droid Client App | Rejected (MVP constraint) |

### Detailed Analysis of Russian Internet Reachability
Russia's censorship infrastructure uses **TSPU (Technical Means to Counter Threats)** Deep Packet Inspection hardware. TSPU executes **active jamming** (corrupting TCP connections and interrupting TLS handshakes) on blocked or throttled Western development infrastructure. 

OONI (Open Observatory of Network Interference) data confirmed a major degradation of GitHub reachability inside Russia. Anomaly rates for connections to `api.github.com` and `raw.githubusercontent.com` spiked up to 16% on Russian ISPs. Files hosted on `objects.githubusercontent.com` (GitHub's Release Asset CDN) regularly stall or fail to complete handshakes entirely.

Furthermore, Russian mobile operators actively pool IP ranges. Having thousands of GMS-free devices directly polling the anonymous GitHub API will exhaust the 60 requests/hour limit globally for whole cellular towers in seconds, throwing HTTP `403 Rate Limit Exceeded` to legitimate users.

Additionally, many Russian users install the state-promoted **"Russian Trusted Root CA"** on their devices. Sideloaded apps are vulnerable to systemic SSL/TLS Man-in-the-Middle (MITM) attacks if they rely only on standard system trust stores.

### Recommended Topology (Primary + Fallback)
1.  **Primary Path:** Sourced strictly from Russia-first infrastructure.
    *   **Manifest Check:** The app calls our Spring Boot API: `https://tea.macsia.fun/api/v1/upgrade`.
    *   **APK Download:** The manifest returns a secure URL pointing directly to **Yandex Object Storage** (e.g., `https://storage.yandexcloud.net/teatiers-builds/teatiers-0.1.0.apk`). This ensures lightning-fast downloads on local RU networks without ISP throttling.
    *   **Transport Defense:** Inside the OkHttp client, we implement **SSL/TLS Pinning** targeting `tea.macsia.fun` and `storage.yandexcloud.net` using SHA-256 certificate public key hashes. This completely nullifies potential intercept attacks by domestic intermediate CAs.
2.  **Fallback Path:**
    *   If Yandex Object Storage or our VM is down, the manifest (optionally cached) or a secondary endpoint points to GitHub Releases assets (`objects.githubusercontent.com`). The app gracefully falls back, warning the user that "Download speeds may be slow due to network conditions."

---

## 4. Version-Check / Update Manifest Design

### Manifest JSON Schema (`tea_upgrade_manifest.json`)
The update manifest signature uses a SHA-256 with ECDSA algorithm. The `signature` field is calculated over a canonical string representation of the update details:

```json
{
  "latestVersionCode": 2,
  "latestVersionName": "0.2.0",
  "minSupportedVersionCode": 1,
  "apkUrl": "https://storage.yandexcloud.net/teatiers-builds/teatiers-v0.2.0.apk",
  "apkSha256": "8f4f3a76e93bc4b0f92b74070707070707070707070707070707070707070707",
  "releaseNotes": "• Добавлена поддержка экспорта в JSON\n• Оптимизация локальной базы данных Room\n• Исправление отображения профиля вкуса",
  "mandatory": false,
  "mirrors": [
    "https://github.com/macsia/teatiers/releases/download/v0.2.0/teatiers-release.apk"
  ],
  "signature": "MEQCIFz9O2Y3b0...Base64SignatureOfPayload..."
}
```

### Update Evaluation Rules
The logic inside TeaTiers compares the parsed payload against local variables:

```kotlin
val currentVersionCode = BuildConfig.VERSION_CODE

if (manifest.latestVersionCode > currentVersionCode) {
    if (currentVersionCode < manifest.minSupportedVersionCode || manifest.mandatory) {
        triggerForcedUpdateFlow(manifest)
    } else {
        triggerOptionalUpdateFlow(manifest)
    }
} else {
    // Already up to date.
}
```

### Polling Cadence and Caching (ETag / If-None-Match)
To minimize VM utilization on our single 4 GB VM, we must avoid constant heavy polling.
1.  **Cadence:** The app should only poll the upgrade endpoint **once per day** (using Jetpack WorkManager) or on **explicit manual trigger** in Settings.
2.  **OkHttp Cache Integration:** The Spring Boot backend must attach standard HTTP caching headers (`Cache-Control: private, max-age=86400`, `ETag`). OkHttp automatically parses these. When the app checks for an update, it sends an `If-None-Match` header. If no update is published, the backend returns a `304 Not Modified` payload with a 0-byte body, entirely bypassing database queries and serialization.

### Spring Boot Backend Upgrade Endpoint
```kotlin
package com.macsia.teatiers.backend.controller

import org.springframework.http.CacheControl
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.util.concurrent.TimeUnit

@RestController
@RequestMapping("/api/v1")
class UpgradeController {

    private val currentManifestEtag = "\"v2-ecdsa-sig-4493\""

    private val upgradeManifestPayload = """
        {
          "latestVersionCode": 2,
          "latestVersionName": "0.2.0",
          "minSupportedVersionCode": 1,
          "apkUrl": "https://storage.yandexcloud.net/teatiers-builds/teatiers-v0.2.0.apk",
          "apkSha256": "8f4f3a76e93bc4b0f92b74070707070707070707070707070707070707070707",
          "releaseNotes": "• Добавлена поддержка экспорта в JSON\n• Исправление отображения профиля вкуса",
          "mandatory": false,
          "mirrors": [
            "https://github.com/macsia/teatiers/releases/download/v0.2.0/teatiers-release.apk"
          ],
          "signature": "MEQCIFz9O2Y3b0..."
        }
    """.trimIndent()

    @GetMapping("/upgrade")
    fun checkForUpgrade(
        @RequestHeader(value = "If-None-Match", required = false) clientEtag: String?
    ): ResponseEntity<String> {
        
        // Return 304 if manifest hasn't changed (saves CPU + egress bandwidth)
        if (clientEtag == currentManifestEtag) {
            return ResponseEntity
                .status(HttpStatus.NOT_MODIFIED)
                .cacheControl(CacheControl.maxAge(1, TimeUnit.DAYS).cachePrivate())
                .eTag(currentManifestEtag)
                .build()
        }

        return ResponseEntity
            .ok()
            .cacheControl(CacheControl.maxAge(1, TimeUnit.DAYS).cachePrivate())
            .eTag(currentManifestEtag)
            .header("Content-Type", "application/json")
            .body(upgradeManifestPayload)
    }
}
```

---

## 5. Open-Source Libraries vs. Build-Our-Own

For sideloaded self-updates, using a heavily-maintained, security-focused wrapper library is vastly superior to hand-rolling standard boilerplate, which frequently breaks on targetSdk 34–36.

### Sideloading Package Installer Library Matrix

| Library Name | Coordinates & Latest Version | SPDX License | Last Release | GMS Free? | Supports Security Model? | RU Viable? | Verdict |
| :--- | :--- | :--- | :--- | :--- | :--- | :--- | :--- |
| **Ackpine** | `ru.solrudev.ackpine:ackpine-core:0.22.8` | Apache-2.0 | April 30, 2026 | **Yes** | **Yes** (Provides low-level stream injection) | **Yes** (Uses local PackageInstaller) | **Highly Recommended**. Solves Session lifecycle, status callbacks, and process death. |
| **SimpleInstaller** | `io.github.solrudev:simpleinstaller:5.0.0` | Apache-2.0 | Dec 5, 2023 (Archived) | **Yes** | No (Missing API 34+ optimizations) | No (Archived) | **Do Not Use**. Archived and replaced by Ackpine. |
| **AppUpdater** (JavierSantos) | `com.github.javiersantos:AppUpdater:2.7` | Apache-2.0 | ~2019 (Unmaintained) | No (Features Play Store dependencies) | No (Outdated, lacks Session API) | No | **Do Not Use**. Hard crashes on targetSdk 30+. |
| **Obtainium** | *Not applicable (Stand-alone App, not library)* | GPL-3.0 | Active | Yes | Yes | Yes | **Do Not Use**. This is an external app store, not an embeddable SDK. |

### Recommendation: Use Ackpine + Custom Verification Pipeline
We recommend implementing a hybrid architecture:
1.  **Use Ackpine** (`ru.solrudev.ackpine:ackpine-core` + `ackpine-ktx`) exclusively to abstract the complex, error-prone `PackageInstaller` Session state machines, transaction tracking, and background service execution.
2.  **Build a Small Custom Kotlin Helper** to manage the download (via OkHttp), verify the SHA-256 checksum, and enforce the signature and downgrade security limits *before* passing the verified APK file to Ackpine's installer session. This keeps the codebase highly reliable, fully secure, and 100% GMS-free.

---

## 6. Update UX & Policy (Russia-First, Respectful)

Russian users are highly accustomed to APK sideloading due to GMS-free ecosystems (Huawei AppGallery, RuStore, direct downloads). However, updates must be unobtrusive and respect data limits.

```
                    [Update Available]
                            |
             Is it Mandatory/Hard-Cut?
              /                      \
          (Yes)                      (No)
            |                          |
    [Forced Dialog]            [Subtle Settings Card]
    - Blocks app access        - Click to Update
    - Shows progress bar       - Dismissable
            |                          |
       Is Metered/Roaming Connection Active?
              \                      /
               \                    /
          (Yes) -> Warn User (Data Costs)
          (No)  -> Proceed
```

### Visual Prompts & Policies
*   **Optional Update available:** Display a non-intrusive card or a subtle dot marker inside the "Settings" or "About" view of TeaTiers. Never disrupt the user's board editing or tea rating flows with popup nags on launch. Include a "Skip this version" option, which stores the skipped `versionCode` in Android `DataStore` to suppress notifications for that version.
*   **Mandatory Update (Hard-cut):** If `currentVersionCode < minSupportedVersionCode`, show a full-screen, non-dismissible dialog explaining that a major backend API evolution or critical security fix has occurred. Provide a single CTA button: "Download Update." Disable the app's database access until the update is completed.

### Network Safeguards
Before automatically triggering or prompting a download, check the active network context:
```kotlin
val connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
val activeNetwork = connectivityManager.activeNetwork ?: return
val capabilities = connectivityManager.getNetworkCapabilities(activeNetwork) ?: return

val isMetered = capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED).not()
val isRoaming = capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_ROAMING).not()
```
If `isMetered` or `isRoaming` is `true`, halt background downloads. If the user clicks "Download" manually, present a warning dialog: *"Вы используете лимитное подключение или роуминг. Загрузка обновления (15 МБ) может повлечь дополнительные расходы."*

### Sideload Authorization UX Flow
1.  **Pre-Permission Context:** If `canRequestPackageInstalls()` is `false`, **do not immediately jump to the system settings**. Sideloading users in Russia are familiar with permissions but appreciate transparency. Display a clean Compose BottomSheet or dialog:
    *   *Title:* "Разрешение на обновление"
    *   *Message:* "TeaTiers работает автономно без Google Play. Чтобы установить обновление, системе Android требуется дать разрешение на 'Установку неизвестных приложений' для этого приложения."
    *   *Buttons:* "Перейти в настройки" | "Отмена".
2.  **Post-Permission Return:** When the user returns from system settings, check the state. If they granted authorization, immediately execute the transactional `PackageInstaller` session.

---

## 7. Recommended Architecture & Phased Plan

### End-to-End Update Pipeline
1.  **Trigger:** Periodic background worker runs once a day (Jetpack WorkManager).
2.  **Fetch:** Polls `https://tea.macsia.fun/api/v1/upgrade` using an OkHttp Client with hardcoded Certificate Pinning. Passes cached `ETag` to avoid processing [4.0].
3.  **Validate Manifest:** Backend returns `200 OK` with JSON manifest if an update is available [4.0]. The app parses the JSON and verifies the ECDSA cryptographic signature against the embedded public key.
4.  **Evaluate:** If `latestVersionCode` > current, prompt the user or cache the request depending on the mandatory flag.
5.  **Download:** Download the target APK over TLS directly from Yandex Object Storage into `context.cacheDir/updates/update.apk`.
6.  **Verify APK:**
    *   Verify the file's SHA-256 hash matches `apkSha256`.
    *   Verify the file's parsed package name is `com.macsia.teatiers`.
    *   Verify the parsed `versionCode` is higher than current (downgrade check).
    *   Verify the parsed signing certificate SHA-256 matches our hardcoded release key.
7.  **Install:** Pass the file to **Ackpine**, executing a structured `PackageInstaller` session.
8.  **Relaunch:** Android completes the installation, kills the app process, and the system-declared `ACTION_MY_PACKAGE_REPLACED` receiver restarts the fresh app version.

### Phased Roadmap

```
Phase 1: Basic Sideload Flow (Internal/Testing)
 ├── Deploy Spring JSON manifest endpoint (no signatures).
 ├── Wire up Ackpine local PackageInstaller session flow.
 └── Set up manual "Check for Updates" button in Settings.

Phase 2: Security Hardening & Edge Optimization (Pre-MVP)
 ├── Implement APK Pre-Verification (Signer Hash Check & Downgrade block).
 ├── Setup SSL Pinning in OkHttp for Yandex Cloud domains.
 └── Wire up WorkManager daily background update tasks.

Phase 3: Deep Security & Public Launch (MVP Release)
 ├── Integrate ECDSA Signature signing of Manifest JSON files in CI/CD.
 ├── Implement unattended update flags (USER_ACTION_NOT_REQUIRED) for API 31+.
 └── Add network state guards (Metered/Roaming data-saving blocks).
```

### Locked Constraint Check
This blueprint operates **100% GMS-free**, utilizes zero third-party accounts, manages zero PII, fits comfortably on our current 4GB single-VM Yandex backend, and maintains excellent reachability and high security on all networks inside Russia.

---

## 8. Reference Links & Sources

1.  **OONI Censorship Measurements - Russia May 2026:** [OONI Project Report on Deterioration of GitHub Reachability in Russia](https://meduza.io/news/2026/05/08/verstka-ooni-zafiksirovala-problemy-s-dostupom-k-github-v-rossii) (Access Date: June 19, 2026).
2.  **Android PackageInstaller Session API Docs:** [Official Developer Documentation for PackageInstaller](https://developer.android.com/reference/android/content/pm/PackageInstaller).
3.  **Android 12 Unattended App Updates:** [PackageInstaller.SessionParams#setRequireUserAction](https://developer.android.com/reference/android/content/pm/PackageInstaller.SessionParams#setRequireUserAction(int)).
4.  **Security Guidelines with TLS on Android:** [Android Developer Reference for SSL Pinning and Trust Managers](https://developer.android.com/training/articles/security-ssl).
5.  **Ackpine Library Repository:** [Ilya Fomichev (solrudev) - Ackpine Git Repo](https://github.com/solrudev/Ackpine).
6.  **Russia Ministry of Digital Development Root CAs:** [EFF Analysis of National Root Certificates in Russia](https://www.eff.org/deeplinks/2022/03/you-should-not-trust-russias-new-trusted-root-ca).
7.  **Xiaomi, Samsung, and Huawei Sideloading Protocols:** [Uptodown Help Center - Sideloading Variations across OEM UI](https://help.uptodown.com/hc/en-us/articles/9327885901329-How-to-download-apps-from-unknown-sources).