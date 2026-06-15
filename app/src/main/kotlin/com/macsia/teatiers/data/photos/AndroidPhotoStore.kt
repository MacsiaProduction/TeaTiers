package com.macsia.teatiers.data.photos

import android.content.Context
import android.net.Uri
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "AndroidPhotoStore"
private const val PHOTOS_DIR = "tea_photos"

/**
 * Disk-backed [PhotoStore]: copies the picked image into `<filesDir>/tea_photos/<uuid>.<ext>`.
 *
 * - The directory is app-private; nothing else on the device can read it. We never persist the
 *   picker's `content://` URI — by design, so backups and gallery-cleanup don't break the link.
 * - The extension is taken from the source URI's MIME type when we can read it; we fall back to
 *   `.jpg` rather than guessing mime sniffing. Coil decodes by content, not extension, so the
 *   suffix is purely cosmetic.
 * - All disk I/O hops to [Dispatchers.IO] so the caller can stay on a structured-concurrency
 *   coroutine without blocking the main dispatcher.
 */
@Singleton
class AndroidPhotoStore @Inject constructor(
    @ApplicationContext private val context: Context,
) : PhotoStore {

    private val rootDir: File by lazy {
        File(context.filesDir, PHOTOS_DIR).also { if (!it.exists()) it.mkdirs() }
    }

    override suspend fun copyIn(source: Uri): String? = withContext(Dispatchers.IO) {
        val resolver = context.contentResolver
        val ext = extensionFor(source)
        val target = File(rootDir, "${UUID.randomUUID()}$ext")
        try {
            resolver.openInputStream(source)?.use { input ->
                FileOutputStream(target).use { output ->
                    input.copyTo(output)
                    output.fd.sync()
                }
            } ?: run {
                Log.w(TAG, "openInputStream returned null for $source")
                return@withContext null
            }
            target.absolutePath
        } catch (e: SecurityException) {
            Log.w(TAG, "Permission denied reading $source", e)
            target.delete()
            null
        } catch (e: IOException) {
            Log.w(TAG, "Failed to copy $source", e)
            target.delete()
            null
        }
    }

    override suspend fun delete(path: String): Boolean = withContext(Dispatchers.IO) {
        val file = File(path)
        if (!file.exists()) return@withContext true
        if (!file.path.startsWith(rootDir.path)) {
            // Refuse to touch anything outside our own dir; an external URI here is a bug.
            Log.w(TAG, "Refusing to delete out-of-root path $path")
            return@withContext false
        }
        file.delete()
    }

    private fun extensionFor(uri: Uri): String {
        val mime = context.contentResolver.getType(uri)
        return when (mime) {
            "image/png" -> ".png"
            "image/webp" -> ".webp"
            "image/heic", "image/heif" -> ".heic"
            else -> ".jpg"
        }
    }
}
