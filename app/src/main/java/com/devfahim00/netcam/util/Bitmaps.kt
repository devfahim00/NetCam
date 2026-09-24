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

/**
 * Decodes JPEG bytes into an upright bitmap whose total pixel count is at
 * most [maxPixels] (memory-safe cap regardless of sensor aspect ratio).
 */
fun decodeJpegBytesCapped(
    bytes: ByteArray,
    rotationDegrees: Int,
    maxPixels: Int
): Bitmap? {
    val longSideGuess = kotlin.math.sqrt(maxPixels.toFloat()).toInt().coerceAtLeast(64)
    val decoded = decodeJpegBytes(bytes, rotationDegrees, longSideGuess) ?: return null
    return decoded.fitToArea(maxPixels)
}

/** Scales a bitmap so its total area is at most [maxArea] (no-op if smaller). */
fun Bitmap.fitToArea(maxArea: Int): Bitmap {
    val area = width.toLong() * height.toLong()
    if (area <= maxArea) return this
    val scale = kotlin.math.sqrt(maxArea.toDouble() / area).toFloat()
    val w = (width * scale).roundToInt().coerceAtLeast(1)
    val h = (height * scale).roundToInt().coerceAtLeast(1)
    return Bitmap.createScaledBitmap(this, w, h, true)
}

/** Mirrors a bitmap horizontally (for selfie shots that should match preview). */
fun Bitmap.mirrorHorizontal(): Bitmap {
    val matrix = Matrix().apply { preScale(-1f, 1f) }
    return Bitmap.createBitmap(this, 0, 0, width, height, matrix, true)
}

/** Center-cropped square (used by the 1:1 aspect mode). */
fun Bitmap.cropCenterSquare(): Bitmap {
    val side = minOf(width, height)
    val x = (width - side) / 2
    val y = (height - side) / 2
    return Bitmap.createBitmap(this, x, y, side, side)
}

/**
 * Wraps a float mask (values 0..1) into an opaque grayscale bitmap. Scaling
 * this bitmap uses the framework's bilinear filtering, which gives us smooth,
 * high-quality mask upsampling without any manual interpolation code.
 */
fun maskToBitmap(mask: FloatArray, w: Int, h: Int): Bitmap {
    val pixels = IntArray(w * h)
    for (i in pixels.indices) {
        val v = (mask[i].coerceIn(0f, 1f) * 255f + 0.5f).toInt()
        pixels[i] = (0xFF shl 24) or (v shl 16) or (v shl 8) or v
    }
    return Bitmap.createBitmap(pixels, w, h, Bitmap.Config.ARGB_8888)
}

/** Reads a grayscale bitmap back into a float array (luminance in 0..1). */
fun bitmapToGrayFloat(src: Bitmap): FloatArray {
    val w = src.width
    val h = src.height
    val pixels = IntArray(w * h)
    src.getPixels(pixels, 0, w, 0, 0, w, h)
    val out = FloatArray(w * h)
    for (i in out.indices) {
        val p = pixels[i]
        val r = (p shr 16) and 0xFF
        val g = (p shr 8) and 0xFF
        val b = p and 0xFF
        out[i] = (0.299f * r + 0.587f * g + 0.114f * b) / 255f
    }
    return out
}

/** Upscales a float mask to an arbitrary size using bilinear bitmap scaling. */
fun upscaleMask(mask: FloatArray, w: Int, h: Int, outW: Int, outH: Int): FloatArray {
    if (w == outW && h == outH) return mask
    val small = maskToBitmap(mask, w, h)
    val big = Bitmap.createScaledBitmap(small, outW, outH, true)
    return bitmapToGrayFloat(big)
}

/**
 * Wraps float values (0..1) into a WHITE bitmap that carries each value in
 * its ALPHA channel. Drawing this with PorterDuff DST_IN multiplies the
 * destination's alpha by the value — a fast, fully native per-pixel mask.
 */
fun alphaValuesToBitmap(values: FloatArray, w: Int, h: Int): Bitmap {
    val pixels = IntArray(w * h)
    for (i in pixels.indices) {
        val a = (values[i].coerceIn(0f, 1f) * 255f + 0.5f).toInt()
        pixels[i] = (a shl 24) or 0x00FFFFFF
    }
    return Bitmap.createBitmap(pixels, w, h, Bitmap.Config.ARGB_8888)
}

/** Largest pixel count that is safe to allocate for in-memory processing. */
fun safeBitmapPixelBudget(): Int {
    val maxMemoryMb = Runtime.getRuntime().maxMemory() / (1024L * 1024L)
    return when {
        maxMemoryMb >= 700L -> 24_000_000
        maxMemoryMb >= 450L -> 16_000_000
        maxMemoryMb >= 280L -> 12_000_000
        else -> 8_000_000
    }
}
