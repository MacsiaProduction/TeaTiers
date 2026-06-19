# TeaTiers In-App Auto-Update: GMS-Free, Russia-First, GitHub-Released APK — Technical Design Report

## TL;DR
- **Build a small custom updater, not a library.** Use a first-party signed JSON manifest served by your existing Spring backend at `https://tea.macsia.fun/api/v1/` (always reachable in RU) as the source of truth, with the APK hosted on **Yandex Object Storage** (ru-central1, reachable inside Russia, effectively ~0 ₽/month at your volume) as primary and the GitHub Releases asset as a secondary mirror. Do **not** make GitHub the primary check — GitHub reachability in Russia is actively degrading (OONI measured failed-connection rates rising to 16% in early May 2026).
- **Security model:** Android enforces same-signer-on-update for you; YOU must enforce manifest integrity (offline Ed25519/minisign detached signature with an embedded public key), SHA-256 pinning of the APK, signer-certificate SHA-256 pinning verified on the downloaded file before install, and downgrade protection (monotonic versionCode). Use the `PackageInstaller` Session API, never the deprecated intent.
- **No single library is GMS-free, maintained, AND supports your security model out of the box.** `javiersantos/AppUpdater` is check-only and last released v2.7 on July 5, 2021 (effectively unmaintained). `Ackpine` (`ru.solrudev.ackpine`, Apache-2.0, latest **0.21.1**, last release Feb 3–4 2026, actively maintained) is an excellent GMS-free *installer* wrapper you may optionally adopt for the install step — but you still write the download+verify pipeline yourself.

## Key Findings

### RU reachability is the decisive design constraint
GitHub access from Russia is degrading, not hypothetical. As Meduza reported on May 8, 2026 (citing Verstka and OONI data): *"Disruptions began on May 5, when the rate of failed connections reached 10 percent. By May 6 and 7, that figure had climbed to 16 percent — well above the preceding weeks' average anomaly rate of no more than 4 percent."* The disruptions explicitly affected `raw.githubusercontent.com` (file downloads) and `release-asset.githubusercontent.com` (the release-asset CDN your update assets would live on). **Roskomnadzor denied blocking GitHub.** Anton Gorelkin, First Vice Chairman of the State Duma Committee on Information Policy, wrote on his Telegram channel that *"the rate of failed connections to the platform that many programmers in the country use to collaborate on code has exceeded 16%"* and urged Russian developers to urgently move their projects to other Git repositories. Further, per June 2026 reporting, Roskomnadzor plans to create a unified "state VPN" for Russian developers who have lost access to foreign repositories — which, sources warned, would make it *easier* to cut Russians off from international tooling. GitHub has a long history of disruption in Russia (a full HTTPS-level block in December 2014). **Conclusion: GitHub cannot be the primary, mandatory dependency for a Russia-first updater.** Your Yandex backend, a domestic provider, is reliably reachable without a VPN.

### What Android enforces vs. what you must enforce
Android's `PackageInstaller` enforces that an update must have the **identical package name, version-code semantics, and signing certificate** as the installed app — per the official docs, *"all existing and new packages must have identical package names, version codes, and signing certificates."* This is your strongest guarantee: a swapped/malicious APK signed with a different key is *rejected by the OS at install time*. But it does **not** protect the first install, and it does not stop an attacker from feeding you a *validly-signed-but-wrong* or *downgraded* APK if your manifest/transport is compromised. You must add manifest integrity, SHA-256 pinning, signer pinning (checked in-app before invoking the installer), and downgrade protection.

## Details

---

## Q1 — Android Install Mechanism (GMS-free), API 26–36

**Use the `PackageInstaller` Session API, not the deprecated `Intent.ACTION_INSTALL_PACKAGE` / `ACTION_VIEW`.** The session API is the current, supported path; the legacy intent path was deprecated at API 29. It is fully GMS-free — pure AOSP framework, present on de-Googled and Huawei devices.

**Permission + per-source grant:**
- Declare `<uses-permission android:name="android.permission.REQUEST_INSTALL_PACKAGES" />`. This takes effect when targeting API 26+. (It is Play-policy-restricted, but you are not shipping on Play, so the policy does not bind you — only relevant to flag if you ever submit to Play later.)
- Before installing, check `packageManager.canRequestPackageInstalls()`. If false, send the user to `Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES` with `Uri.parse("package:" + packageName)`. From Android 8 (API 26) the "install unknown apps" grant is **per-source** (per requesting app), replacing the old global "Unknown sources" toggle. Per Android docs, if `canRequestPackageInstalls()` returns false the install is blocked and the system shows a dialog to launch settings.

**Behavior across API levels:**
- **API 26 (O):** per-source unknown-apps grant introduced; `REQUEST_INSTALL_PACKAGES` required.
- **API 29 (Q):** legacy install intent deprecated; session API is canonical.
- **API 31 (S):** `setRequireUserAction()` added. For installers holding only `REQUEST_INSTALL_PACKAGES` (i.e., you, unprivileged), user action defaults to REQUIRED — a confirmation dialog always appears, and on commit you receive `STATUS_PENDING_USER_ACTION`. Fully silent installs require the installed app to target a minimum API *and* the installer to be privileged/update-owner — so **expect a per-update confirmation dialog**.
- **API 33 (Tiramisu):** `checkSignatures` recognizes proof-of-rotation and returns the newest signer.
- **API 34 (U):** **update ownership** introduced (`setRequestUpdateOwnership`). Per AOSP docs: *"In Android 14, the initial installer of an app can declare itself 'update owner' and own updates to the app. If another installer attempts to update the app, the user is given the opportunity to approve the new update."* As an unprivileged self-updater this mostly means a user-action dialog on each update is normal. API 34 also enforces a minimum target SDK for installs and restricts downgrades (downgrade works only with root or debuggable apps) — irrelevant for you since you target 36 and only ever go forward.
- **API 35/36 (V and successor):** same session model; no breaking change to the unprivileged flow. Continue to handle `STATUS_PENDING_USER_ACTION`.

**FileProvider / content-URI:** With the **session API you do NOT need a FileProvider** — you stream APK bytes directly into the session via `session.openWrite()`. FileProvider + `content://` URIs are only needed for the *legacy* `ACTION_VIEW`/`ACTION_INSTALL_PACKAGE` intent path (raw `file://` URIs are banned since API 24). This is another reason to prefer the session API: simpler, no provider plumbing.

**Can install proceed without a foreground UI?** No, not for you. As an unprivileged installer you receive `STATUS_PENDING_USER_ACTION`; you must `startActivity()` the returned confirmation intent and the system shows its install dialog. You can *download* in the background, but the install confirmation needs the user.

**Relaunch after self-update:** When you update your own running app, the process is killed and restarted. The robust pattern is: register a status receiver; on `STATUS_SUCCESS` launch your launcher activity, **and** persist a "just updated to vX" flag so that, if your process is killed before you observe success, you can show a confirmation on next cold start.

**OEM quirks common on Russian-market devices:**
- **Xiaomi / MIUI / HyperOS (most problematic):** MIUI uses its own `com.miui.packageinstaller`, per-app unknown-source grants, and a notorious `INSTALL_FAILED_USER_RESTRICTED` failure. "MIUI optimization" and the "Install via USB" developer setting can block sideloaded/programmatic installs; some builds require a Mi account or disabling MIUI optimization. Surface clear instructions and handle `STATUS_FAILURE_*` gracefully.
- **Huawei / EMUI / HarmonyOS:** no GMS at all (core target audience). Pure AOSP PackageInstaller works, but expect aggressive background-process killing — do the download in a foreground service / WorkManager.
- **Samsung / OneUI:** generally smooth with the session API; Knox/"Auto Blocker" (newer OneUI) can block sideloading when enabled — detect failure and instruct the user.

(Full Kotlin pipeline appears in Return 2.)

---

## Q2 — Update Authenticity & Security (the critical one)

### What ANDROID enforces for you
- **Same-signing-certificate on update.** The OS rejects any update whose signer doesn't match the installed app's signer. A maliciously swapped APK signed by an attacker key **cannot replace TeaTiers** — it fails install with a signature mismatch. This is your bedrock guarantee.
- **APK integrity via signature schemes v2/v3/v4.** Per AOSP: v2 (Android 7.0/API 24+) signs the whole APK so any modification is detected; v3 (Android 9/API 28+) adds key-rotation lineage; v4 (Android 11/API 30+) supports incremental installs alongside v2/v3. Modern build tooling signs v2+v3 by default. Sign TeaTiers v2+v3 (v1 is irrelevant at minSdk 26).
- **Caveat — first install is NOT protected by same-signer** (nothing installed to compare against). The very first APK a tester installs must be obtained over a trusted channel; the same-signer guarantee only applies to *updates*.
- **Key-rotation edge cases:** v3 lineage (proof-of-rotation) lets you rotate keys; `checkSignatures` honors lineage from API 33; `apksigner rotate --rotation-min-sdk-version` controls targeting. **Recommendation: do not rotate** for the MVP — single key, no lineage, simplest verification.

### What WE MUST enforce ourselves (in order of importance)
1. **Manifest integrity (prevent a MITM from pointing us at an attacker APK).** TLS alone is the minimum bar but is a single point of failure (mis-issued cert, compromised host). **Belt-and-suspenders: a detached signature over the manifest, verified in-app with an embedded Ed25519 public key.** Use **minisign/signify** (Ed25519, tiny, deterministic; its *trusted comment* can carry the versionCode to also block downgrade-by-replay) or a JWS with an embedded EdDSA key. Sign the manifest **offline** (the same laptop that holds the APK signing key); the private key never lives on the VM or in CI. The app ships the public key compiled in. Result: **even if the Yandex VM, the Object Storage bucket, GitHub, or the TLS PKI is fully compromised, an attacker cannot forge a manifest your app will accept.**
2. **SHA-256 pin of the APK.** The signed manifest carries `apkSha256`. After download, compute SHA-256 of the file and reject on mismatch *before* handing anything to the installer. This binds the verified manifest to the exact bytes, so it doesn't matter which mirror served them.
3. **Signer-certificate pin, checked in-app BEFORE invoking the installer.** Call `PackageManager.getPackageArchiveInfo(path, flags)` on the *downloaded file*. On API 28+ pass `GET_SIGNING_CERTIFICATES` and read `packageInfo.signingInfo.apkContentsSigners`; SHA-256 the signing cert bytes and compare to your pinned release-cert SHA-256. **API caveat:** on some OEM/older builds `getPackageArchiveInfo` does not populate `signatures`/`signingInfo` unless you also set `applicationInfo.sourceDir`/`publicSourceDir` to the file path — set them defensively. On API 26–27 fall back to `GET_SIGNATURES` + `packageInfo.signatures`. This catches a bad APK *before* the OS dialog (clean error, defense-in-depth), even though the OS would also reject on same-signer.
4. **Downgrade protection.** Compare manifest `latestVersionCode` and the downloaded archive's `versionCode` against the installed `versionCode`; **reject anything ≤ installed.** (Android 14 also blocks downgrades at the OS level, but enforce it yourself for all API levels.)
5. **TLS for all transport.** All endpoints HTTPS; system trust store. TLS is the first integrity gate, but the manifest signature is what you actually trust.

### Minimum bar vs. belt-and-suspenders
- **Minimum bar (acceptable for internal testers):** HTTPS manifest + `apkSha256` pin + signer-cert pin checked in-app + downgrade check + Android same-signer. No manifest signature yet.
- **Belt-and-suspenders (required before public):** all of the above PLUS an **offline Ed25519/minisign detached signature** over the manifest verified with an embedded public key, so manifest authenticity does not depend on any server or the TLS PKI.

---

## Return 2 — Minimal Correct Kotlin Pipeline (download → verify → install → relaunch)

**AndroidManifest.xml:**
```xml
<uses-permission android:name="android.permission.INTERNET" />
<uses-permission android:name="android.permission.REQUEST_INSTALL_PACKAGES" />
<!-- No FileProvider needed: the PackageInstaller Session API streams bytes directly. -->
<!-- For a foreground download service (recommended on Huawei/EMUI background-kill): -->
<uses-permission android:name="android.permission.FOREGROUND_SERVICE" />
<uses-permission android:name="android.permission.FOREGROUND_SERVICE_DATA_SYNC" /> <!-- API 34+ -->
```

```kotlin
import android.app.PendingIntent
import android.content.*
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import android.os.Build
import java.io.File
import java.security.MessageDigest

object UpdateInstaller {

    // SHA-256 of YOUR release signing certificate (lowercase hex, no colons).
    // Get it: apksigner verify --print-certs app.apk | grep SHA-256
    private const val PINNED_SIGNER_SHA256 = "your_release_cert_sha256_here"
    private const val INSTALL_ACTION = "com.macsia.teatiers.SESSION_INSTALL_RESULT"

    /** Full gate: call AFTER you've already verified the signed manifest (Ed25519). */
    fun verifyAndInstall(ctx: Context, apk: File, expectedSha256: String, expectedVersionCode: Long) {
        // (1) SHA-256 pin of the downloaded bytes vs. the (signed) manifest.
        require(sha256(apk).equals(expectedSha256, ignoreCase = true)) { "APK hash mismatch" }

        // (2) Signer-cert pin + versionCode read, in-app, BEFORE the installer.
        val flags = if (Build.VERSION.SDK_INT >= 28)
            PackageManager.GET_SIGNING_CERTIFICATES else PackageManager.GET_SIGNATURES
        val pm = ctx.packageManager
        val info = pm.getPackageArchiveInfo(apk.absolutePath, flags)
            ?: error("Cannot parse APK")
        // Defensive: some OEMs need these set for signer extraction to work.
        info.applicationInfo?.apply { sourceDir = apk.absolutePath; publicSourceDir = apk.absolutePath }

        val signers = if (Build.VERSION.SDK_INT >= 28)
            info.signingInfo?.apkContentsSigners else @Suppress("DEPRECATION") info.signatures
        require(!signers.isNullOrEmpty()) { "No signers in APK" }
        val ok = signers.any { sha256Bytes(it.toByteArray()).equals(PINNED_SIGNER_SHA256, true) }
        require(ok) { "Signer certificate not pinned — refusing to install" }

        // (3) Downgrade protection (you enforce it on every API level).
        val archiveVersion =
            if (Build.VERSION.SDK_INT >= 28) info.longVersionCode
            else @Suppress("DEPRECATION") info.versionCode.toLong()
        val installed = pm.getPackageInfo(ctx.packageName, 0).let {
            if (Build.VERSION.SDK_INT >= 28) it.longVersionCode
            else @Suppress("DEPRECATION") it.versionCode.toLong()
        }
        require(archiveVersion == expectedVersionCode && archiveVersion > installed) {
            "Downgrade/version mismatch refused (have $installed, apk $archiveVersion)"
        }

        // (4) PackageInstaller Session install. Android then enforces SAME-SIGNER on commit.
        val installer = pm.packageInstaller
        val params = PackageInstaller.SessionParams(
            PackageInstaller.SessionParams.MODE_FULL_INSTALL
        ).apply { setAppPackageName(ctx.packageName) }
        val sessionId = installer.createSession(params)
        installer.openSession(sessionId).use { session ->
            session.openWrite("base.apk", 0, apk.length()).use { out ->
                apk.inputStream().use { it.copyTo(out) }
                session.fsync(out)
            }
            val intent = Intent(INSTALL_ACTION).setPackage(ctx.packageName)
            val flagsPi = PendingIntent.FLAG_UPDATE_CURRENT or
                (if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0)
            val pi = PendingIntent.getBroadcast(ctx, sessionId, intent, flagsPi)
            session.commit(pi.intentSender)   // → STATUS_PENDING_USER_ACTION for unprivileged installers
        }
    }

    // Receiver: handle the confirmation dialog and the final result.
    class ResultReceiver : BroadcastReceiver() {
        override fun onReceive(ctx: Context, intent: Intent) {
            when (intent.getIntExtra(PackageInstaller.EXTRA_STATUS, -1)) {
                PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                    val confirm = intent.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)!!
                    confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    ctx.startActivity(confirm)   // system install confirmation dialog
                }
                PackageInstaller.STATUS_SUCCESS -> {
                    // Persist "just updated" flag; process may be killed before relaunch.
                    ctx.getSharedPreferences("upd", Context.MODE_PRIVATE)
                        .edit().putBoolean("justUpdated", true).apply()
                    ctx.packageManager.getLaunchIntentForPackage(ctx.packageName)?.let {
                        it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK); ctx.startActivity(it)
                    }
                }
                else -> { /* STATUS_FAILURE_* → show localized error + GitHub fallback */ }
            }
        }
    }

    private fun sha256(f: File) = MessageDigest.getInstance("SHA-256").let { md ->
        f.inputStream().use { ins ->
            val b = ByteArray(8192); var n = ins.read(b)
            while (n >= 0) { md.update(b, 0, n); n = ins.read(b) }
        }
        md.digest().joinToString("") { "%02x".format(it) }
    }
    private fun sha256Bytes(bytes: ByteArray) =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
}
```

**Manifest-signature verification (do this first, before trusting `apkSha256`)** — verify the detached Ed25519 signature over the exact manifest bytes with your embedded public key (e.g., via Tink/`java.security.Signature` with Ed25519 on API 26+ providers, or a minisign verifier). Only parse JSON after the signature checks out.

Register the receiver in the manifest with `android:exported="false"` and `<action android:name="com.macsia.teatiers.SESSION_INSTALL_RESULT"/>`.

---

## Q3 / Return 1 — Where the version check + APK live, and RU reachability

| Option | RU reachability (no VPN) | Rate-limit / cost / footprint | Verdict |
|---|---|---|---|
| **(a) GitHub Releases API + asset CDN** | **Degrading/unreliable** — OONI: failed-connection rate rose 4%→10%→16% over May 5–7 2026, hitting `release-asset.githubusercontent.com` and `raw.githubusercontent.com`; blocked entirely in 2014. | Anonymous API = **60 req/hr per IP** (GitHub tightened unauthenticated limits May 8 2025); CGNAT/shared IPs exhaust fast. ETag/`If-None-Match`→304 doesn't count against the limit. | **Mirror only, never primary.** |
| **(b) First-party manifest on `tea.macsia.fun/api/v1/`** | **Reliable** — domestic VM, already used by the app. | You control caching; no third-party limit. Tiny JSON + ETag = negligible load. | **PRIMARY for the version check.** |
| **(c) APK on Yandex Object Storage** | **Reliable** — `storage.yandexcloud.net` ru-central1 is a Russian provider, data resides in Russia, reachable without VPN. | Free tiers: **first 1 GB storage free**, **first 100 GB egress/month free** (≈2,000–3,300 downloads of a 30–50 MB APK at zero egress cost), **first 100,000 GET/month free** → ≈**0 ₽/month** at your volume. Public bucket + custom domain + **free Let's Encrypt cert via Certificate Manager** supported. Needs a Yandex Cloud billing account with a Russian/RK card. | **PRIMARY for the APK download.** |
| **(d) Self-hosted F-Droid repo (fdroidserver)** | Reliable if hosted on Yandex/VM; but the *client* is a separate app. | Heavier: generates a signed `index-v1.jar`/`index-v2`, needs Debian tooling + repo signing key; `AllowedAPKSigningKeys` enforces signer at index-build time. Usually implies users run Neo Store/Droid-ify rather than your in-app updater. | **Overkill for MVP; reconsider post-MVP.** |

**DECISION:**
- **Version check (manifest):** PRIMARY = `GET https://tea.macsia.fun/api/v1/app/latest` (signed JSON, ETag-cached). No GitHub dependency for the check.
- **APK download:** PRIMARY = Yandex Object Storage public URL (e.g., `https://dl.macsia.fun/teatiers/teatiers-<versionCode>.apk` via a custom-domain bucket with free TLS). FALLBACK = the GitHub Releases asset, listed in the manifest's ordered `mirrors` array and tried only if the Yandex URL fails. **Because the signed manifest pins `apkSha256`, the host serving the bytes is irrelevant to integrity.**

**VM footprint:** the manifest endpoint is a tiny ETag-cached JSON response — negligible against the ~3.4 GB already committed. The APK does NOT live on the 4 GB VM (it's on Object Storage), so no disk/bandwidth pressure. This respects the "static file + tiny JSON" constraint.

**Yandex Object Storage pricing note (verify on live calculator):** official docs (updated June 2026) confirm the free tiers above and that *"Certificate Manager usage is free of charge."* Standard storage ≈ 2 ₽/GB-month incl. VAT; egress above 100 GB ≈ 1.3–1.5 ₽/GB incl. VAT (≈$0.0138/GB net of VAT for the 100 GB–1 TB band). An 8% price increase on Object Storage took effect May 1, 2026. At your volume, everything sits inside the free tiers (~0 ₽/month). A Yandex Cloud billing account requires a Russian/RK bank card (Visa/MasterCard/МИР) even for free usage.

---

## Q4 / Return 3 — Version-check / update manifest design

**Endpoint:** `GET /api/v1/app/latest` (anonymous, no PII, no device ID, no logging of identifiers). Stateless and cacheable. This endpoint family doubles as your API-version gating channel.

**JSON schema (example):**
```json
{
  "schemaVersion": 1,
  "latestVersionCode": 7,
  "latestVersionName": "0.4.0",
  "minSupportedVersionCode": 3,
  "apkSha256": "9f86d081884c7d659a2feaa0c55ad015a3bf4f1b2b0b822cd15d6c15b0f00a08",
  "apkSizeBytes": 41943040,
  "apkUrl": "https://dl.macsia.fun/teatiers/teatiers-7.apk",
  "mirrors": [
    "https://github.com/macsia/teatiers/releases/download/v0.4.0/teatiers-7.apk"
  ],
  "releaseNotes": "Bug fixes and a new dark theme.",
  "releaseNotesRu": "Исправления ошибок и новая тёмная тема.",
  "mandatory": false,
  "minOsSdk": 26,
  "minSupportedApiClient": 1,
  "publishedAt": "2026-06-15T10:00:00Z"
}
```
- **Detached signature** served as a sibling: `GET /api/v1/app/latest.minisig` (or a `signature` field of base64 Ed25519 over the canonical JSON bytes). The app fetches both, verifies the signature over the exact bytes with the embedded public key, then parses.
- **"Update available"** = `latestVersionCode > installedVersionCode`.
- **"Forced update"** = `installedVersionCode < minSupportedVersionCode` (non-dismissable update screen) OR `mandatory == true` for a critical fix.
- **Polling cadence:** check on app foreground, throttled to once per 6–24h (store last-check timestamp). Send `If-None-Match` with the stored ETag → `304 Not Modified` when unchanged. This keeps load (and any GitHub-fallback rate-limit pressure) near zero.

**Spring Boot controller shape (contract):**
```kotlin
@RestController
@RequestMapping("/api/v1/app")
class AppVersionController(private val manifestProvider: ManifestProvider) {

    // GET /api/v1/app/latest  →  200 (JSON) | 304 (ETag match) | 5xx (client keeps last-known)
    @GetMapping("/latest", produces = [MediaType.APPLICATION_JSON_VALUE])
    fun latest(
        @RequestHeader(HttpHeaders.IF_NONE_MATCH, required = false) ifNoneMatch: String?
    ): ResponseEntity<ByteArray> {
        val m = manifestProvider.current()                 // { bytes, etag }
        if (ifNoneMatch != null && ifNoneMatch.trim('"') == m.etag)
            return ResponseEntity.status(HttpStatus.NOT_MODIFIED).eTag(m.etag).build()
        return ResponseEntity.ok()
            .eTag(m.etag)
            .cacheControl(CacheControl.maxAge(Duration.ofHours(6)).cachePublic())
            .contentType(MediaType.APPLICATION_JSON)
            .body(m.bytes)                                  // exact bytes the .minisig covers
    }

    // GET /api/v1/app/latest.minisig  (detached signature)
    @GetMapping("/latest.minisig", produces = [MediaType.TEXT_PLAIN_VALUE])
    fun signature(): ResponseEntity<ByteArray> =
        ResponseEntity.ok()
            .cacheControl(CacheControl.maxAge(Duration.ofHours(6)).cachePublic())
            .body(manifestProvider.currentSignature())
}
```
- **Headers:** strong `ETag`; `Cache-Control: public, max-age=21600`. No auth, no cookies, no identifiers logged.
- The bytes returned must be byte-identical to what was signed offline (don't re-serialize/reorder keys server-side) — serve a stored blob.

---

## Q5 / Return 4 — Open-source libraries vs build-our-own

| Name | Coordinates + version | License (SPDX) | Last release | GMS-free? | Supports sha256 + signer pin? | RU-viable? | Verdict |
|---|---|---|---|---|---|---|---|
| **javiersantos/AppUpdater** | `com.github.javiersantos:AppUpdater:2.7` (JitPack) | Apache-2.0 | **v2.7, Jul 5 2021** (unmaintained; open issues unanswered; 1.9k★) | Yes (check-only; uses Jsoup to scrape) | **No** — only *detects* updates / shows a dialog; no download/verify/install, no sha256/signer | GitHub mode bad for RU; has XML/JSON modes you could point at your backend | **Reject** — stale, check-only, no security model. |
| **Ackpine** | `ru.solrudev.ackpine:ackpine-core` + `ackpine-ktx` **0.21.1** (Maven Central) | Apache-2.0 | **0.21.1, Feb 3–4 2026** (actively maintained, author Ilya Fomichev / "solrudev") | **Yes** — pure AOSP PackageInstaller wrapper; Shizuku is an *optional separate artifact* you simply don't add | Handles the **install** robustly (sessions, process-death, progress, deferred notification); you still do download + sha256 + signer pin yourself | **Yes** (Russian author, no GMS, Maven Central) | **Optional adopt for the install step.** |
| **solrudev/SimpleInstaller** | (superseded) | Apache-2.0 | superseded by Ackpine | Yes | install-only | Yes | **Skip** — author says use Ackpine. |
| **Obtainium (app, not a library)** | `dev.imranr.obtainium` | GPL-3.0 | active | Yes (default install = system PackageInstaller; Shizuku/root optional) | end-user app, not embeddable | Yes | **Precedent, not a dependency** — confirms PackageInstaller is the right GMS-free pattern. |
| **fdroidserver + F-Droid client** | `fdroidserver` (server-side, Python); client = Neo Store/Droid-ify | AGPL/GPL | active | Yes | Signed index + `AllowedAPKSigningKeys` give strong integrity | Yes if self-hosted on Yandex | **Defer** — strong but heavy; implies a separate client app. |
| **Google Play In-App Updates** | `com.google.android.play:app-update` | proprietary | active | **NO — GMS-only (pulls in Play Core / GMS)** | n/a | **No** | **FORBIDDEN by your constraints. Flagged.** |
| **Hand-rolled OkHttp/DownloadManager + PackageInstaller** | your code | your choice | n/a | Yes | **Yes — you implement exactly your model** | Yes | **RECOMMENDED primary approach.** |

**Recommendation — custom updater (optionally Ackpine for the install step):** A self-updater is a code-execution channel, so the *verification* logic must be small, auditable, and fully yours. Write download + SHA-256 + signer-pin + downgrade + manifest-signature verification yourself (~150 lines). For the actual install, either call `PackageInstaller` directly (zero dependencies) or delegate to **Ackpine 0.21.1** if you want its tested session/process-death handling and progress notifications. Do NOT adopt AppUpdater (stale, no security). Do NOT adopt anything that pulls GMS.

---

## Return 5 — Security checklist + UX/policy rules

**Android enforces (free):** same-signer on update; APK v2/v3 integrity; (API 34) OS-level downgrade block & min-target-SDK.
**You enforce (must build):** offline Ed25519/minisign manifest signature (embedded pubkey); APK SHA-256 pin from the signed manifest; signer-cert SHA-256 pin checked in-app *before* install; monotonic versionCode downgrade guard; HTTPS everywhere; first-install obtained over a trusted channel.

**Q6 — UX & policy (Russia-first, respectful):**
- **Urgency tiers:** (1) *Optional* — dismissable card/snackbar "Доступно обновление (vX)", remembers dismissal, re-shows at most every few days. (2) *Mandatory* (`mandatory==true` or below `minSupportedVersionCode`) — blocking screen explaining why ("эта версия больше не поддерживается сервером").
- **Respect the network:** default to downloading only on unmetered Wi-Fi; on metered/roaming ask first ("Скачать обновление (~40 МБ) по мобильной сети?"). Use WorkManager / foreground service with a progress notification (important on Huawei/EMUI).
- **First-run grant flow:** if `canRequestPackageInstalls()` is false, show a short explainer then deep-link to `ACTION_MANAGE_UNKNOWN_APP_SOURCES`; on return, re-check and continue.
- **If the user denies `REQUEST_INSTALL_PACKAGES`:** don't loop. Offer a calm fallback — a "Скачать с GitHub" / direct mirror link — and re-offer the grant next time.
- **Russian-market expectations:** sideloading is normal (RuStore/APK culture), but trust signals matter — show version, size, a "проверено" (verified) badge after signature verification, and full Russian localization. Never assume Play / never show "update via Play Store" (Huawei/de-Googled devices are common). Always offer the manual mirror link as a last resort.

---

## Q7 / Return 6 — Recommended architecture + phased plan

**Architecture (end state):**
1. Build & sign one universal APK with your release key (v2+v3). Compute its SHA-256.
2. Upload to the Yandex Object Storage public bucket (custom domain `dl.macsia.fun`, free Let's Encrypt TLS). Also attach to the GitHub Release as a mirror.
3. Generate the manifest JSON (versionCode/name, `minSupportedVersionCode`, `apkSha256`, `apkUrl`, `mirrors`, RU/EN notes). Sign it **offline** with your Ed25519/minisign secret key → `latest.minisig`. Upload both; Spring serves `/api/v1/app/latest` + `/api/v1/app/latest.minisig`, ETag-cached.
4. Client (throttled, on foreground): `GET /api/v1/app/latest` (+ `.minisig`) → verify Ed25519 with embedded pubkey → parse → if `latestVersionCode > installed` prompt (or force if below `minSupportedVersionCode`).
5. On accept: download from `apkUrl` (try `mirrors` on failure) → SHA-256 == `apkSha256` → `getPackageArchiveInfo` signer-cert SHA-256 == pinned → `versionCode > installed` → `PackageInstaller` session install → handle `STATUS_PENDING_USER_ACTION` → on success, mark "updated" and relaunch.

**Phased build plan:**
- **Phase 0 — internal testers (build now):** Spring `/api/v1/app/latest` (HTTPS, ETag) + APK on Yandex Object Storage (+ GitHub mirror). Client: manifest fetch → versionCode compare → download → **SHA-256 pin + signer-cert pin + downgrade guard** → PackageInstaller session install. **No manifest signature yet** is the acceptable minimum bar for trusted testers (same-signer + sha256-from-TLS-manifest + signer-pin already block realistic attacks). Localize to Russian.
- **Phase 1 — harden before public:** add the **offline Ed25519/minisign manifest signature** verified with an embedded public key (removes trust in server/TLS for authenticity); add the `minSupportedVersionCode` forced-update path; add metered-network deferral + foreground-service download with progress; polish OEM failure messaging (MIUI especially); add the manual GitHub fallback button.
- **Phase 2 — optional:** consider a self-hosted F-Droid repo on Yandex for a standards-based channel and/or adopt Ackpine for the install step; reconsider RuStore (Russian store, reachable) only as a deferred marketplace option.

**Locked-constraint conflicts:** None of the recommendations require relaxing GMS-free, no-VPN, or no-accounts/PII. The only "cost" is a Yandex Cloud billing account (Russian/RK card) to host the bucket — which you effectively already have via the Yandex VM. **Fully silent (no-dialog) updates** would require Shizuku or system privilege — not worth relaxing for an internal/public tester app; **accept the per-update confirmation dialog.**

## Recommendations
1. **Now:** ship Phase 0 (first-party manifest + Yandex bucket APK + custom updater with sha256/signer/downgrade checks via PackageInstaller). Threshold to revisit cost: only if Object Storage egress exceeds 100 GB/month do you start paying (~1.3–1.5 ₽/GB) — still trivial until tens of thousands of users.
2. **Before public launch:** add the offline-signed manifest (Ed25519/minisign) and the `minSupportedVersionCode` forced-update gate. This is the threshold that turns "good enough for testers" into "safe for the public."
3. **Keep GitHub strictly as a mirror.** Re-evaluate making it more central only if OONI data shows RU reachability stabilizing below ~4% anomaly for a sustained period.
4. **Do not rotate signing keys** for the MVP; single key, no lineage, simplest verification.

## Caveats
- **Exact Yandex ruble pricing** could not be read verbatim from the live SKU table (bot-gated); figures (~2 ₽/GB-mo storage, ~1.3–1.5 ₽/GB egress) come from official free-tier rules + recent third-party quotes, and an 8% Object Storage increase took effect May 1, 2026. At your volume everything sits inside free tiers (~0 ₽/month). Verify on the live calculator.
- **GitHub RU reachability** is a moving target; the May 2026 OONI degradation (and the proposed "state VPN") may worsen or ease. The design is robust either way because GitHub is only a mirror and integrity is guaranteed by the signed manifest + sha256 regardless of host.
- **`getPackageArchiveInfo` signer extraction** has had OEM/version inconsistencies; set `applicationInfo.sourceDir`/`publicSourceDir` defensively and test on real MIUI/EMUI/OneUI hardware.
- **Fully silent updates** are not achievable without Shizuku/root/system privilege; a per-update confirmation dialog is expected and accepted.
- **Ed25519 in-app verification** on API 26–27 may need a bundled provider (e.g., Tink or BouncyCastle) since platform JCA Ed25519 support is not guaranteed pre-API 28; bundle a verifier to keep behavior uniform from minSdk 26.

### Key reference links
1. PackageInstaller.Session — Android Developers: `https://developer.android.com/reference/android/content/pm/PackageInstaller.Session`
2. REQUEST_INSTALL_PACKAGES / canRequestPackageInstalls behavior — Android Developers Blog "Making it safer to get apps on Android O": `https://android-developers.googleblog.com/2017/08/making-it-safer-to-get-apps-on-android-o.html`
3. Update ownership (Android 14) — AOSP: `https://source.android.com/docs/setup/create/app-ownership`
4. APK Signature Scheme v3 (key rotation / same-signer integrity) — AOSP: `https://source.android.com/docs/security/features/apksigning/v3`
5. GitHub reachability degradation in Russia (May 2026, OONI) — Meduza: `https://meduza.io/en/news/2026/05/08/github-access-deteriorates-in-russia-as-internet-regulator-denies-blocking`
6. Ackpine (GMS-free PackageInstaller wrapper, Apache-2.0) — repo + Maven: `https://github.com/solrudev/Ackpine` and `https://mvnrepository.com/artifact/ru.solrudev.ackpine`
7. F-Droid self-hosted repo (`AllowedAPKSigningKeys`, signed index) — F-Droid docs: `https://f-droid.org/en/docs/Setup_an_F-Droid_App_Repo/` and `https://f-droid.org/en/docs/All_our_APIs/`
8. minisign (Ed25519 detached signatures for the manifest) — `https://jedisct1.github.io/minisign/`; Yandex Object Storage hosting + free TLS — `https://yandex.cloud/en/docs/storage/operations/hosting/own-domain`