package com.macsia.teatiers.data.repository

import android.net.Uri
import com.macsia.teatiers.data.photos.PhotoStore
import java.io.InputStream
import java.util.concurrent.atomic.AtomicInteger

/**
 * Pure-JVM fake of [PhotoStore]. Maps the picker URI's `toString()` to a deterministic
 * `/fake/<n>.jpg` absolute path so tests can assert path strings without touching the disk.
 *
 * - [copyIn] returns `null` for a URI whose `toString()` is registered in [failures] so we can
 *   exercise the "copy failed → no row" path.
 * - [delete] records every deletion for later assertions and is a no-op for files we never
 *   stored (best-effort, mirrors the production contract).
 */
class FakePhotoStore : PhotoStore {

    val failures: MutableSet<String> = mutableSetOf()

    /** Bundled photo names whose [importInto] should fail (null), to exercise partial-restore cleanup. */
    val importFailures: MutableSet<String> = mutableSetOf()
    val deleted: MutableList<String> = mutableListOf()
    val stored: MutableMap<String, String> = mutableMapOf()

    /** Files the fake "has on disk" — tests may pre-seed an orphan; [reconcile] sweeps it. */
    val onDisk: MutableSet<String> = mutableSetOf()

    /** Every keep-set [reconcile] was called with, for asserting the import/app-open sweep. */
    val reconcileCalls: MutableList<Set<String>> = mutableListOf()

    private val counter = AtomicInteger(0)

    override suspend fun copyIn(source: Uri): String? {
        val key = source.toString()
        if (key in failures) return null
        val path = "/fake/${counter.incrementAndGet()}.jpg"
        stored[key] = path
        onDisk += path
        return path
    }

    override suspend fun delete(path: String): Boolean {
        deleted += path
        onDisk -= path
        return true
    }

    override suspend fun importInto(originalName: String, input: InputStream): String? {
        input.readBytes()
        if (originalName in importFailures) return null
        val path = "/fake/imported-${counter.incrementAndGet()}.jpg"
        onDisk += path
        return path
    }

    override suspend fun reconcile(keepPaths: Set<String>): Int {
        reconcileCalls += keepPaths
        val orphans = onDisk.filterNot { it in keepPaths }
        onDisk.removeAll(orphans.toSet())
        deleted += orphans
        return orphans.size
    }
}
