package com.macsia.teatiers.data.backup

import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * Pure java.util.zip read/write for the backup bundle (decisions.md #26). No Android, no DB — kept
 * separate from [BackupManager] so the zip layout is unit-testable on the JVM. The bundle is a flat
 * zip: [BACKUP_JSON_ENTRY] at the root plus photo files under [BACKUP_PHOTO_DIR].
 *
 * Photos are STREAMED one at a time (review P1-5): export pulls each photo from a fresh [InputStream]
 * and import writes each entry straight to a staging file — the whole photo corpus is never held in
 * memory at once, so a formally-bounded 256 MB archive can't OOM the app.
 */
object BackupArchive {

    /**
     * Bounds enforced while reading an untrusted (user-picked) archive so a huge or zip-bomb backup
     * cannot OOM the app (review 2026-06-18, decision #96). [maxTotalBytes] caps the cumulative
     * decompressed size (the real zip-bomb guard); per-entry caps bound any single entry, and the
     * count cap bounds entry churn. Defaults are the production limits; tests pass tiny values.
     */
    data class Limits(
        val maxJsonBytes: Int = 16 * 1024 * 1024,
        val maxPhotoBytes: Int = 20 * 1024 * 1024,
        val maxPhotoCount: Int = 1_000,
        val maxTotalBytes: Long = 256L * 1024 * 1024,
    )

    /** Thrown by [extractTo] when an archive breaches a [Limits] bound; the caller maps it to "too large". */
    class TooLargeException(message: String) : Exception(message)

    /** One photo to stream into an archive: its bare entry [name] and a fresh [open] stream of its bytes. */
    class PhotoSource(val name: String, val open: () -> InputStream)

    /** A streamed-out archive: the JSON text plus the photo files extracted into the staging dir, keyed by name. */
    data class Extracted(val json: String, val photoFiles: Map<String, File>)

    /**
     * Writes [json] and [photos] into [out] as a zip, STREAMING each photo (never buffering the whole
     * corpus). Does not close [out]. A photo whose [PhotoSource.open] throws aborts the write.
     */
    fun write(out: OutputStream, json: String, photos: List<PhotoSource>) {
        ZipOutputStream(out).use { zip ->
            zip.putNextEntry(ZipEntry(BACKUP_JSON_ENTRY))
            zip.write(json.toByteArray(Charsets.UTF_8))
            zip.closeEntry()
            photos.forEach { photo ->
                zip.putNextEntry(ZipEntry("$BACKUP_PHOTO_DIR${photo.name}"))
                photo.open().use { it.copyTo(zip) }
                zip.closeEntry()
            }
        }
    }

    /**
     * Streams a backup zip from [input] into [stagingDir], enforcing [limits] so an oversized/malicious
     * archive is rejected instead of OOMing. The small JSON is returned in memory; every `photos/<name>`
     * entry is written to `stagingDir/<name>` (one at a time) and returned as a name→File map — the
     * caller validates completeness, applies, then deletes [stagingDir]. A zip with no
     * [BACKUP_JSON_ENTRY] yields an empty json string so the caller can reject it as "not a backup".
     * Ignores directory entries, nested paths, and traversal names. Throws [TooLargeException] on a bound.
     */
    fun extractTo(input: InputStream, stagingDir: File, limits: Limits = Limits()): Extracted {
        stagingDir.mkdirs()
        var json = ""
        val photoFiles = LinkedHashMap<String, File>()
        var totalBytes = 0L
        var photoCount = 0
        ZipInputStream(input).use { zip ->
            var entry: ZipEntry? = zip.nextEntry
            while (entry != null) {
                val name = entry.name
                when {
                    entry.isDirectory -> Unit
                    name == BACKUP_JSON_ENTRY -> {
                        val bytes = zip.readEntryBounded(limits.maxJsonBytes, "backup.json")
                        totalBytes = addTotal(totalBytes, bytes.size.toLong(), limits.maxTotalBytes)
                        json = bytes.toString(Charsets.UTF_8)
                    }
                    name.startsWith(BACKUP_PHOTO_DIR) -> {
                        val bare = name.removePrefix(BACKUP_PHOTO_DIR)
                        // Skip empty/nested/traversal names defensively (PhotoStore also renames to a UUID).
                        if (bare.isNotEmpty() && !bare.contains('/') && !bare.contains("..")) {
                            if (++photoCount > limits.maxPhotoCount) {
                                throw TooLargeException("photo count exceeds ${limits.maxPhotoCount}")
                            }
                            val dest = File(stagingDir, bare)
                            val written = zip.copyEntryBounded(dest, limits.maxPhotoBytes, "photo $bare")
                            totalBytes = addTotal(totalBytes, written, limits.maxTotalBytes)
                            photoFiles[bare] = dest
                        }
                    }
                }
                zip.closeEntry()
                entry = zip.nextEntry
            }
        }
        return Extracted(json, photoFiles)
    }

    /** Reads the current zip entry up to [limit] bytes, throwing [TooLargeException] if it exceeds it. */
    private fun ZipInputStream.readEntryBounded(limit: Int, what: String): ByteArray {
        val out = ByteArrayOutputStream()
        val chunk = ByteArray(8192)
        var total = 0L
        while (true) {
            val n = read(chunk)
            if (n < 0) break
            total += n
            if (total > limit) throw TooLargeException("$what exceeds $limit bytes")
            out.write(chunk, 0, n)
        }
        return out.toByteArray()
    }

    /** Streams the current zip entry into [dest] up to [limit] bytes; returns bytes written. */
    private fun ZipInputStream.copyEntryBounded(dest: File, limit: Int, what: String): Long {
        var total = 0L
        dest.outputStream().use { out ->
            val chunk = ByteArray(8192)
            while (true) {
                val n = read(chunk)
                if (n < 0) break
                total += n
                if (total > limit) throw TooLargeException("$what exceeds $limit bytes")
                out.write(chunk, 0, n)
            }
        }
        return total
    }

    private fun addTotal(running: Long, add: Long, max: Long): Long {
        val next = running + add
        if (next > max) throw TooLargeException("archive exceeds $max uncompressed bytes")
        return next
    }
}
