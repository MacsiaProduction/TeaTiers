package com.macsia.teatiers.data.update

import android.content.Context
import android.util.Log
import com.macsia.teatiers.data.remote.dto.AppManifestDto
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "ApkDownloader"
private const val UPDATE_APK = "update.apk"

/**
 * Downloads the release APK to an app-private cache file (decision #119), trying the manifest's
 * primary `apkUrl` first and then each `mirrorUrls` entry in order — so if GitHub's asset CDN is
 * throttled in Russia, a Yandex Object Storage mirror still works. Returns the file, or null if every
 * source failed; the caller verifies (sha256 + signer) before installing and deletes it after.
 *
 * The download is NOT trusted on its own — integrity comes from [ApkVerifier], not from the host.
 */
@Singleton
class ApkDownloader @Inject constructor(
    @ApplicationContext private val context: Context,
    private val client: OkHttpClient,
) {

    suspend fun download(manifest: AppManifestDto): File? = withContext(Dispatchers.IO) {
        val urls = (listOf(manifest.apkUrl) + manifest.mirrorUrls).filter { it.isNotBlank() }
        if (urls.isEmpty()) return@withContext null
        // The `updates/` subdir is the one FileProvider exposes (file_paths.xml), so the installer
        // can hand a content:// Uri to PackageInstaller.
        val dir = File(context.cacheDir, "updates").apply { mkdirs() }
        val target = File(dir, UPDATE_APK)
        for (url in urls) {
            if (tryDownload(url, target)) return@withContext target
            target.delete() // drop a partial before trying the next mirror
        }
        null
    }

    private fun tryDownload(url: String, target: File): Boolean {
        return try {
            client.newCall(Request.Builder().url(url).build()).execute().use { response ->
                if (!response.isSuccessful) {
                    Log.w(TAG, "download $url -> HTTP ${response.code}")
                    return false
                }
                val body = response.body ?: return false
                target.outputStream().use { out -> body.byteStream().copyTo(out) }
                true
            }
        } catch (e: IOException) {
            Log.w(TAG, "download $url failed: ${e.message}")
            false
        } catch (e: IllegalArgumentException) {
            Log.w(TAG, "download bad url $url: ${e.message}") // malformed manifest URL
            false
        }
    }
}
