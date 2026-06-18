package com.macsia.teatiers.data.photos

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.net.Uri
import android.util.Log
import androidx.exifinterface.media.ExifInterface
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.max

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
            // Camera apps store landscape sensor pixels + an EXIF Orientation tag rather than rotating
            // the pixels; BitmapFactory ignores that tag. The sidecar runs angle-cls OFF (decision
            // #105), so an un-rotated portrait scan recognizes as near-garbage. Fold the orientation
            // (rotation + any mirror) AND the final downscale into ONE Matrix → a single output bitmap.
            val oriented = orientAndScale(decoded, raw, MAX_DIM)
            ByteArrayOutputStream().use { out ->
                oriented.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, out)
                if (oriented !== decoded) decoded.recycle()
                oriented.recycle()
                out.toByteArray()
            }
        }.getOrElse { e ->
            Log.w(TAG, "Failed to read image $uri", e)
            null
        }
    }

    private fun orientAndScale(bitmap: Bitmap, raw: ByteArray, maxDim: Int): Bitmap {
        val matrix = exifMatrix(raw)
        val longest = max(bitmap.width, bitmap.height)
        if (longest > maxDim) {
            val ratio = maxDim.toFloat() / longest
            matrix.postScale(ratio, ratio) // uniform scale commutes with the rotation/flip above
        }
        // createScaledBitmap has no Matrix overload; createBitmap(src,x,y,w,h,matrix,filter) does.
        return if (matrix.isIdentity) {
            bitmap
        } else {
            Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
        }
    }

    /** A Matrix that undoes the JPEG EXIF Orientation (rotation + any mirror); identity if none/unset. */
    private fun exifMatrix(raw: ByteArray): Matrix {
        val orientation = runCatching {
            ExifInterface(ByteArrayInputStream(raw))
                .getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
        }.getOrDefault(ExifInterface.ORIENTATION_NORMAL)
        return Matrix().apply {
            when (orientation) {
                ExifInterface.ORIENTATION_ROTATE_90 -> postRotate(90f)
                ExifInterface.ORIENTATION_ROTATE_180 -> postRotate(180f)
                ExifInterface.ORIENTATION_ROTATE_270 -> postRotate(270f)
                ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> postScale(-1f, 1f)
                ExifInterface.ORIENTATION_FLIP_VERTICAL -> postScale(1f, -1f)
                ExifInterface.ORIENTATION_TRANSPOSE -> { postRotate(90f); postScale(-1f, 1f) }
                ExifInterface.ORIENTATION_TRANSVERSE -> { postRotate(270f); postScale(-1f, 1f) }
                else -> Unit // NORMAL / UNDEFINED → identity
            }
        }
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
