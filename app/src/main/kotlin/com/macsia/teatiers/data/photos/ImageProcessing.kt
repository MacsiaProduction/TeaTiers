package com.macsia.teatiers.data.photos

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import androidx.exifinterface.media.ExifInterface
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import kotlin.math.max

/**
 * Shared image-normalisation used by both the OCR upload path ([AndroidImageReader], small target)
 * and the permanent photo store ([AndroidPhotoStore], larger target): decode [raw] with an
 * `inSampleSize` that lands near [maxDim], fold the EXIF orientation (rotation + any mirror) into the
 * pixels, downscale so the longest edge is <= [maxDim], and re-encode as JPEG at [quality].
 *
 * Re-encoding to JPEG also drops all EXIF metadata (including any GPS tag) — a privacy win the store
 * gets for free, matching what the OCR re-encode already did.
 *
 * Returns null if [raw] can't be decoded as an image (the caller decides whether to fall back).
 */
internal fun decodeOrientDownscaleJpeg(raw: ByteArray, maxDim: Int, quality: Int): ByteArray? {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeByteArray(raw, 0, raw.size, bounds)
    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
    val decodeOpts = BitmapFactory.Options().apply {
        inSampleSize = sampleSize(bounds.outWidth, bounds.outHeight, maxDim)
    }
    val decoded = BitmapFactory.decodeByteArray(raw, 0, raw.size, decodeOpts) ?: return null
    // Camera apps store landscape sensor pixels + an EXIF Orientation tag rather than rotating the
    // pixels; BitmapFactory ignores that tag. Fold the orientation AND the final downscale into ONE
    // Matrix → a single output bitmap.
    val oriented = orientAndScale(decoded, raw, maxDim)
    return ByteArrayOutputStream().use { out ->
        oriented.compress(Bitmap.CompressFormat.JPEG, quality, out)
        if (oriented !== decoded) decoded.recycle()
        oriented.recycle()
        out.toByteArray()
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
