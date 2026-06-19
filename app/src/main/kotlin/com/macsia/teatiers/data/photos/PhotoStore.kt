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

    /**
     * Deletes every file in the store whose absolute path is NOT in [keepPaths]. Used after a
     * destructive backup import — which replaces all DB rows, orphaning the prior corpus's files —
     * and on app-open to sweep any leftover orphans (review 2026-06-19). Best-effort; returns the
     * number of files deleted. A path in [keepPaths] that isn't on disk is simply ignored.
     *
     * Implementations should leave a *very recently written* file alone even if it isn't in
     * [keepPaths]: it may be an in-flight [copyIn] whose DB row hasn't been inserted yet, and the
     * app-open sweep must not race-delete it. Such a young orphan is caught by the next sweep.
     */
    suspend fun reconcile(keepPaths: Set<String>): Int
}
