package com.devfahim00.netcam.processing

import android.graphics.Bitmap
import com.devfahim00.netcam.util.bitmapToGrayFloat
import com.devfahim00.netcam.util.maskToBitmap
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min

/**
 * Software HDR: exposure fusion (Mertens-style) of a 3-frame bracket
 * (EV -2 / 0 / +2). Every pixel picks the best-exposed value across the
 * bracket, weighted by:
 *
 *  - well-exposedness (Gaussian around mid-gray),
 *  - local contrast (gradient magnitude), and
 *  - a ghost penalty against the reference frame that suppresses motion
 *    artifacts when the phone or subject moved between frames.
 *
 * Weights are computed at a small scale and bilinearly upsampled, so the
 * fusion pass is a single linear sweep over the full-resolution pixels.
 */
object HdrFusion {

    /** Fused output cap — keeps the 3-frame memory footprint sane. */
    const val MAX_FUSION_PIXELS = 8_000_000

    private const val WEIGHT_SIDE = 256
    private const val SIGMA_EXPOSED = 0.18f

    /**
     * @param frames bracket images; [0] is the reference (EV 0) exposure.
     * @return fused bitmap, or null when fusion is not possible.
     */
    fun fuse(frames: List<Bitmap>): Bitmap? {
        if (frames.size < 2) return null
        val w = frames[0].width
        val h = frames[0].height
        for (f in frames) {
            if (f.width != w || f.height != h) return null
        }
        if (w < 2 || h < 2) return null

        // --- 1) Weights at small scale -------------------------------
        val smallW = if (w >= h) WEIGHT_SIDE else max(2, (WEIGHT_SIDE * w / h))
        val smallH = if (w >= h) max(2, (WEIGHT_SIDE * h / w)) else WEIGHT_SIDE
        val smalls = frames.map { Bitmap.createScaledBitmap(it, smallW, smallH, true) }
        val grays = smalls.map { bitmapToGrayFloat(it) }
        val refGray = grays[0]

        val weightsSmall = Array(frames.size) { f ->
            FloatArray(smallW * smallH) { i ->
                val l = grays[f][i]
                val dx = l - (if (i % smallW == 0) l else grays[f][i - 1])
                val dy = l - (if (i < smallW) l else grays[f][i - smallW])
                val grad = min(1f, abs(dx) + abs(dy))
                val exposed = exp(-((l - 0.5f) * (l - 0.5f)) / (2f * SIGMA_EXPOSED * SIGMA_EXPOSED))
                var weight = exposed * (0.25f + 2.5f * grad)
                if (f > 0) {
                    // Ghost penalty: pixels that moved vs the reference get less weight.
                    val diff = abs(l - refGray[i])
                    weight *= 1f / (1f + 10f * diff)
                }
                weight
            }
        }

        // --- 2) Upsample weights to half resolution -------------------
        val halfW = max(1, w / 2)
        val halfH = max(1, h / 2)
        val weightsHalf = weightsSmall.map { ws ->
            val smallBmp = maskToBitmap(ws, smallW, smallH)
            val halfBmp = Bitmap.createScaledBitmap(smallBmp, halfW, halfH, true)
            bitmapToGrayFloat(halfBmp)
        }

        // --- 3) Full-resolution weighted fusion -----------------------
        val framePixels = frames.map { f ->
            IntArray(w * h).also { f.getPixels(it, 0, w, 0, 0, w, h) }
        }
        val out = framePixels[0]
        val nFrames = frames.size

        // Reusable per-pixel accumulators (avoid per-iteration allocations).
        val wr = FloatArray(nFrames)
        val wg = FloatArray(nFrames)
        val wb = FloatArray(nFrames)
        val wts = FloatArray(nFrames)

        for (y in 0 until h) {
            val row = y * w
            val fy = (y + 0.5f) / 2f - 0.5f
            val by0 = fy.toInt()
            val wy0 = by0.coerceIn(0, halfH - 1)
            val wy1 = (by0 + 1).coerceIn(0, halfH - 1)
            val ty = (fy - by0).coerceIn(0f, 1f)

            for (x in 0 until w) {
                val idx = row + x
                val fx = (x + 0.5f) / 2f - 0.5f
                val bx0 = fx.toInt()
                val wx0 = bx0.coerceIn(0, halfW - 1)
                val wx1 = (bx0 + 1).coerceIn(0, halfW - 1)
                val tx = (fx - bx0).coerceIn(0f, 1f)

                var wSum = 1e-4f
                for (f in 0 until nFrames) {
                    val base = weightsHalf[f]
                    val r0 = wy0 * halfW
                    val r1 = wy1 * halfW
                    val top = base[r0 + wx0] + tx * (base[r0 + wx1] - base[r0 + wx0])
                    val bot = base[r1 + wx0] + tx * (base[r1 + wx1] - base[r1 + wx0])
                    val wt = top + ty * (bot - top)
                    wts[f] = wt
                    wSum += wt

                    val p = framePixels[f][idx]
                    wr[f] = wt * ((p shr 16) and 0xFF)
                    wg[f] = wt * ((p shr 8) and 0xFF)
                    wb[f] = wt * (p and 0xFF)
                }

                var r = 0f
                var g = 0f
                var b = 0f
                for (f in 0 until nFrames) {
                    r += wr[f]
                    g += wg[f]
                    b += wb[f]
                }
                out[idx] = (0xFF shl 24) or
                    ((r / wSum + 0.5f).toInt().coerceIn(0, 255) shl 16) or
                    ((g / wSum + 0.5f).toInt().coerceIn(0, 255) shl 8) or
                    (b / wSum + 0.5f).toInt().coerceIn(0, 255)
            }
        }

        return Bitmap.createBitmap(out, w, h, Bitmap.Config.ARGB_8888)
    }
}
