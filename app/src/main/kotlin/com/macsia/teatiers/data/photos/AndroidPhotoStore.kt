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
import java.io.InputStream
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "AndroidPhotoStore"
private const val PHOTOS_DIR = "tea_photos"

/** Reject a picked image larger than this before copying it to disk (matches the server OCR cap). */
private const val MAX_PHOTO_BYTES = 8L * 1024 * 1024

/** Don't sweep a file written this recently — it may be an in-flight copy whose row isn't in yet. */
private const val RECENT_GRACE_MS = 60_000L

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
        // Reject an oversized image before reading a byte of it (AND-P1-5). AssetFileDescriptor.length
        // is UNKNOWN_LENGTH (-1) for some providers; treat unknown as "allow" and let the copy proceed.
        val declaredSize = runCatching {
            resolver.openAssetFileDescriptor(source, "r")?.use { it.length }
        }.getOrNull()
        if (declaredSize != null && declaredSize > MAX_PHOTO_BYTES) {
            Log.w(TAG, "Refusing oversized photo ($declaredSize bytes)")
            return@withContext null
        }
        val ext = extensionFor(source)
        val target = File(rootDir, "${UUID.randomUUID()}$ext")
        try {
            resolver.openInputStream(source)?.use { input ->
                FileOutputStream(target).use { output ->
                    input.copyTo(output)
                    output.fd.sync()
                }
            } ?: run {
                Log.w(TAG, "openInputStream returned null for source uri")
                return@withContext null
            }
            target.absolutePath
        } catch (e: SecurityException) {
            Log.w(TAG, "Permission denied reading source uri", e)
            target.delete()
            null
        } catch (e: IOException) {
            Log.w(TAG, "Failed to copy source uri", e)
            target.delete()
            null
        }
    }

    override suspend fun importInto(originalName: String, input: InputStream): String? =
        withContext(Dispatchers.IO) {
            val ext = originalName.substringAfterLast('.', "").let { if (it.isEmpty()) "jpg" else it }
            val target = File(rootDir, "${UUID.randomUUID()}.$ext")
            try {
                FileOutputStream(target).use { output ->
                    input.copyTo(output)
                    output.fd.sync()
                }
                target.absolutePath
            } catch (e: IOException) {
                Log.w(TAG, "Failed to import photo", e)
                target.delete()
                null
            }
        }

    override suspend fun delete(path: String): Boolean = withContext(Dispatchers.IO) {
        val file = File(path)
        if (!file.exists()) return@withContext true
        if (!isContainedInRoot(file)) {
            // Refuse to touch anything outside our own dir; an external URI here is a bug.
            Log.w(TAG, "Refusing to delete out-of-root path")
            return@withContext false
        }
        file.delete()
    }

    override suspend fun reconcile(keepPaths: Set<String>): Int = withContext(Dispatchers.IO) {
        // Only ever scoped to our own private dir, so it can never sweep anything but tea photos.
        val files = rootDir.listFiles() ?: return@withContext 0
        // Keep-check by BOTH the stored (absolute) path AND its canonical form: if filesDir ever
        // resolves through a symlink (e.g. /data/user/0 vs /data/data), the swept file's absolutePath
        // and the DB's stored uri can differ for the same physical file — a raw string compare would
        // then sweep a still-referenced photo. We only ever WIDEN the keep set, never narrow it.
        val keep = HashSet(keepPaths)
        keepPaths.forEach { p -> runCatching { File(p).canonicalPath }.getOrNull()?.let { keep += it } }
        val now = System.currentTimeMillis()
        var deleted = 0
        for (file in files) {
            val canonical = runCatching { file.canonicalPath }.getOrNull()
            val kept = file.absolutePath in keep || (canonical != null && canonical in keep)
            if (!file.isFile || kept) continue
            // Grace window: never sweep a file written in the last RECENT_GRACE_MS. It may be an
            // in-flight copyIn whose DB row hasn't been inserted yet (so it isn't in keepPaths) —
            // the app-open sweep would otherwise TOCTOU-delete a photo a concurrent add just wrote.
            // A genuine orphan younger than the window is simply caught by the next sweep.
            if (now - file.lastModified() < RECENT_GRACE_MS) continue
            if (file.delete()) deleted++ else Log.w(TAG, "Failed to delete orphan photo")
        }
        deleted
    }

    /**
     * Canonical-path containment so a `..`-traversal path can't escape the photos root. A plain
     * `path.startsWith(rootDir.path)` prefix check would accept e.g. `<root>/../../etc/x`; resolving
     * to canonical paths first collapses `..` segments. If canonicalisation throws (broken symlink,
     * I/O error) we treat the file as NOT contained and refuse the delete.
     */
    private fun isContainedInRoot(file: File): Boolean = try {
        file.canonicalFile.toPath().startsWith(rootDir.canonicalFile.toPath())
    } catch (e: IOException) {
        Log.w(TAG, "Could not canonicalise file; refusing delete", e)
        false
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
