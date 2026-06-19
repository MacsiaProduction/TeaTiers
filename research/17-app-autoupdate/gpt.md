# Decision

For TeaTiers MVP, build a **small custom updater**:

**Version check:** first-party `GET https://tea.macsia.fun/api/v1/app/android/upgrade` served by the existing Spring/Caddy backend, ETag-cached, anonymous, and backed by a static signed JSON manifest.

**APK download:** primary APK mirror on **Yandex Object Storage** under a TeaTiers-controlled download hostname, with **GitHub Releases as public canonical source and fallback/manual link**. GitHub should not be the primary in-app update path for Russia-first users because recent 2026 reports show degraded GitHub access from Russian networks, while Roskomnadzor/GRFC publicly denied a formal block; that is exactly the kind of unstable reachability the design must tolerate.  ([Meduza][1])

**Install mechanism:** use Android’s **`PackageInstaller` Session API**, not Play In-App Updates and not the deprecated intent-first path. `ACTION_INSTALL_PACKAGE` is deprecated since API 29, while `PackageInstaller` sessions are the current platform API. Installs still require user confirmation and the per-source “Install unknown apps” grant on API 26+. ([Android Developers][2])

**Security bar:** verify all three before opening the installer: **signed manifest → APK SHA-256 → APK package/version/signing cert pin**. Android’s same-signer update enforcement is useful, but it is not enough as the only control for a self-updater because the manifest/download path can be attacked, the first-install case has no installed package to compare against, and a compromised manifest can still cause forced-update abuse or denial-of-service.

---

# 1. Recommended architecture

## Primary path

```
TeaTiers app
  └─ GET /api/v1/app/android/upgrade
       Host: tea.macsia.fun
       If-None-Match: "..."
       X-TeaTiers-App-Version-Code: 1
       X-TeaTiers-Channel: stable
       no device id, no account, no advertising id
          ↓
     signed JSON manifest
          ↓
     download APK from Yandex Object Storage mirror
          ↓
     verify manifest signature, APK SHA-256, packageName, versionCode, signer cert pin
          ↓
     PackageInstaller Session API
          ↓
     Android user confirmation dialog
```

This keeps the update check on the same first-party infrastructure the app already uses and avoids every device polling GitHub’s API. GitHub’s unauthenticated REST API limit is **60 requests per hour per originating IP**, which is especially bad behind carrier NATs or office/shared networks. GitHub also says release assets are downloaded through asset URLs / redirects, and GitHub release asset traffic may involve `release-assets.githubusercontent.com` / `*.githubusercontent.com`, so relying on GitHub means relying on several GitHub/CDN hostnames being reachable from Russian ISPs. ([GitHub Docs][3])

Yandex Object Storage is a good fit because it can host static files, can be put behind CDN/TLS, and the bandwidth/storage economics are appropriate for APK mirrors rather than a heavy update server. Yandex documents static website hosting for Object Storage, CDN + TLS integration, and low/free included outbound transfer tiers. ([Yandex Cloud][4])

## Fallback path

The manifest should include ordered mirrors:

1. `https://downloads.tea.macsia.fun/android/stable/teatiers-0.1.1.apk`
   backed by Yandex Object Storage.
2. `https://github.com/.../releases/download/v0.1.1/teatiers-0.1.1.apk`
   fallback and manual-public source.
3. Optional later: another Russian-friendly static mirror.

The app should try mirrors in order. A failed mirror is not trusted or distrusted by hostname; **every mirror must pass the same hash and signer checks**.

## Why not GitHub as the primary updater source?

GitHub Releases are still useful as the public canonical release page, but not as the primary update check/download path. In May 2026, Russian users and OONI-derived reporting showed GitHub connection failures increasing from normal low levels to roughly 10–16% during a disruption window, while Russian authorities denied blocking. That combination means “not reliably broken, not reliably reachable,” which is the worst default for an autoupdater. ([Meduza][1])

---

# 2. Android install mechanism, API 26–36

Use **`PackageInstaller` Session API** for the main path.

`ACTION_INSTALL_PACKAGE` / `ACTION_VIEW` with an APK content URI is the legacy path and is deprecated from API 29. It remains useful only as an emergency OEM fallback, and it needs a `FileProvider`/`content://` URI because exposing `file://` APK paths is not acceptable on modern Android. ([Android Developers][2])

On API 26+, Android moved from one global “Unknown sources” switch to **per-source install grants**. The user must allow TeaTiers itself as a source before TeaTiers can request APK installs. Android exposes this through `PackageManager.canRequestPackageInstalls()` and the `ACTION_MANAGE_UNKNOWN_APP_SOURCES` settings screen. Apps targeting Android O+ must declare `REQUEST_INSTALL_PACKAGES` for this flow. ([Android Developers][5])

`PackageInstaller.Session.commit()` can still require user intervention; it reports that through the callback intent, often as `STATUS_PENDING_USER_ACTION`. On API 35+ / target 35+, the status receiver `PendingIntent` must not be immutable, so use `FLAG_MUTABLE`. ([Android Developers][6])

API-level notes:

| API level | Practical TeaTiers behavior                                                                                                                                                                                            |
| --------- | ---------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| 26–28     | Per-source “Install unknown apps” grant required. Use `PackageInstaller`; signer inspection uses older `GET_SIGNATURES` fallback where needed.                                                                         |
| 29        | `ACTION_INSTALL_PACKAGE` deprecated; use `PackageInstaller`.                                                                                                                                                           |
| 31        | `SessionParams.setRequireUserAction(...)` exists. Use `USER_ACTION_REQUIRED` for respectful self-update UX. ([Android Developers][7])                                                                                  |
| 33        | If using progress notifications, request `POST_NOTIFICATIONS`; otherwise keep progress inside the foreground Activity.                                                                                                 |
| 34        | Android 14 adds install pre-approval and update-ownership features. Pre-approval can be added later to ask install permission before downloading large APKs, but it is not required for MVP. ([Android Developers][8]) |
| 35–36     | Use mutable `PendingIntent` for `commit()` callbacks; continue using `PackageInstaller`. No GMS/Play path.                                                                                                             |

OEM caveats to test explicitly on Russia-relevant devices: Xiaomi/HyperOS/MIUI, Huawei/Honor, Samsung, and AOSP/de-Googled builds. Expect extra installer warnings, vendor security-scan screens, aggressive background limits, and inconsistent callback timing. The updater must recover by showing a persistent in-app state: “downloaded, verified, tap to install again.”

---

# 3. Minimal Android manifest entries

```xml
<manifest ...>
    <uses-permission android:name="android.permission.INTERNET" />
    <uses-permission android:name="android.permission.ACCESS_NETWORK_STATE" />
    <uses-permission android:name="android.permission.REQUEST_INSTALL_PACKAGES" />

    <!-- Only needed if you show download progress through notifications on Android 13+. -->
    <uses-permission android:name="android.permission.POST_NOTIFICATIONS" />

    <application ...>
        <receiver
            android:name=".updates.InstallStatusReceiver"
            android:exported="false" />

        <!-- Only needed for a legacy ACTION_VIEW/ACTION_INSTALL_PACKAGE fallback. -->
        <provider
            android:name="androidx.core.content.FileProvider"
            android:authorities="${applicationId}.fileprovider"
            android:exported="false"
            android:grantUriPermissions="true">
            <meta-data
                android:name="android.support.FILE_PROVIDER_PATHS"
                android:resource="@xml/update_file_paths" />
        </provider>
    </application>
</manifest>
```

The network permissions are standard for HTTPS update checks/downloads. `REQUEST_INSTALL_PACKAGES` is the important self-update permission; Play policy would heavily restrict it, but TeaTiers is not using Google Play for MVP. Play In-App Updates are intentionally out because that flow is part of Play Core / Google Play delivery. ([Android Developers][9])

---

# 4. Minimal Kotlin pipeline

This is the shape I would implement. It assumes the APK has already been downloaded into app-private storage such as `cacheDir/updates/`.

## Constants

```kotlin
private const val APP_ID = "com.macsia.teatiers"

// Replace with the SHA-256 of the TeaTiers release signing certificate,
// not the APK file hash.
private const val RELEASE_CERT_SHA256_HEX =
    "REPLACE_WITH_64_HEX_RELEASE_CERT_SHA256"
```

## Unknown-source grant check

```kotlin
fun ensureCanRequestPackageInstalls(activity: Activity): Boolean {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return true

    val pm = activity.packageManager
    if (pm.canRequestPackageInstalls()) return true

    val intent = Intent(
        Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
        Uri.parse("package:${activity.packageName}")
    )
    activity.startActivity(intent)
    return false
}
```

Call this only after the user taps “Update,” not silently on app start.

## APK hash

```kotlin
fun sha256Hex(file: File): String {
    val digest = MessageDigest.getInstance("SHA-256")
    file.inputStream().use { input ->
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            digest.update(buffer, 0, read)
        }
    }
    return digest.digest().joinToString("") { "%02x".format(it) }
}
```

## Package archive parsing

```kotlin
@Suppress("DEPRECATION")
fun getArchivePackageInfo(pm: PackageManager, apk: File): PackageInfo {
    val flags =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P)
            PackageManager.GET_SIGNING_CERTIFICATES
        else
            PackageManager.GET_SIGNATURES

    return if (Build.VERSION.SDK_INT >= 33) {
        pm.getPackageArchiveInfo(
            apk.absolutePath,
            PackageManager.PackageInfoFlags.of(flags.toLong())
        )
    } else {
        pm.getPackageArchiveInfo(apk.absolutePath, flags)
    } ?: error("APK is not a parseable Android package")
}
```

Android’s `getPackageArchiveInfo()` is the platform method for reading package metadata from an APK archive without installing it. On modern Android, signing-certificate data is exposed through `GET_SIGNING_CERTIFICATES` / `SigningInfo`; older devices need the deprecated signature fallback. ([Android Developers][5])

## Signing certificate hash extraction

```kotlin
@Suppress("DEPRECATION")
fun signingCertSha256Hexes(packageInfo: PackageInfo): Set<String> {
    val signatures: Array<Signature> =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val info = packageInfo.signingInfo
                ?: error("APK has no signingInfo")

            if (info.hasMultipleSigners()) {
                info.apkContentsSigners
            } else {
                info.signingCertificateHistory
            }
        } else {
            packageInfo.signatures
                ?: error("APK has no legacy signatures")
        }

    return signatures.map { sig ->
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(sig.toByteArray())
        digest.joinToString("") { "%02x".format(it) }
    }.toSet()
}
```

If TeaTiers ever rotates its signing key, use APK Signature Scheme v3/v3.1 lineage deliberately and update the pinned certificate policy in the app. Android supports key rotation through APK Signature Scheme v3 proof-of-rotation, and `SigningInfo` can expose signing history. ([Android Open Source Project][10])

## APK verification

```kotlin
data class AndroidUpdate(
    val versionCode: Long,
    val versionName: String,
    val apkSha256: String,
    val apkSizeBytes: Long,
    val mirrors: List<String>
)

fun verifyDownloadedApk(
    context: Context,
    update: AndroidUpdate,
    apk: File
) {
    require(apk.isFile) { "Downloaded APK does not exist" }
    require(apk.length() == update.apkSizeBytes) {
        "APK size mismatch"
    }

    val actualApkHash = sha256Hex(apk)
    require(actualApkHash.equals(update.apkSha256, ignoreCase = true)) {
        "APK SHA-256 mismatch"
    }

    val pm = context.packageManager
    val archiveInfo = getArchivePackageInfo(pm, apk)

    require(archiveInfo.packageName == APP_ID) {
        "APK packageName mismatch: ${archiveInfo.packageName}"
    }

    val archiveVersionCode =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            archiveInfo.longVersionCode
        } else {
            @Suppress("DEPRECATION")
            archiveInfo.versionCode.toLong()
        }

    val installedInfo = pm.getPackageInfo(context.packageName, 0)
    val installedVersionCode =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            installedInfo.longVersionCode
        } else {
            @Suppress("DEPRECATION")
            installedInfo.versionCode.toLong()
        }

    require(archiveVersionCode == update.versionCode) {
        "APK versionCode does not match manifest"
    }

    require(archiveVersionCode > installedVersionCode) {
        "Refusing downgrade or same-version install"
    }

    val archiveSignerHashes = signingCertSha256Hexes(archiveInfo)
    require(RELEASE_CERT_SHA256_HEX.lowercase() in archiveSignerHashes) {
        "APK signer certificate is not the pinned TeaTiers release certificate"
    }

    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
        val installedMatchesPinnedCert = pm.hasSigningCertificate(
            context.packageName,
            hexToBytes(RELEASE_CERT_SHA256_HEX),
            PackageManager.CERT_INPUT_SHA256
        )
        require(installedMatchesPinnedCert) {
            "Installed app is not signed by the pinned TeaTiers release certificate"
        }
    }
}

fun hexToBytes(hex: String): ByteArray {
    require(hex.length % 2 == 0)
    return ByteArray(hex.length / 2) { i ->
        hex.substring(i * 2, i * 2 + 2).toInt(16).toByte()
    }
}
```

`PackageManager.hasSigningCertificate()` is preferable on API 28+ because it accounts for signing history / rotation where appropriate, whereas raw old `GET_SIGNATURES` checks are deprecated. Android also exposes `checkSignatures()` for installed-package comparisons, but for a downloaded archive the stronger pattern is: parse archive → inspect package name/version/signers → pin the expected release certificate. ([Android Developers][5])

## Install through `PackageInstaller`

```kotlin
fun installApkWithPackageInstaller(context: Context, apk: File) {
    val installer = context.packageManager.packageInstaller

    val params = PackageInstaller.SessionParams(
        PackageInstaller.SessionParams.MODE_FULL_INSTALL
    ).apply {
        setAppPackageName(context.packageName)
        setSize(apk.length())

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            setRequireUserAction(
                PackageInstaller.SessionParams.USER_ACTION_REQUIRED
            )
        }
    }

    val sessionId = installer.createSession(params)

    installer.openSession(sessionId).use { session ->
        apk.inputStream().use { input ->
            session.openWrite("teatiers-update.apk", 0, apk.length()).use { output ->
                input.copyTo(output)
                session.fsync(output)
            }
        }

        val callbackIntent = Intent(context, InstallStatusReceiver::class.java)
            .setPackage(context.packageName)
            .putExtra("sessionId", sessionId)

        val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE

        val pendingIntent = PendingIntent.getBroadcast(
            context,
            sessionId,
            callbackIntent,
            flags
        )

        session.commit(pendingIntent.intentSender)
    }
}
```

`Session.openWrite()` streams the APK into the install session, and `commit()` finishes the session through an `IntentSender`. Android documents that `commit()` may require user action and may not complete immediately. ([Android Developers][6])

## Install result receiver

```kotlin
class InstallStatusReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val status = intent.getIntExtra(
            PackageInstaller.EXTRA_STATUS,
            PackageInstaller.STATUS_FAILURE
        )

        when (status) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                val confirmationIntent =
                    if (Build.VERSION.SDK_INT >= 33) {
                        intent.getParcelableExtra(
                            Intent.EXTRA_INTENT,
                            Intent::class.java
                        )
                    } else {
                        @Suppress("DEPRECATION")
                        intent.getParcelableExtra(Intent.EXTRA_INTENT)
                    }

                confirmationIntent?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                if (confirmationIntent != null) {
                    context.startActivity(confirmationIntent)
                }
            }

            PackageInstaller.STATUS_SUCCESS -> {
                // The process may have been replaced. Prefer a notification or
                // let Android's installer show "Open".
                showUpdateInstalledNotification(context)
            }

            else -> {
                val message = intent.getStringExtra(
                    PackageInstaller.EXTRA_STATUS_MESSAGE
                ) ?: "Unknown installer failure"
                showUpdateFailedNotification(context, message)
            }
        }
    }
}
```

Do not promise a silent background update. For a normal sideloaded app, the user will see Android’s package installer confirmation UI.

---

# 5. Manifest authenticity and APK security

## What Android enforces

Android enforces that an update to an already-installed package must be compatible with the installed package identity and signing lineage. That means a malicious APK with `packageName = com.macsia.teatiers` but a different signing certificate should fail as an update. Android’s modern signing APIs also understand signing-certificate history for v3 key rotation. ([Android Developers][11])

## What TeaTiers must enforce itself

Android does **not** authenticate your update manifest, decide which URL is trustworthy, prevent a compromised backend from setting `mandatory = true`, or protect the first-install/manual-install case. TeaTiers must enforce:

1. **Manifest signature**
   Serve the manifest over HTTPS, but also sign the exact response body with an offline key. For minSdk 26, prefer **ECDSA P-256 with SHA-256** via Android’s built-in JCA providers, or bundle a well-reviewed Ed25519 verifier if you specifically want Ed25519.

2. **APK SHA-256**
   The APK file hash in the signed manifest must match the downloaded bytes.

3. **APK signer certificate pin**
   The APK’s signing certificate SHA-256 must match the hardcoded TeaTiers release certificate pin.

4. **Package name**
   Reject anything except `com.macsia.teatiers`.

5. **Version monotonicity**
   Reject `versionCode <= installedVersionCode`.

6. **Manifest version consistency**
   The parsed APK `versionCode` must equal manifest `latest.versionCode`.

7. **TLS only**
   No HTTP mirrors. Do not rely on TLS alone; use TLS plus signed manifest plus APK verification.

8. **Key rotation plan**
   Before public release, document how a new signing certificate is introduced. Android APK Signature Scheme v3/v3.1 supports proof-of-rotation, but older API 26–27 devices need a conservative fallback strategy because signing-lineage support is newer than minSdk 26. ([Android Open Source Project][10])

## Minimum vs. belt-and-suspenders

Minimum acceptable for internal testers:

```
HTTPS first-party manifest
+ APK SHA-256 in manifest
+ packageName/versionCode checks
+ release signing cert SHA-256 pin
+ Android same-signer enforcement
```

Recommended before wider public use:

```
Minimum
+ offline-signed manifest body
+ multiple mirrors with identical hash/signature requirements
+ downgrade protection
+ emergency minSupportedVersionCode gate
+ release-key rotation playbook
+ physical OEM test matrix
```

---

# 6. Manifest JSON schema

Prefer a **signed body with a detached HTTP signature header** rather than putting the signature inside JSON. That avoids fragile JSON canonicalization.

Response headers:

```http
ETag: "sha256-<manifest-body-sha256>"
Cache-Control: max-age=3600, must-revalidate
X-TeaTiers-Manifest-Signature: base64url(ecdsa_p256_sha256_signature_over_exact_body)
Content-Type: application/json; charset=utf-8
```

Body:

```json
{
  "schemaVersion": 1,
  "app": {
    "platform": "android",
    "packageName": "com.macsia.teatiers",
    "channel": "stable"
  },
  "generatedAt": "2026-06-19T12:00:00Z",
  "minSupportedVersionCode": 1,
  "latest": {
    "versionCode": 2,
    "versionName": "0.1.1",
    "minSdk": 26,
    "targetSdk": 36,
    "apkName": "teatiers-0.1.1-universal-release.apk",
    "apkSizeBytes": 18452231,
    "apkSha256": "64_lowercase_hex_chars",
    "signingCertSha256": "64_lowercase_hex_chars",
    "mandatory": false,
    "publishedAt": "2026-06-19T12:00:00Z",
    "releaseNotes": {
      "en": "Bug fixes and updater improvements.",
      "ru": "Исправления ошибок и улучшения обновления."
    },
    "mirrors": [
      {
        "priority": 1,
        "kind": "yandex-object-storage",
        "url": "https://downloads.tea.macsia.fun/android/stable/teatiers-0.1.1-universal-release.apk",
        "regionHint": "RU"
      },
      {
        "priority": 2,
        "kind": "github-release",
        "url": "https://github.com/macsia/teatiers/releases/download/v0.1.1/teatiers-0.1.1-universal-release.apk",
        "regionHint": "global"
      }
    ]
  },
  "manualUrl": "https://github.com/macsia/teatiers/releases/latest",
  "messages": []
}
```

Decision logic:

```text
if manifest.app.packageName != BuildConfig.APPLICATION_ID:
    reject manifest

if currentVersionCode < minSupportedVersionCode:
    forced update gate for network/catalog/OCR features

if latest.versionCode <= currentVersionCode:
    no update

if latest.versionCode > currentVersionCode and latest.mandatory == false:
    optional update prompt

if latest.versionCode > currentVersionCode and latest.mandatory == true:
    mandatory update prompt
```

For a local-first app, even a forced update should not lock the user out of existing on-device boards unless there is a local data-loss/security reason. A sane TeaTiers policy is: **old clients may be blocked from backend catalog/OCR calls, but local boards remain accessible**.

---

# 7. Backend endpoint shape

```http
GET /api/v1/app/android/upgrade?packageName=com.macsia.teatiers&channel=stable
If-None-Match: "sha256-..."
X-TeaTiers-App-Version-Code: 1
X-TeaTiers-App-Version-Name: 0.1.0
Accept: application/json
```

Responses:

```http
200 OK
ETag: "sha256-..."
X-TeaTiers-Manifest-Signature: ...
Content-Type: application/json; charset=utf-8

{ manifest body }
```

```http
304 Not Modified
ETag: "sha256-..."
```

```http
400 Bad Request
Content-Type: application/problem+json
```

No accounts, no logins, no Android ID, no serial, no advertising ID. `versionCode`, `packageName`, `channel`, and locale are enough.

Implementation options on the 4 GB VM:

**Best MVP implementation:** Spring serves a static pre-signed manifest file from disk or classpath. Caddy can cache it. No database table is required.

**Release process:**

```text
1. Build signed universal release APK.
2. Compute APK SHA-256 and size.
3. Upload APK to GitHub Release.
4. Upload same APK to Yandex Object Storage.
5. Generate manifest JSON.
6. Sign exact manifest bytes offline.
7. Publish manifest + signature to backend/static path.
8. Smoke-test: app downloads from both mirrors and verifies hash/signer.
```

GitHub’s own “latest release” endpoint is not ideal for the client because it selects the latest non-draft/non-prerelease release according to GitHub’s rules, and polling it directly also hits GitHub’s anonymous API limit. Keep GitHub as a human-facing release page and CI source, not as every client’s first API call. ([GitHub Docs][12])

Polling cadence:

| Trigger                    | Behavior                                                          |
| -------------------------- | ----------------------------------------------------------------- |
| App launch                 | Check only if last successful/failed check is older than 24h.     |
| Manual “Check for updates” | Always check, with rate limiting after repeated failures.         |
| Background                 | Daily WorkManager job with jitter.                                |
| HTTP caching               | Send `If-None-Match`; honor `304 Not Modified`.                   |
| Failure                    | Exponential backoff; do not nag.                                  |
| Download                   | Ask user first; default to Wi-Fi/unmetered unless user overrides. |

GitHub recommends conditional requests with `ETag`/`Last-Modified` for API use, but the primary design avoids putting clients on GitHub’s API path at all. ([GitHub Docs][13])

---

# 8. Library comparison

| Option                                                  | Coordinates / version                                                    |                                               License | Last release / freshness                                              | GMS-free?                                                                                   | Supports TeaTiers security model?                                                                               | RU viability                                                                      | Verdict                                                                                    |
| ------------------------------------------------------- | ------------------------------------------------------------------------ | ----------------------------------------------------: | --------------------------------------------------------------------- | ------------------------------------------------------------------------------------------- | --------------------------------------------------------------------------------------------------------------- | --------------------------------------------------------------------------------- | ------------------------------------------------------------------------------------------ |
| **Custom OkHttp/Ktor + WorkManager + PackageInstaller** | No updater library; use Android platform APIs                            |                                                   N/A | Fully under project control                                           | Yes                                                                                         | **Yes**: signed manifest, APK hash, signer pin, downgrade protection, ordered mirrors                           | **Best**: first-party Yandex manifest + Yandex APK mirror                         | **Recommended**                                                                            |
| **Ackpine**                                             | `ru.solrudev.ackpine:ackpine-core:0.23.0`, optional `ackpine-ktx:0.23.0` |                                            Apache-2.0 | 0.23.0 published May 20, 2026                                         | Appears GMS-free; do not use optional Shizuku/root paths                                    | Partial: good installer-session abstraction, but you still build manifest/download/signature verification       | Good if paired with Yandex manifest/mirror                                        | Optional wrapper, not required for MVP ([GitHub][14])                                      |
| **AppUpdater by Javier Santos**                         | `com.github.javiersantos:AppUpdater:2.7` via JitPack                     |                                            Apache-2.0 | 2.7 listed Apr 14, 2025                                               | Cannot fully confirm dependency graph from current evidence; repo supports non-Play sources | **No**: project FAQ says it does not self-update/download/install; it mainly checks/notifies                    | Weak if GitHub-backed; own-server check possible                                  | Do not use for TeaTiers updater core ([GitHub][15])                                        |
| **Obtainium**                                           | App, not embeddable Maven library; current release observed `v1.4.3`     |                                               GPL-3.0 | Apr 16, 2026 release                                                  | Yes                                                                                         | External app model; can install/update from release pages, but not TeaTiers in-app UX or signed manifest policy | Mixed: GitHub/source reachability still matters unless pointed at TeaTiers mirror | Good power-user alternative, not MVP in-app updater ([GitHub][16])                         |
| **F-Droid repo + F-Droid client / F-Droid Basic**       | `fdroidserver`; F-Droid client app, not in-app library                   | AGPL-3.0+ for server tooling; GPL for client variants | `fdroidserver` 2.4.4 observed in package index; verify before pinning | Yes                                                                                         | Strong repo-index signatures and APK hashes, but requires users to install/subscribe to a repo client           | Good if repo hosted on Yandex/static web                                          | Excellent later distribution channel, too much UX/ops for MVP self-updater ([GitHub][17])  |
| **SimpleInstaller**                                     | `io.github.solrudev:simpleinstaller:5.0.0`                               |                                            Apache-2.0 | Deprecated in favor of Ackpine                                        | Appears GMS-free                                                                            | Installer wrapper only; no manifest/hash/signature model                                                        | OK only as installer wrapper                                                      | Do not start new code with deprecated library ([GitHub][18])                               |
| **KMP App Updater**                                     | `com.pavi2410.kmp-app-updater:core:0.1.0`, `compose-ui:0.1.0`            |            License not confirmed from captured source | Announced Feb 22, 2026; very new                                      | Not enough evidence to confirm                                                              | GitHub-release updater pattern; not enough evidence for TeaTiers signer-pin/signed-manifest requirements        | Weak if GitHub-first                                                              | Interesting to watch; do not use for security-critical MVP updater ([Pavitra Golchha][19]) |
| **Google Play In-App Updates / Play Core**              | `com.google.android.play:app-update` family                              |                                 Google Play SDK terms | Current but Play-dependent                                            | **No**                                                                                      | No: depends on Google Play delivery path                                                                        | Not viable for no-GMS sideload users                                              | Forbidden by locked constraint ([Android Developers][20])                                  |

Recommendation: **build the updater yourself** and optionally use **Ackpine** only if raw `PackageInstaller` state management becomes annoying. Do not delegate the security model to a generic updater library.

---

# 9. Update UX and policy

## Optional update

Show a calm prompt:

```text
TeaTiers 0.1.1 is available

Bug fixes and updater improvements.
Size: 18.5 MB

[Update] [Later] [Skip this version]
```

Rules:

* Do not show more than once per version every few days unless the user manually checks.
* Do not ask for “Install unknown apps” permission until the user taps **Update**.
* Show APK size and network warning before download.
* Prefer Wi-Fi/unmetered; allow cellular override.
* Use foreground progress for the download.
* After verification, show “Verified TeaTiers update. Android will ask you to confirm installation.”

## Mandatory update

Use only for backend/API incompatibility, a critical security issue, or a data-loss bug.

For TeaTiers specifically:

```text
Update required for online catalog and OCR

Your local tea boards remain on this device.
To continue using online catalog search/OCR, install TeaTiers 0.1.1.
```

Because the app is local-first, avoid blocking local boards unless the installed app itself is unsafe.

## Unknown-source denial

If the user refuses the Android setting:

```text
Android requires you to allow TeaTiers to install TeaTiers updates.
You can continue using the current version, or download the APK manually.
```

Provide:

* “Open Android settings”
* “Try again”
* “Open GitHub Releases”
* “Copy expected SHA-256”

## GitHub fallback UX

If Yandex mirror fails and GitHub fallback also fails:

```text
Could not reach update mirrors.
This may happen on some networks. Try again later or download manually from the official release page.
```

Do not tell Russian users to use a VPN; the locked design explicitly avoids that.

---

# 10. Security checklist

## Android provides

* Same-package update checks.
* Same-signer / signing-lineage enforcement for installed package updates.
* User-facing package installer confirmation.
* Per-source install permission on API 26+.
* PackageInstaller session lifecycle and status callbacks. ([Android Developers][21])

## TeaTiers must provide

* Pinned release signing certificate hash in app code.
* Signed update manifest.
* APK SHA-256 verification.
* APK size check.
* Package name check.
* APK `versionCode` equals manifest `versionCode`.
* APK `versionCode > installedVersionCode`.
* No cleartext update URLs.
* No PII in update check.
* Mirror fallback with identical verification.
* Release-key backup and rotation plan.
* Manual recovery path.

## Release signing key rules

* Do not keep the release keystore on the 4 GB VM.
* Do not let the backend generate or sign manifests with a hot private key.
* Keep release signing and manifest signing separate.
* Record the release certificate SHA-256 in `docs/release-signing.md`.
* Test a fake malicious APK signed with a different key; TeaTiers must reject it before installer launch.
* Test a validly signed lower `versionCode`; TeaTiers must reject it before installer launch.

---

# 11. Phased build plan

## Phase 1 — internal tester release

Build this first:

1. Finalize release keystore and record the release cert SHA-256.
2. Add `/api/v1/app/android/upgrade`.
3. Serve a static signed manifest with ETag.
4. Upload APK to GitHub Releases and Yandex Object Storage.
5. Android app:

   * manual “Check for updates”
   * manifest fetch with ETag
   * manifest signature verification
   * mirror download
   * SHA-256 verification
   * package/version/signer verification
   * PackageInstaller session install
6. Add basic UX:

   * optional update dialog
   * unknown-source settings flow
   * download progress
   * failure states
7. Test:

   * API 26, 29, 31, 33, 34, 35/36
   * de-Googled emulator/device
   * Xiaomi/Huawei/Samsung if available
   * GitHub blocked/throttled simulation
   * corrupt APK
   * wrong signer
   * downgrade
   * manifest signature mismatch

## Phase 2 — public hardening before wider release

Add:

1. Daily WorkManager polling with jitter.
2. Mirror health/fallback ordering.
3. “Skip this version.”
4. `minSupportedVersionCode` backend gating.
5. Better release automation:

   * build APK
   * compute SHA-256
   * upload mirrors
   * generate manifest
   * sign manifest
   * smoke-test both mirrors
6. Optional Android 14+ preapproval flow for large downloads.
7. Optional Ackpine adoption if raw installer-session code becomes too stateful.
8. Consider a self-hosted F-Droid repository later, but not as the MVP in-app updater.

## Constraint conflicts

None of the recommended design requires GMS, VPN, accounts, user identifiers, a heavy update server, or a marketplace. Play In-App Updates would violate the no-GMS/no-Play constraint and should stay out.

---

# Final recommendation

TeaTiers should ship with a **first-party, signed, anonymous update manifest on `tea.macsia.fun`**, APKs mirrored primarily on **Yandex Object Storage**, GitHub Releases retained as the public canonical fallback, and a **custom Kotlin updater** using `PackageInstaller`.

The key implementation rule is simple:

```text
Never invoke Android’s installer until the manifest signature, APK SHA-256,
packageName, versionCode, and release signing certificate pin have all passed.
```

That gives you a small, buildable MVP updater that is compatible with no-GMS devices, resilient to GitHub reachability problems in Russia, cheap enough for the current infrastructure, and safe enough for a self-installing APK channel from day one.

[1]: https://meduza.io/en/news/2026/05/08/github-access-deteriorates-in-russia-as-internet-regulator-denies-blocking?utm_source=chatgpt.com "GitHub access deteriorates in Russia as internet regulator ..."
[2]: https://developer.android.com/reference/android/content/Intent?utm_source=chatgpt.com "Intent | API reference"
[3]: https://docs.github.com/en/rest/using-the-rest-api/rate-limits-for-the-rest-api?utm_source=chatgpt.com "Rate limits for the REST API"
[4]: https://yandex.cloud/en/docs/storage/operations/hosting/setup?utm_source=chatgpt.com "Setting up hosting in Yandex Object Storage"
[5]: https://developer.android.com/reference/android/content/pm/PackageManager "PackageManager  |  API reference  |  Android Developers"
[6]: https://developer.android.com/reference/android/content/pm/PackageInstaller.Session "PackageInstaller.Session  |  API reference  |  Android Developers"
[7]: https://developer.android.com/reference/kotlin/android/content/pm/PackageInstaller.SessionParams "PackageInstaller.SessionParams  |  API reference  |  Android Developers"
[8]: https://developer.android.com/about/versions/14/features "Features and APIs Overview  |  Android Developers"
[9]: https://developer.android.com/develop/connectivity/network-ops/connecting?utm_source=chatgpt.com "Connect to the network"
[10]: https://source.android.com/docs/security/features/apksigning/v3?utm_source=chatgpt.com "APK signature scheme v3"
[11]: https://developer.android.com/reference/android/content/pm/SigningInfo "SigningInfo  |  API reference  |  Android Developers"
[12]: https://docs.github.com/en/rest/releases/releases?utm_source=chatgpt.com "REST API endpoints for releases"
[13]: https://docs.github.com/rest/guides/best-practices-for-using-the-rest-api?utm_source=chatgpt.com "Best practices for using the REST API"
[14]: https://github.com/solrudev/Ackpine "GitHub - solrudev/Ackpine: Android package installer library · GitHub"
[15]: https://github.com/javiersantos/AppUpdater?utm_source=chatgpt.com "javiersantos/AppUpdater"
[16]: https://github.com/ImranR98/Obtainium "GitHub - ImranR98/Obtainium: Get Android app updates straight from the source. · GitHub"
[17]: https://github.com/f-droid/fdroidserver "GitHub - f-droid/fdroidserver: F-Droid server and build tools. · GitHub"
[18]: https://github.com/solrudev/SimpleInstaller "GitHub - solrudev/SimpleInstaller: Easy to use Android package installer wrapper leveraging Kotlin coroutines (API 16+). Newer alternative: https://github.com/solrudev/Ackpine · GitHub"
[19]: https://pavi2410.com/blog/introducing-kmp-app-updater/ "I Built a KMP Library for In-App Updates (So You Don't Have To)"
[20]: https://developer.android.com/guide/playcore/in-app-updates?utm_source=chatgpt.com "In-app updates | Other Play guides"
[21]: https://developer.android.com/distribute/marketing-tools/alternative-distribution?utm_source=chatgpt.com "Alternative distribution options | Branding & Marketing"
