package com.macsia.teatiers.data.backup

import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.core.content.FileProvider
import com.macsia.teatiers.BuildConfig
import com.macsia.teatiers.data.db.TeaDao
import com.macsia.teatiers.data.photos.PhotoStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "BackupManager"
private const val SHARE_DIR = "backups"

/** Reject a picked import larger than this before reading it (review 2026-06-18, decision #96). */
private const val MAX_ARCHIVE_BYTES = 200L * 1024 * 1024

/** Outcome of an export/import, mapped to a user message by the ViewModel. */
sealed interface BackupResult {
    data class Exported(val teaCount: Int) : BackupResult
    data class Imported(val teaCount: Int) : BackupResult

    /** The picked file is not a readable TeaTiers backup (bad zip/JSON or a newer format). */
    data object InvalidFile : BackupResult

    /** The picked archive breaches the import size/count bounds (too large or a zip bomb). */
    data object TooLarge : BackupResult

    /** An I/O failure (stream could not be opened, disk error). */
    data object Failed : BackupResult
}

/**
 * Reads/writes the full-catalog backup zip (decisions.md #26). Pure mapping ([toBundle] /
 * [toSeedEntities]) and zip layout ([BackupArchive]) live in sibling files; this class is the thin
 * Android glue: open SAF streams, copy photo bytes via [PhotoStore], drive the DB through [TeaDao].
 * Import is **destructive** (replace-all) per the locked decision.
 */
@Singleton
class BackupManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val dao: TeaDao,
    private val photoStore: PhotoStore,
) {
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    /** Writes a backup to a SAF-picked document [uri] ("save to" flow). */
    suspend fun exportTo(uri: Uri): BackupResult = withContext(Dispatchers.IO) {
        try {
            val output = context.contentResolver.openOutputStream(uri) ?: return@withContext BackupResult.Failed
            val teaCount = output.use { writeArchive(it) }
            BackupResult.Exported(teaCount)
        } catch (e: Exception) {
            Log.w(TAG, "Export failed", e)
            BackupResult.Failed
        }
    }

    /**
     * Writes a backup into a cache file and returns a FileProvider content URI to hand to the
     * system share sheet, or null on failure. The cache dir is auto-purged by the OS; we also
     * clear stale share files first so the dir does not accumulate.
     */
    suspend fun createShareUri(): Uri? = withContext(Dispatchers.IO) {
        try {
            val dir = File(context.cacheDir, SHARE_DIR).apply { mkdirs() }
            dir.listFiles()?.forEach { it.delete() }
            val file = File(dir, fileName())
            FileOutputStream(file).use { writeArchive(it) }
            FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        } catch (e: Exception) {
            Log.w(TAG, "Share export failed", e)
            null
        }
    }

    /** Restores a backup from a SAF-picked [uri], replacing all current data. */
    suspend fun importFrom(uri: Uri): BackupResult = withContext(Dispatchers.IO) {
        // Cheap up-front guard: reject an oversized archive before reading a byte of it.
        val declaredSize = runCatching {
            context.contentResolver.openAssetFileDescriptor(uri, "r")?.use { it.length }
        }.getOrNull()
        if (declaredSize != null && declaredSize > MAX_ARCHIVE_BYTES) {
            return@withContext BackupResult.TooLarge
        }
        val parsed = try {
            context.contentResolver.openInputStream(uri)?.use { BackupArchive.read(it) }
                ?: return@withContext BackupResult.Failed
        } catch (e: BackupArchive.TooLargeException) {
            Log.w(TAG, "Import rejected: archive too large", e)
            return@withContext BackupResult.TooLarge
        } catch (e: Exception) {
            Log.w(TAG, "Import read failed", e)
            return@withContext BackupResult.Failed
        }
        if (parsed.json.isBlank()) return@withContext BackupResult.InvalidFile
        val bundle = runCatching { json.decodeFromString<BackupBundle>(parsed.json) }
            .getOrElse { return@withContext BackupResult.InvalidFile }
        // We cannot faithfully restore a bundle written by a newer app version.
        if (bundle.formatVersion > BACKUP_FORMAT_VERSION) return@withContext BackupResult.InvalidFile

        return@withContext try {
            val restoredPaths = HashMap<String, String>()
            bundle.bundledPhotoNames().forEach { name ->
                val bytes = parsed.photoBytes[name] ?: return@forEach
                photoStore.importInto(name, bytes.inputStream())?.let { restoredPaths[name] = it }
            }
            val entities = bundle.toSeedEntities(restoredPaths)
            dao.replaceAll(entities)
            BackupResult.Imported(entities.teas.size)
        } catch (e: Exception) {
            Log.w(TAG, "Import write failed", e)
            BackupResult.Failed
        }
    }

    /** Serializes the current DB + bundles photo files into [out]; returns the exported tea count. */
    private suspend fun writeArchive(out: OutputStream): Int {
        val snapshot = dao.exportSnapshot()
        val bundle = snapshot.toBundle(System.currentTimeMillis(), BuildConfig.VERSION_NAME)
        val photoBytes = snapshot.photos
            .filter { it.uri.startsWith("/") }
            .mapNotNull { photo ->
                val file = File(photo.uri)
                if (file.exists()) photo.bundledFileName() to file.readBytes() else null
            }
            .toMap()
        BackupArchive.write(out, json.encodeToString(bundle), photoBytes)
        return snapshot.teas.size
    }

    private fun fileName(): String {
        val stamp = SimpleDateFormat("yyyyMMdd-HHmm", Locale.US).format(Date())
        return "teatiers-backup-$stamp.zip"
    }
}
