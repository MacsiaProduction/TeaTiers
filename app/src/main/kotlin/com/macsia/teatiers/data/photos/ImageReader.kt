package com.macsia.teatiers.data.photos

import android.content.Context
import android.net.Uri
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Reads a picked/captured image [Uri] into bytes for the OCR upload, applying the EXIF orientation
 * and downscaling + re-encoding so the payload stays well under the server's cap (a full-res phone
 * photo is often >8 MB). Kept behind an interface so the OCR-scan ViewModel logic stays JVM-testable
 * without Android image decoding.
 */
interface ImageReader {
    /** Oriented, downscaled JPEG bytes, or null if the URI can't be read/decoded. */
    suspend fun read(uri: Uri): ByteArray?
}

@Singleton
class AndroidImageReader @Inject constructor(
    @ApplicationContext private val context: Context,
) : ImageReader {

    override suspend fun read(uri: Uri): ByteArray? = withContext(Dispatchers.IO) {
        runCatching {
            val resolver = context.contentResolver
            // Reject a pathological source before readBytes() buffers it whole (local-OOM guard,
            // AND-P1-5). Unknown length (-1) passes; the downscale + server 8 MB cap bound the rest.
            val declaredSize = runCatching {
                resolver.openAssetFileDescriptor(uri, "r")?.use { it.length }
            }.getOrNull()
            if (declaredSize != null && declaredSize > MAX_SOURCE_BYTES) return@runCatching null
            val raw = resolver.openInputStream(uri)?.use { it.readBytes() } ?: return@runCatching null
            decodeOrientDownscaleJpeg(raw, MAX_DIM, JPEG_QUALITY)
        }.getOrElse { e ->
            Log.w(TAG, "Failed to read image", e)
            null
        }
    }

    private companion object {
        const val TAG = "ImageReader"
        const val MAX_DIM = 1600 // longest-side cap; det downscales to 960 anyway (decision #105)
        const val JPEG_QUALITY = 85

        // Upper bound on the raw source we'll buffer into memory before downscaling. Generous — a real
        // phone photo is far smaller; this only stops an OOM on a pathological multi-hundred-MB source.
        const val MAX_SOURCE_BYTES = 25L * 1024 * 1024
    }
}
