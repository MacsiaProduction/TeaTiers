package com.macsia.teatiers.data.photos

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Reads a picked/captured image [Uri] into bytes for the OCR upload, downscaled + re-encoded so the
 * payload stays well under the server's cap (a full-res phone photo is often >8 MB). Kept behind an
 * interface so the OCR-scan ViewModel logic stays JVM-testable without Android image decoding.
 */
interface ImageReader {
    /** Decoded, downscaled JPEG bytes, or null if the URI can't be read/decoded. */
    suspend fun read(uri: Uri): ByteArray?
}

@Singleton
class AndroidImageReader @Inject constructor(
    @ApplicationContext private val context: Context,
) : ImageReader {

    override suspend fun read(uri: Uri): ByteArray? = withContext(Dispatchers.IO) {
        runCatching {
            val resolver = context.contentResolver
            val raw = resolver.openInputStream(uri)?.use { it.readBytes() } ?: return@runCatching null
            // Cheap bounds pass to pick an inSampleSize, then decode at ~MAX_DIM and JPEG-encode.
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(raw, 0, raw.size, bounds)
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return@runCatching null
            val decodeOpts = BitmapFactory.Options().apply {
                inSampleSize = sampleSize(bounds.outWidth, bounds.outHeight, MAX_DIM)
            }
            val decoded = BitmapFactory.decodeByteArray(raw, 0, raw.size, decodeOpts)
                ?: return@runCatching null
            val scaled = downscale(decoded, MAX_DIM)
            ByteArrayOutputStream().use { out ->
                scaled.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, out)
                if (scaled !== decoded) decoded.recycle()
                scaled.recycle()
                out.toByteArray()
            }
        }.getOrElse { e ->
            Log.w(TAG, "Failed to read image $uri", e)
            null
        }
    }

    private fun downscale(bitmap: Bitmap, maxDim: Int): Bitmap {
        val longest = maxOf(bitmap.width, bitmap.height)
        if (longest <= maxDim) return bitmap
        val ratio = maxDim.toFloat() / longest
        return Bitmap.createScaledBitmap(
            bitmap,
            (bitmap.width * ratio).toInt().coerceAtLeast(1),
            (bitmap.height * ratio).toInt().coerceAtLeast(1),
            true,
        )
    }

    private fun sampleSize(width: Int, height: Int, maxDim: Int): Int {
        var sample = 1
        while (width / (sample * 2) >= maxDim || height / (sample * 2) >= maxDim) sample *= 2
        return sample
    }

    private companion object {
        const val TAG = "ImageReader"
        const val MAX_DIM = 1600 // longest-side cap; det downscales to 960 anyway (decision #105)
        const val JPEG_QUALITY = 85
    }
}
