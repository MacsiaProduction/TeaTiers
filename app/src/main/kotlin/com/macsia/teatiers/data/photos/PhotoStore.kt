package com.macsia.teatiers.data.photos

import android.net.Uri
import java.io.InputStream

/**
 * Storage layer for the per-tea photo list (decisions.md #43). The repository delegates here so
 * the photo bytes live in app-private storage (cleanly survives gallery cleanup, exports/imports
 * cleanly, no `content://` permissions to track) while the DB only stores the resulting absolute
 * file path. The interface is pure-Kotlin so the unit-test fake stays JVM-only — no Robolectric
 * just to fake an [android.content.ContentResolver].
 */
interface PhotoStore {
    /**
     * Copies the picker's [source] into the app-private photo dir under a fresh UUID-based
     * filename and returns its **absolute file path**. Returns null if the source URI cannot be
     * opened/copied (caller treats it as a no-op so a botched pick never half-creates a row).
     */
    suspend fun copyIn(source: Uri): String?

    /**
     * Deletes the file at [path]. Best-effort: a missing file is treated as success so a stale
     * URI from an interrupted earlier delete does not block the row delete on retry.
     */
    suspend fun delete(path: String): Boolean

    /**
     * Writes [input] into the app-private photo dir under a fresh UUID-based filename, keeping
     * [originalName]'s extension, and returns the new **absolute file path**. Used by import
     * (#26) to land a photo bundled in a backup .zip into stable local storage. Returns null on
     * an I/O failure so the caller can drop that one photo without aborting the whole restore.
     */
    suspend fun importInto(originalName: String, input: InputStream): String?
}
