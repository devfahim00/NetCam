package com.devfahim00.netcam.processing

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import com.devfahim00.netcam.util.fastBlur
import com.devfahim00.netcam.util.scaleLongestSideTo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Outcome of a portrait capture. */
sealed class PortraitResult {
    data class Success(val bitmap: Bitmap) : PortraitResult()
    object NoSubject : PortraitResult()
    object Failed : PortraitResult()
}

/**
 * Professional-looking portrait effect:
 *
 *  1. cap the photo to a sane size (memory + speed on low-end devices)
 *  2. ML Kit subject segmentation -> subject cut-out with alpha matting
 *  3. pyramid-blur the full photo (strong, smooth, cheap bokeh)
 *  4. composite the sharp subject cut-out on top of the blurred background
 */
object PortraitProcessor {

    private const val MAX_OUTPUT_SIDE = 2560
    private const val BLUR_DIVISOR = 11f
    private const val BLUR_MIN_SIDE = 40

    suspend fun process(source: Bitmap): PortraitResult = withContext(Dispatchers.Default) {
        val base = scaleLongestSideTo(source, MAX_OUTPUT_SIDE)

        val foreground = SegmentationManager.subjectBitmap(base)
            ?: return@withContext PortraitResult.Failed

        if (!hasVisibleSubject(foreground)) {
            return@withContext PortraitResult.NoSubject
        }

        try {
            val blurred = fastBlur(base, BLUR_DIVISOR, BLUR_MIN_SIDE)
            val output = blurred.copy(Bitmap.Config.ARGB_8888, true)
            val canvas = Canvas(output)
            val paint = Paint().apply { isFilterBitmap = true }
            canvas.drawBitmap(
                foreground,
                null,
                Rect(0, 0, output.width, output.height),
                paint
            )
            PortraitResult.Success(output)
        } catch (t: Throwable) {
            PortraitResult.Failed
        }
    }

    /** Samples the alpha channel to check the cut-out actually contains a subject. */
    private fun hasVisibleSubject(bitmap: Bitmap): Boolean {
        val w = bitmap.width
        val h = bitmap.height
        if (w <= 0 || h <= 0) return false

        val pixels = IntArray(w * h)
        bitmap.getPixels(pixels, 0, w, 0, 0, w, h)

        val stride = maxOf(1, pixels.size / 4096)
        var sampled = 0
        var visible = 0
        var i = 0
        while (i < pixels.size) {
            sampled++
            if ((pixels[i] ushr 24) >= 128) visible++
            i += stride
        }
        if (sampled == 0) return false
        // Subject should cover at least ~0.5% of the frame to be believable.
        return visible.toFloat() / sampled >= 0.005f
    }
}
