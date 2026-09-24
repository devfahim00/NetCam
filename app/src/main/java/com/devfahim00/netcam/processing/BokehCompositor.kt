package com.devfahim00.netcam.processing

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.Rect
import com.devfahim00.netcam.util.alphaValuesToBitmap
import com.devfahim00.netcam.util.bitmapToGrayFloat
import com.devfahim00.netcam.util.fastBlur
import com.devfahim00.netcam.util.maskToBitmap

/**
 * Depth-graded portrait bokeh.
 *
 * Instead of pasting one uniformly-blurred background behind the subject
 * (the classic "sticker" look), the background is rendered as three blur
 * layers of increasing strength and blended per-pixel by a distance field:
 * pixels just behind the subject stay almost sharp, distant pixels get the
 * full creamy blur — like a real wide-aperture lens.
 *
 * The composite runs at capture resolution: masks and blur bands are computed
 * at half resolution (invisible in blurred regions) and the sharp subject is
 * never rescaled.
 */
object BokehCompositor {

    /**
     * @param base full-resolution photo (ARGB_8888).
     * @param alpha refined subject mask at [workW] x [workH] (1 = subject).
     * @param dist normalized distance field at [workW] x [workH].
     * @param strength user blur strength, 0..1.5 (0 = passthrough).
     */
    fun composite(
        base: Bitmap,
        workW: Int,
        workH: Int,
        alpha: FloatArray,
        dist: FloatArray,
        strength: Float
    ): Bitmap {
        val w = base.width
        val h = base.height
        if (strength <= 0.02f) return base.copy(Bitmap.Config.ARGB_8888, true)

        val halfW = (w / 2).coerceAtLeast(1)
        val halfH = (h / 2).coerceAtLeast(1)

        // --- 1) Three blur layers from the half-res background ---
        val bgHalf = Bitmap.createScaledBitmap(base, halfW, halfH, true)
        val s = 0.45f + 0.85f * strength
        val l1 = fastBlur(bgHalf, 2.6f * s, 64)
        val l2 = fastBlur(bgHalf, 6.0f * s, 40)
        val l3 = fastBlur(bgHalf, 11.5f * s, 26)

        val p1 = IntArray(halfW * halfH)
        val p2 = IntArray(halfW * halfH)
        val p3 = IntArray(halfW * halfH)
        l1.getPixels(p1, 0, halfW, 0, 0, halfW, halfH)
        l2.getPixels(p2, 0, halfW, 0, 0, halfW, halfH)
        l3.getPixels(p3, 0, halfW, 0, 0, halfW, halfH)

        // --- 2) Distance field at half resolution ---
        val distSmall = maskToBitmap(dist, workW, workH)
        val distHalfBmp = Bitmap.createScaledBitmap(distSmall, halfW, halfH, true)
        val distHalf = bitmapToGrayFloat(distHalfBmp)

        // --- 3) Depth-band blend of the three layers ---
        val blended = IntArray(halfW * halfH)
        for (i in blended.indices) {
            val d = distHalf[i]
            val w3 = smoothstep(0.42f, 0.72f, d)
            val rest = 1f - w3
            val w2 = rest * smoothstep(0.12f, 0.42f, d)
            val w1 = rest - w2

            val c1 = p1[i]
            val c2 = p2[i]
            val c3 = p3[i]
            blended[i] = (0xFF shl 24) or
                (blendChannel(c1, c2, c3, w1, w2, w3, 16) shl 16) or
                (blendChannel(c1, c2, c3, w1, w2, w3, 8) shl 8) or
                blendChannel(c1, c2, c3, w1, w2, w3, 0)
        }
        val bgBlendedHalf = Bitmap.createBitmap(blended, halfW, halfH, Bitmap.Config.ARGB_8888)
        val bgFull = Bitmap.createScaledBitmap(bgBlendedHalf, w, h, true)

        // --- 4) Cut the subject out of the blurred background ---
        // "keep background" mask: 1 where there is no subject.
        val keepBg = FloatArray(alpha.size) { i -> 1f - alpha[i] }
        val keepSmall = alphaValuesToBitmap(keepBg, workW, workH)
        val keepFull = Bitmap.createScaledBitmap(keepSmall, w, h, true)

        val maskedBg = bgFull.copy(Bitmap.Config.ARGB_8888, true)
        val cutPaint = Paint().apply {
            isFilterBitmap = true
            xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_IN)
        }
        Canvas(maskedBg).drawBitmap(keepFull, null, Rect(0, 0, w, h), cutPaint)

        // --- 5) Sharp subject on top ---
        val output = base.copy(Bitmap.Config.ARGB_8888, true)
        Canvas(output).drawBitmap(maskedBg, 0f, 0f, Paint(Paint.FILTER_BITMAP_FLAG))
        return output
    }

    private fun blendChannel(c1: Int, c2: Int, c3: Int, w1: Float, w2: Float, w3: Float, shift: Int): Int {
        val v1 = ((c1 shr shift) and 0xFF) * w1
        val v2 = ((c2 shr shift) and 0xFF) * w2
        val v3 = ((c3 shr shift) and 0xFF) * w3
        return (v1 + v2 + v3 + 0.5f).toInt().coerceIn(0, 255)
    }

    private fun smoothstep(e0: Float, e1: Float, x: Float): Float = when {
        x <= e0 -> 0f
        x >= e1 -> 1f
        else -> {
            val t = (x - e0) / (e1 - e0)
            t * t * (3f - 2f * t)
        }
    }
}
