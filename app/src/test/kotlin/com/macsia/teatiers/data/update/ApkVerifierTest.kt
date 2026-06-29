package com.macsia.teatiers.data.update

import com.macsia.teatiers.data.remote.dto.AppManifestDto
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * Unit tests for the self-updater's trust gate via the pure [decideApkVerification] seam (split from
 * the Android PackageManager/file extraction in [ApkVerifier]). A self-updater is a code-execution
 * channel, so these branches must be exercised: a regression flipping the downgrade `<=` to `<`, or
 * accepting a rotated-away cert, would otherwise ship green.
 */
class ApkVerifierTest {

    private fun manifest(sha: String = "ABCD", signer: String = "99AA") = AppManifestDto(
        latestVersionCode = 5,
        latestVersionName = "0.5.0",
        minSupportedVersionCode = 1,
        apkUrl = "https://example/a.apk",
        apkSha256 = sha,
        signingCertSha256 = signer,
        mandatory = false,
        minOsSdk = 26,
    )

    private fun decide(
        sha: String = "ABCD",
        versionCode: Long? = 6,
        signers: List<String> = listOf("99AA"),
        manifest: AppManifestDto = manifest(),
        installed: Long = 5,
    ) = decideApkVerification(sha, versionCode, signers, manifest, installed)

    @Test
    fun `accepts a genuine newer build with a matching sha and pinned signer`() {
        assertEquals(ApkVerification.Ok, decide())
    }

    @Test
    fun `rejects a sha256 mismatch`() {
        assertEquals(ApkVerification.Rejected("sha256 mismatch"), decide(sha = "DEAD"))
    }

    @Test
    fun `rejects an unreadable archive (null version code)`() {
        assertEquals(ApkVerification.Rejected("unreadable apk"), decide(versionCode = null))
    }

    @Test
    fun `the downgrade boundary is strictly greater than the installed version`() {
        assertEquals(ApkVerification.Rejected("downgrade"), decide(versionCode = 5, installed = 5))
        assertEquals(ApkVerification.Rejected("downgrade"), decide(versionCode = 4, installed = 5))
        assertEquals(ApkVerification.Ok, decide(versionCode = 6, installed = 5))
    }

    @Test
    fun `rejects when the manifest pins no signer`() {
        assertEquals(ApkVerification.Rejected("no pinned signer"), decide(manifest = manifest(signer = "   ")))
    }

    @Test
    fun `rejects a signer-cert mismatch (a rotated-away history cert must not pass)`() {
        assertEquals(ApkVerification.Rejected("signer mismatch"), decide(signers = listOf("ROTATED-AWAY")))
    }

    @Test
    fun `sha and signer comparisons are case-insensitive and trim the pinned values`() {
        val m = manifest(sha = " abcd ", signer = " 99aa ")
        assertEquals(ApkVerification.Ok, decide(sha = "ABCD", signers = listOf("99AA"), manifest = m))
    }
}
