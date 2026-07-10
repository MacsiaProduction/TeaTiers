package com.macsia.teatiers.data.photos

import android.content.Context
import android.net.Uri
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
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

/**
 * Reject a picked image larger than this before buffering it to downscale (OOM guard, AND-P1-5).
 * Generous: a real phone photo is far smaller, and anything up to this cap is downsampled — not
 * rejected — so an 8–25 MB full-res capture lands as a bounded stored photo instead of failing.
 */
private const val MAX_SOURCE_BYTES = 25L * 1024 * 1024

/** Free-space preflight floor when the source size is undeclared. */
private const val WRITE_ESTIMATE_BYTES = 8L * 1024 * 1024

/**
 * Stored-photo target: decoded, EXIF-oriented and re-encoded to JPEG with the longest edge capped
 * here (R4-PWR-3). 2048 px is ample for full-screen pinch-zoom while keeping a typical photo a few
 * hundred KB instead of several MB — bounding both app storage and the backup .zip.
 */
private const val STORED_MAX_DIM = 2048
private const val STORED_JPEG_QUALITY = 85

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

    override suspend fun copyIn(source: Uri): PhotoCopyResult = withContext(Dispatchers.IO) {
        val resolver = context.contentResolver
        // Reject a pathological source before we buffer it to downscale (AND-P1-5). AssetFileDescriptor.length
        // is UNKNOWN_LENGTH (-1) for some providers; treat unknown as "allow" and let the decode proceed.
        val declaredSize = runCatching {
            resolver.openAssetFileDescriptor(source, "r")?.use { it.length }
        }.getOrNull()
        if (declaredSize != null && declaredSize > MAX_SOURCE_BYTES) {
            Log.w(TAG, "Refusing oversized photo ($declaredSize bytes)")
            return@withContext PhotoCopyResult.TooLarge
        }
        // Preflight free-space check (UX-P1-1): a full disk would otherwise surface as a generic
        // IOException from the write below, which the user can't tell apart from "add photo did
        // nothing". The re-encoded JPEG is far smaller than the source, so checking against the
        // declared source size (or an 8 MB floor if undeclared) is conservative.
        // ponytail: a preflight check, not a filesystem-quota guarantee — a concurrent writer could
        // still race the free space between this check and the write; that residual failure falls
        // through to the generic IOException catch below, which is an acceptable rare edge.
        val required = declaredSize ?: WRITE_ESTIMATE_BYTES
        if (rootDir.usableSpace < required) {
            Log.w(TAG, "Insufficient storage for photo copy (need ~$required bytes)")
            return@withContext PhotoCopyResult.OutOfSpace
        }
        // Read + downscale under a broad guard: unlike the old streaming copy, decoding a bitmap can
        // throw OutOfMemoryError (an Error, not an Exception) on a pathological/undeclared-length
        // source, and readBytes() can too — either would otherwise escape withContext uncaught and
        // crash the app. Mirror the OCR path (AndroidImageReader) which runCatches the same work.
        // Downscale + re-encode to a bounded JPEG (R4-PWR-3); if the source can't be decoded as an
        // image (corrupt/non-image pick, or a decode OOM), fall back to storing the raw bytes so we
        // never silently drop what the user picked — the extension then keeps the source type.
        val prepared: Pair<ByteArray, String>? = runCatching {
            val raw = resolver.openInputStream(source)?.use { it.readBytes() } ?: return@runCatching null
            val jpeg = runCatching { decodeOrientDownscaleJpeg(raw, STORED_MAX_DIM, STORED_JPEG_QUALITY) }
                .getOrNull()
            if (jpeg != null) jpeg to ".jpg" else raw to extensionFor(source)
        }.getOrElse { e ->
            if (e is CancellationException) throw e
            Log.w(TAG, "Failed to read/decode source uri", e)
            null
        }
        if (prepared == null) return@withContext PhotoCopyResult.Failed
        val (bytes, ext) = prepared
        val target = File(rootDir, "${UUID.randomUUID()}$ext")
        try {
            FileOutputStream(target).use { output ->
                output.write(bytes)
                output.fd.sync()
            }
            PhotoCopyResult.Success(target.absolutePath)
        } catch (e: IOException) {
            Log.w(TAG, "Failed to write photo", e)
            target.delete()
            PhotoCopyResult.Failed
        }
    }

    override suspend fun usage(): PhotoStorageUsage = withContext(Dispatchers.IO) {
        val files = rootDir.listFiles()?.filter { it.isFile } ?: emptyList()
        PhotoStorageUsage(count = files.size, bytes = files.sumOf { it.length() })
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
