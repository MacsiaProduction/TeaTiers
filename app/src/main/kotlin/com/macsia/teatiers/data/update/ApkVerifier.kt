package com.macsia.teatiers.data.update

import android.content.Context
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.pm.PackageInfoCompat
import com.macsia.teatiers.data.remote.dto.AppManifestDto
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/** Outcome of verifying a downloaded APK against the manifest, before it reaches the installer. */
sealed interface ApkVerification {
    data object Ok : ApkVerification

    /** Verification failed — DO NOT install. [reason] is a short, non-sensitive label for logs. */
    data class Rejected(val reason: String) : ApkVerification
}

/**
 * Verifies a downloaded APK is the genuine, newer TeaTiers build **before** it's handed to the
 * installer (decision #119). A self-updater is a code-execution channel, so this is the trust gate —
 * independent of where the APK came from (the host/TLS is not trusted for integrity):
 *
 *  1. **sha256-pin** — the file's SHA-256 must equal `manifest.apkSha256` (catches a corrupt/swapped
 *     download).
 *  2. **downgrade guard** — the APK's own `versionCode` must be strictly greater than the installed
 *     one (never sidestep into an older build).
 *  3. **signer-cert-pin** — the APK's CURRENT signing certificate SHA-256 must equal
 *     `manifest.signingCertSha256` — read via `apkContentsSigners` on API 28+ / `signatures` on 26–27,
 *     **never** `signingCertificateHistory` (which would also accept a rotated-away cert). Android
 *     additionally enforces same-signer on the actual update.
 *
 * The pinned `signingCertSha256` is the SHA-256 of the certificate's DER bytes — exactly what
 * `apksigner verify --print-certs` prints (so the operator copies it straight from the release run).
 */
@Singleton
class ApkVerifier @Inject constructor(
    @ApplicationContext private val context: Context,
) {

    fun verify(apk: File, manifest: AppManifestDto, installedVersionCode: Long): ApkVerification {
        if (!apk.exists() || apk.length() == 0L) return ApkVerification.Rejected("missing apk")
        // Extract the Android-coupled facts (file hash + archive signer/version), then hand the actual
        // trust decision to the pure [decideApkVerification] so its branches are unit-testable.
        val info = archiveInfo(apk.absolutePath)
        return decideApkVerification(
            actualSha256 = Sha256.ofFile(apk),
            apkVersionCode = info?.let { PackageInfoCompat.getLongVersionCode(it) },
            apkSignerCertSha256s = info?.let { signerCertSha256s(it) }.orEmpty(),
            manifest = manifest,
            installedVersionCode = installedVersionCode,
        )
    }

    private fun archiveInfo(path: String): PackageInfo? {
        @Suppress("DEPRECATION")
        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            PackageManager.GET_SIGNING_CERTIFICATES
        } else {
            PackageManager.GET_SIGNATURES
        }
        return context.packageManager.getPackageArchiveInfo(path, flags)
    }

    private fun signerCertSha256s(info: PackageInfo): List<String> {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val signingInfo = info.signingInfo ?: return emptyList()
            // apkContentsSigners = the CURRENT signer set (NOT signingCertificateHistory).
            return signingInfo.apkContentsSigners.map { Sha256.ofBytes(it.toByteArray()) }
        }
        @Suppress("DEPRECATION")
        return info.signatures?.map { Sha256.ofBytes(it.toByteArray()) }.orEmpty()
    }
}

/**
 * Pure verification decision, split from the Android extraction (PackageManager + file hashing) so the
 * trust-gate branches — sha256 pin, downgrade guard, signer-cert pin — are unit-testable without
 * Robolectric or a real signed APK (mirrors how [decideUpdate] is split from [AppUpdateChecker]).
 *
 * [actualSha256] is the SHA-256 of the downloaded file; [apkVersionCode] is null when the archive was
 * unreadable; [apkSignerCertSha256s] are the SHA-256s of the APK's CURRENT signer certs (never the
 * rotation history — see [ApkVerifier]). The order matches the original: sha → readable → downgrade →
 * signer, so the most fundamental mismatch is the reported reason.
 */
internal fun decideApkVerification(
    actualSha256: String,
    apkVersionCode: Long?,
    apkSignerCertSha256s: List<String>,
    manifest: AppManifestDto,
    installedVersionCode: Long,
): ApkVerification {
    if (!actualSha256.equals(manifest.apkSha256.trim(), ignoreCase = true)) {
        return ApkVerification.Rejected("sha256 mismatch")
    }
    if (apkVersionCode == null) return ApkVerification.Rejected("unreadable apk")
    if (apkVersionCode <= installedVersionCode) return ApkVerification.Rejected("downgrade")
    val pinned = manifest.signingCertSha256.trim()
    if (pinned.isEmpty()) return ApkVerification.Rejected("no pinned signer")
    if (apkSignerCertSha256s.none { it.equals(pinned, ignoreCase = true) }) {
        return ApkVerification.Rejected("signer mismatch")
    }
    return ApkVerification.Ok
}
