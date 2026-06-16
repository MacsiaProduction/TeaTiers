package com.macsia.teatiers.data.backup

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
     * Reads a backup zip from [input]. Returns the JSON text and any `photos/<name>` bytes keyed by
     * the bare file name. A zip with no [BACKUP_JSON_ENTRY] yields an empty json string so the
     * caller can reject it as "not a backup". Ignores directory entries and unexpected paths.
     */
    fun read(input: InputStream): Parsed {
        var json = ""
        val photos = mutableMapOf<String, ByteArray>()
        ZipInputStream(input).use { zip ->
            var entry: ZipEntry? = zip.nextEntry
            while (entry != null) {
                val name = entry.name
                when {
                    entry.isDirectory -> Unit
                    name == BACKUP_JSON_ENTRY -> json = zip.readBytes().toString(Charsets.UTF_8)
                    name.startsWith(BACKUP_PHOTO_DIR) -> {
                        val bare = name.removePrefix(BACKUP_PHOTO_DIR)
                        if (bare.isNotEmpty()) photos[bare] = zip.readBytes()
                    }
                }
                zip.closeEntry()
                entry = zip.nextEntry
            }
        }
        return Parsed(json, photos)
    }
}
