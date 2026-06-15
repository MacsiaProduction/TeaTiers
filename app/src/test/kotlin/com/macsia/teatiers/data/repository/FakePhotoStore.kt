package com.macsia.teatiers.data.repository

import android.net.Uri
import com.macsia.teatiers.data.photos.PhotoStore
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
    val deleted: MutableList<String> = mutableListOf()
    val stored: MutableMap<String, String> = mutableMapOf()

    private val counter = AtomicInteger(0)

    override suspend fun copyIn(source: Uri): String? {
        val key = source.toString()
        if (key in failures) return null
        val path = "/fake/${counter.incrementAndGet()}.jpg"
        stored[key] = path
        return path
    }

    override suspend fun delete(path: String): Boolean {
        deleted += path
        return true
    }
}
