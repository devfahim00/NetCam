package com.devfahim00.netcam.util

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import kotlin.math.roundToInt

/**
 * Decodes JPEG bytes from CameraX into an upright bitmap, optionally capped
 * to [maxLongSide] to keep memory and processing time under control.
 */
fun decodeJpegBytes(
    bytes: ByteArray,
    rotationDegrees: Int,
    maxLongSide: Int = Int.MAX_VALUE
): Bitmap? {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

    var sample = 1
    while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= maxLongSide) {
        sample *= 2
    }

    val opts = BitmapFactory.Options().apply { inSampleSize = sample }
    val decoded = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts) ?: return null

    val matrix = Matrix()
    if (rotationDegrees != 0) {
        matrix.postRotate(rotationDegrees.toFloat())
    }

    val longSide = maxOf(decoded.width, decoded.height)
    if (longSide > maxLongSide) {
        val scale = maxLongSide.toFloat() / longSide
        matrix.postScale(scale, scale)
    }

    return if (matrix.isIdentity) {
        decoded
    } else {
        Bitmap.createBitmap(decoded, 0, 0, decoded.width, decoded.height, matrix, true)
    }
}

/**
 * Fast, allocation-friendly blur: progressively halves the bitmap with
 * bilinear filtering (which approximates area averaging) and upsamples once
 * at the end. Runs on the CPU without any heavy per-pixel kernel, so it stays
 * smooth even on low-end devices.
 *
 * @param blurDivisor how small the intermediate image is relative to the
 *   source. Larger value = stronger blur.
 * @param minSide floor for the intermediate size, keeps tiny inputs sane.
 */
fun fastBlur(src: Bitmap, blurDivisor: Float, minSide: Int): Bitmap {
    require(src.width > 0 && src.height > 0) { "empty source bitmap" }

    val targetW = (src.width / blurDivisor).toInt().coerceAtLeast(minSide)
    val targetH = (src.height / blurDivisor).toInt().coerceAtLeast(minSide)

    var current = src
    while (current.width / 2 >= targetW && current.height / 2 >= targetH) {
        current = Bitmap.createScaledBitmap(
            current,
            (current.width / 2).coerceAtLeast(1),
            (current.height / 2).coerceAtLeast(1),
            true
        )
    }

    return if (current.width == src.width && current.height == src.height) {
        src
    } else {
        Bitmap.createScaledBitmap(current, src.width, src.height, true)
    }
}

/** Scales a bitmap so its longest side is at most [maxSide] (no-op if smaller). */
fun scaleLongestSideTo(src: Bitmap, maxSide: Int): Bitmap {
    val longSide = maxOf(src.width, src.height)
    if (longSide <= maxSide) return src
    val scale = maxSide.toFloat() / longSide
    val w = (src.width * scale).roundToInt().coerceAtLeast(1)
    val h = (src.height * scale).roundToInt().coerceAtLeast(1)
    return Bitmap.createScaledBitmap(src, w, h, true)
}

/** Small center-cropped square thumbnail for the gallery button. */
fun Bitmap.centerThumbnail(size: Int): Bitmap {
    val scale = size.toFloat() / minOf(width, height)
    val w = (width * scale).roundToInt().coerceAtLeast(1)
    val h = (height * scale).roundToInt().coerceAtLeast(1)
    val scaled = if (w <= width && h <= height) {
        this
    } else {
        Bitmap.createScaledBitmap(this, w, h, true)
    }
    val x = ((w - size) / 2f).roundToInt().coerceAtLeast(0)
    val y = ((h - size) / 2f).roundToInt().coerceAtLeast(0)
    val side = minOf(size, w, h)
    return Bitmap.createBitmap(scaled, x, y, side, side)
}
