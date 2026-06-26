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
import java.io.InputStream
import java.io.OutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "BackupManager"
private const val SHARE_DIR = "backups"

/** Cache subdir an import streams into + validates before any live mutation (review P1-5). */
private const val IMPORT_STAGING_DIR = "import-staging"

/**
 * App-private file holding the auto safety-backup: a snapshot of the corpus taken right before a
 * destructive restore, so a user who imported the wrong file can undo it. A single rolling zip in
 * internal storage (not the SAF-picked location) — it's a safety net, not a user-managed backup.
 */
private const val SAFETY_DIR = "safety"
private const val SAFETY_FILE = "pre-import.zip"

/**
 * Reject a picked import larger than this by its SAF-declared (compressed, on-disk zip) size before reading
 * it (review 2026-06-18, decision #96). Deliberately independent of, and NOT to be "aligned" with,
 * [BackupArchive.Limits.maxTotalBytes] (256 MiB): that one caps the cumulative DECOMPRESSED bytes streamed
 * out, the zip-bomb guard (AND-P2-4). Compressed-pre-check < decompressed-cap is correct — a small archive
 * can still inflate past the decompressed ceiling and is caught there.
 */
private const val MAX_ARCHIVE_BYTES = 200L * 1024 * 1024

/** Outcome of an export/import, mapped to a user message by the ViewModel. */
sealed interface BackupResult {
    data class Exported(val teaCount: Int) : BackupResult
    data class Imported(val teaCount: Int) : BackupResult

    /** The picked file is not a readable TeaTiers backup (bad zip, or unparseable/blank JSON). */
    data object InvalidFile : BackupResult

    /** The backup was written by a newer app version whose format we can't faithfully restore. */
    data object IncompatibleVersion : BackupResult

    /** The archive is missing photo files its data references — a truncated/corrupt backup. */
    data object IncompleteArchive : BackupResult

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
        runImport(protect = true) { context.contentResolver.openInputStream(uri) }
    }

    /** True when an auto safety-backup is on hand to undo the last restore (drives the Settings entry). */
    fun hasSafetyBackup(): Boolean = safetyBackupFile.exists()

    /**
     * Restores the pre-import safety snapshot ([writeSafetyBackup]) — the user's "undo my last
     * restore". Does NOT take a fresh safety backup (`protect = false`): that would overwrite the good
     * copy with the very data we're about to discard. [BackupResult.InvalidFile] when none exists.
     */
    suspend fun restoreSafetyBackup(): BackupResult = withContext(Dispatchers.IO) {
        val file = safetyBackupFile
        if (!file.exists()) return@withContext BackupResult.InvalidFile
        val result = runImport(protect = false) { file.inputStream() }
        // One-shot undo: once the snapshot is reinstated, drop it. Leaving it on disk would keep the
        // "undo last restore" entry live, and a second tap would re-restore this now-stale snapshot
        // over anything the user added since — silent data loss (finding #1).
        if (result is BackupResult.Imported) file.delete()
        result
    }

    private val safetyBackupFile: File
        get() = File(File(context.filesDir, SAFETY_DIR), SAFETY_FILE)

    /** Where [stageSafetyBackup] writes the pre-import snapshot before [commitSafetyBackup] promotes it. */
    private val safetyTmpFile: File
        get() = File(File(context.filesDir, SAFETY_DIR), "$SAFETY_FILE.tmp")

    /**
     * Shared validated-restore core. Streams the archive [openStream] yields into a fresh staging dir
     * (photos to files, never the corpus in RAM) and fully VALIDATES it before touching any live data
     * (review P1-5). A leftover dir from a killed import is cleared first; `finally` clears it on every
     * exit path. When [protect] is set, snapshots the current corpus just before the destructive swap.
     */
    private suspend fun runImport(protect: Boolean, openStream: () -> InputStream?): BackupResult {
        val stagingDir = File(context.cacheDir, IMPORT_STAGING_DIR).apply { deleteRecursively() }
        // Photos streamed into the LIVE store during this import, plus whether the swap committed. Both
        // are read in `finally`: a failed apply must delete the partial photos it wrote (the live DB is
        // untouched / rolled back, so they reference nothing) and drop the uncommitted safety snapshot
        // (findings #14A / #14B).
        val restoredPaths = HashMap<String, String>()
        var imported = false
        return try {
            val extracted = try {
                openStream()?.use { BackupArchive.extractTo(it, stagingDir) }
                    ?: return BackupResult.Failed
            } catch (e: BackupArchive.TooLargeException) {
                Log.w(TAG, "Import rejected: archive too large", e)
                return BackupResult.TooLarge
            }
            if (extracted.json.isBlank()) return BackupResult.InvalidFile
            val bundle = runCatching { json.decodeFromString<BackupBundle>(extracted.json) }
                .getOrElse { return BackupResult.InvalidFile }
            // We cannot faithfully restore a bundle written by a newer app version — tell the user to
            // update rather than claiming their (valid) backup "isn't a TeaTiers backup".
            if (bundle.formatVersion > BACKUP_FORMAT_VERSION) return BackupResult.IncompatibleVersion

            // Atomic guard (review P1-5): every photo the data declares must actually be present in the
            // archive. If any is missing, reject the WHOLE restore and leave current data untouched —
            // never half-restore (the old code silently dropped a missing photo from the imported tea).
            val declared = bundle.bundledPhotoNames()
            val missing = declared.filter { it !in extracted.photoFiles }
            if (missing.isNotEmpty()) {
                Log.w(TAG, "Import rejected: ${missing.size} declared photo(s) missing from the archive")
                return BackupResult.IncompleteArchive
            }

            // Validated and about to destroy the live corpus — snapshot it first so a wrong-file restore
            // is undoable (auto safety-backup). Staged to a temp now; promoted to the live undo only
            // after the swap below succeeds, so a failed/no-op import never leaves a misleading undo or
            // clobbers an earlier one (finding #14B). Skipped when restoring that very snapshot.
            val staged = protect && stageSafetyBackup()

            // Apply. Stream each staged photo into the live store, then swap the DB.
            for (name in declared) {
                val src = extracted.photoFiles.getValue(name)
                val path = src.inputStream().use { photoStore.importInto(name, it) }
                    ?: return BackupResult.Failed
                restoredPaths[name] = path
            }
            val entities = bundle.toSeedEntities(restoredPaths)
            dao.replaceAll(entities)
            imported = true
            if (staged) commitSafetyBackup()
            // A destructive replace-all orphans every photo file the *old* corpus referenced. Sweep
            // them now, keeping exactly the files the freshly-imported rows point at (review
            // 2026-06-18). Best-effort: the DB is already restored, so a sweep failure must not fail
            // the import — the leftovers get caught by the app-open reconcile.
            val keepPaths = entities.photos.map { it.uri }.filter { it.startsWith("/") }.toSet()
            runCatching { photoStore.reconcile(keepPaths) }
                .onFailure { Log.w(TAG, "Photo reconcile after import failed", it) }
            BackupResult.Imported(entities.teas.size)
        } catch (e: Exception) {
            Log.w(TAG, "Import write failed", e)
            BackupResult.Failed
        } finally {
            runCatching { stagingDir.deleteRecursively() }
            // Drop any uncommitted safety snapshot (a successful commit already renamed it away).
            runCatching { safetyTmpFile.delete() }
            // On any non-success path, the photos already streamed into the live store are orphans —
            // delete them now instead of waiting for the next app-open reconcile (finding #14A).
            if (!imported) restoredPaths.values.forEach { p -> runCatching { photoStore.delete(p) } }
        }
    }

    /**
     * Snapshots the CURRENT corpus to [safetyTmpFile] right before a destructive restore, returning
     * true when a snapshot was written. Skipped (returns false) when the DB holds no teas — nothing to
     * protect, and so a previous, meaningful safety copy is not clobbered by an empty one. The snapshot
     * is only promoted to the live undo by [commitSafetyBackup] once the restore actually succeeds.
     * Best-effort: a failure here only forgoes the undo, it must never block the restore the user
     * explicitly asked for.
     */
    private suspend fun stageSafetyBackup(): Boolean = runCatching {
        if (dao.teaCount() == 0) return false
        safetyTmpFile.parentFile?.mkdirs()
        FileOutputStream(safetyTmpFile).use { writeArchive(it) }
        true
    }.onFailure { Log.w(TAG, "Pre-import safety backup failed; restore proceeds without an undo", it) }
        .getOrDefault(false)

    /**
     * Promotes the staged snapshot ([stageSafetyBackup]) to the live undo, atomically via rename so a
     * crash mid-write can't leave a half-zip the undo would reject. Called only after the destructive
     * swap succeeds, so the undo always reflects a restore that really happened.
     */
    private fun commitSafetyBackup() {
        if (!safetyTmpFile.renameTo(safetyBackupFile)) {
            runCatching {
                safetyTmpFile.copyTo(safetyBackupFile, overwrite = true)
                safetyTmpFile.delete()
            }.onFailure { Log.w(TAG, "Could not commit safety backup", it) }
        }
    }

    /** Serializes the current DB + bundles photo files into [out]; returns the exported tea count. */
    private suspend fun writeArchive(out: OutputStream): Int {
        val snapshot = dao.exportSnapshot()
        // Drop file-backed photos whose file is GONE (gallery cleanup, an orphaned path, a crash
        // between copy-in and row-insert) BEFORE building the bundle, so the JSON declares exactly the
        // files the zip carries. Otherwise the strict, atomic importer (review P1-5) would reject the
        // WHOLE restore over a single stale path — making the backup un-restorable (review N1). URL
        // photos have no local file and always survive.
        val exportable = snapshot.copy(
            photos = snapshot.photos.filter { !it.uri.startsWith("/") || File(it.uri).exists() },
        )
        val bundle = exportable.toBundle(System.currentTimeMillis(), BuildConfig.VERSION_NAME)
        // Stream each photo file straight into the zip (review P1-5) — never read the whole corpus
        // into memory, so a large collection can't OOM the export.
        val photos = exportable.photos
            .filter { it.uri.startsWith("/") }
            .map { photo -> BackupArchive.PhotoSource(photo.bundledFileName()) { File(photo.uri).inputStream() } }
        BackupArchive.write(out, json.encodeToString(bundle), photos)
        return exportable.teas.size
    }

    private fun fileName(): String {
        val stamp = SimpleDateFormat("yyyyMMdd-HHmm", Locale.US).format(Date())
        return "teatiers-backup-$stamp.zip"
    }
}
