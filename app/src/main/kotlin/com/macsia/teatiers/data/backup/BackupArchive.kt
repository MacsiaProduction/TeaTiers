package com.macsia.teatiers.data.backup

import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * Pure java.util.zip read/write for the backup bundle (decisions.md #26). No Android, no DB — kept
 * separate from [BackupManager] so the zip layout is unit-testable on the JVM. The bundle is a flat
 * zip: [BACKUP_JSON_ENTRY] at the root plus photo files under [BACKUP_PHOTO_DIR].
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

    /** Thrown by [read] when an archive breaches a [Limits] bound; the caller maps it to "too large". */
    class TooLargeException(message: String) : Exception(message)

    /** Parsed archive: the JSON text and the bundled photo bytes keyed by their entry file name. */
    data class Parsed(val json: String, val photoBytes: Map<String, ByteArray>)

    /** Writes [json] and [photoBytes] (entry name -> bytes) into [out] as a zip. Does not close [out]. */
    fun write(out: OutputStream, json: String, photoBytes: Map<String, ByteArray>) {
        ZipOutputStream(out).use { zip ->
            zip.putNextEntry(ZipEntry(BACKUP_JSON_ENTRY))
            zip.write(json.toByteArray(Charsets.UTF_8))
            zip.closeEntry()
            photoBytes.forEach { (name, bytes) ->
                zip.putNextEntry(ZipEntry("$BACKUP_PHOTO_DIR$name"))
                zip.write(bytes)
                zip.closeEntry()
            }
        }
    }

    /**
     * Reads a backup zip from [input], enforcing [limits] so an oversized/malicious archive is
     * rejected instead of OOMing. Returns the JSON text and any `photos/<name>` bytes keyed by the
     * bare file name. A zip with no [BACKUP_JSON_ENTRY] yields an empty json string so the caller
     * can reject it as "not a backup". Ignores directory entries, nested paths, and unexpected
     * names. Throws [TooLargeException] when a bound is breached.
     */
    fun read(input: InputStream, limits: Limits = Limits()): Parsed {
        var json = ""
        val photos = mutableMapOf<String, ByteArray>()
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
                        totalBytes = addTotal(totalBytes, bytes.size, limits.maxTotalBytes)
                        json = bytes.toString(Charsets.UTF_8)
                    }
                    name.startsWith(BACKUP_PHOTO_DIR) -> {
                        val bare = name.removePrefix(BACKUP_PHOTO_DIR)
                        // Skip empty/nested/traversal names defensively (PhotoStore also renames to a UUID).
                        if (bare.isNotEmpty() && !bare.contains('/') && !bare.contains("..")) {
                            if (++photoCount > limits.maxPhotoCount) {
                                throw TooLargeException("photo count exceeds ${limits.maxPhotoCount}")
                            }
                            val bytes = zip.readEntryBounded(limits.maxPhotoBytes, "photo $bare")
                            totalBytes = addTotal(totalBytes, bytes.size, limits.maxTotalBytes)
                            photos[bare] = bytes
                        }
                    }
                }
                zip.closeEntry()
                entry = zip.nextEntry
            }
        }
        return Parsed(json, photos)
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

    private fun addTotal(running: Long, add: Int, max: Long): Long {
        val next = running + add
        if (next > max) throw TooLargeException("archive exceeds $max uncompressed bytes")
        return next
    }
}
