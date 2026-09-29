package com.devfahim00.netcam.processing

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import com.devfahim00.netcam.util.alphaValuesToBitmap
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Real depth-of-field portrait rendering.
 *
 * The old pipeline blurred "everything that is not inside the segmentation
 * mask", so any part of the subject the mask missed (a hand, the second
 * person, half of an object, the table the fan stands on) was blurred too.
 * This renderer decides sharpness from *depth*, like a real lens:
 *
 *  1. MiDaS depth (256px) is upsampled with a guided filter so depth edges
 *     snap onto the real image edges (hair, fingers, fan blades).
 *  2. The focus plane is the depth of the subject's head/shoulder band.
 *     Everything at that depth stays sharp — even if segmentation missed it.
 *  3. Circle-of-confusion grows smoothly with distance from the focus plane,
 *     in both directions (far background AND near foreground blur).
 *  4. The mask only *protects* the subject (hair-fine edges); it no longer
 *     decides what is blurred.
 *  5. Blur is rendered in layers with occlusion-aware normalised convolution:
 *     sharp pixels never leak into blurry ones, so there is no halo.
 */
object DepthBokeh {

    private val RADII = floatArrayOf(0f, 2.5f, 5f, 8.5f, 13f, 19f, 27f)
    private const val TOLERANCE = 0.06f      // in-focus depth slack
    private const val RAMP = 0.65f           // depth distance for full blur
    private const val FOREGROUND_SOFT = 0.75f
    private const val BLOOM_THRESHOLD = 0.72f
    private const val BLOOM_GAIN = 0.45f

    /**
     * @param base full-resolution photo.
     * @param working downscaled copy the mask was computed on.
     * @param mask refined subject mask at working size (0..1).
     * @param depth256 output of [DepthEstimator.estimate].
     * @param strength user blur strength 0..1.5.
     * @return the finished bitmap, or null if it could not be rendered.
     */
    fun render(
        base: Bitmap,
        working: Bitmap,
        mask: FloatArray,
        depth256: FloatArray,
        strength: Float
    ): Bitmap? {
        if (strength <= 0.02f) return null
        val w = working.width
        val h = working.height
        val n = w * h
        if (mask.size != n) return null
        val side = max(w, h)
        val sScale = side / 1280f

        // ---- pixels -------------------------------------------------------
        val px = IntArray(n)
        working.getPixels(px, 0, w, 0, 0, w, h)
        val gray = FloatArray(n)
        val chR = FloatArray(n)
        val chG = FloatArray(n)
        val chB = FloatArray(n)
        for (i in 0 until n) {
            val p = px[i]
            val r = ((p shr 16) and 0xFF).toFloat()
            val g = ((p shr 8) and 0xFF).toFloat()
            val b = (p and 0xFF).toFloat()
            chR[i] = r
            chG[i] = g
            chB[i] = b
            gray[i] = (0.299f * r + 0.587f * g + 0.114f * b) / 255f
        }

        // ---- 1) depth upsampled onto real edges ---------------------------
        var depth = resizeBilinear(depth256, DepthEstimator.SIZE, DepthEstimator.SIZE, w, h)
        depth = MaskRefiner.guidedFilter(gray, depth, w, h, max(4, side / 40), 1.0e-3f)
        depth = MaskRefiner.guidedFilter(gray, depth, w, h, max(2, side / 160), 1.0e-4f)

        // ---- 2) focus plane ----------------------------------------------
        val focus = focusDepth(mask, depth, w, h)

        // ---- 3) circle of confusion ---------------------------------------
        val maxCoc = 17f * strength * sScale
        val coc01 = FloatArray(n)
        for (i in 0 until n) {
            val delta = depth[i] - focus
            val ad = abs(delta)
            val a = ad - TOLERANCE
            var c = if (a <= 0f) 0f else min(a / RAMP, 1f)
            if (delta > 0f) c *= FOREGROUND_SOFT
            // Mask protects the subject, but only where it is also at (about)
            // the focus depth — a knee pushed toward the lens still blurs.
            val prot = mask[i] * smoothstep(0f, 0.10f, 0.20f - ad)
            coc01[i] = c * (1f - prot)
        }
        val cocFiltered = MaskRefiner.guidedFilter(gray, coc01, w, h, 4, 1.0e-4f)
        val coc = FloatArray(n)
        for (i in 0 until n) coc[i] = cocFiltered[i].coerceIn(0f, 1f) * maxCoc

        // ---- 4) blur level per pixel -------------------------------------
        val radii = FloatArray(RADII.size) { RADII[it] * strength * sScale }
        val top = radii.size - 1
        val lvl = FloatArray(n)
        var maxNeeded = 0
        for (i in 0 until n) {
            val c = coc[i]
            var k = 0
            while (k < top && c > radii[k + 1]) k++
            val v = if (k >= top) top.toFloat()
            else k + (c - radii[k]) / max(radii[k + 1] - radii[k], 1e-4f)
            lvl[i] = v
            val need = if (v - v.toInt() > 0.001f) v.toInt() + 1 else v.toInt()
            if (need > maxNeeded) maxNeeded = need
        }

        // ---- 5) layered, occlusion-aware blur ------------------------------
        val outR = FloatArray(n)
        val outG = FloatArray(n)
        val outB = FloatArray(n)
        for (i in 0 until n) {
            val w0 = max(0f, 1f - lvl[i])
            outR[i] = chR[i] * w0
            outG[i] = chG[i] * w0
            outB[i] = chB[i] * w0
        }

        if (maxNeeded >= 1) {
            // Bloom: push highlights up before blurring so lights become discs.
            for (i in 0 until n) {
                val lum = gray[i]
                if (lum > BLOOM_THRESHOLD) {
                    val boost = 1f + (lum - BLOOM_THRESHOLD) / (1f - BLOOM_THRESHOLD) * BLOOM_GAIN
                    chR[i] *= boost
                    chG[i] *= boost
                    chB[i] *= boost
                }
            }

            val blurrer = Blurrer(w, h)
            var prevR = FloatArray(n)
            var prevG = FloatArray(n)
            var prevB = FloatArray(n)
            for (i in 0 until n) {
                // L0 (sharp) approximated by the bloom-free original.
                val p = px[i]
                prevR[i] = ((p shr 16) and 0xFF).toFloat()
                prevG[i] = ((p shr 8) and 0xFF).toFloat()
                prevB[i] = (p and 0xFF).toFloat()
            }
            var curR = FloatArray(n)
            var curG = FloatArray(n)
            var curB = FloatArray(n)
            val wk = FloatArray(n)
            val den = FloatArray(n)
            val tmp = FloatArray(n)
            val srcTmp = FloatArray(n)

            for (k in 1..maxNeeded) {
                val boxR = max(1, (radii[k] / 1.6f).roundToInt())
                val thr = if (k > 1) 0.85f * radii[k - 1] else 0f

                if (k == 1) {
                    blurrer.blur(chR, curR, boxR)
                    blurrer.blur(chG, curG, boxR)
                    blurrer.blur(chB, curB, boxR)
                } else {
                    // Only pixels at least as blurry as the previous level
                    // may contribute: sharp pixels never bleed into blur.
                    for (i in 0 until n) wk[i] = if (coc[i] >= thr) 1f else 0f
                    blurrer.blur(wk, den, boxR)

                    for (i in 0 until n) srcTmp[i] = chR[i] * wk[i]
                    blurrer.blur(srcTmp, tmp, boxR)
                    mixLayer(curR, tmp, den, prevR, n)

                    for (i in 0 until n) srcTmp[i] = chG[i] * wk[i]
                    blurrer.blur(srcTmp, tmp, boxR)
                    mixLayer(curG, tmp, den, prevG, n)

                    for (i in 0 until n) srcTmp[i] = chB[i] * wk[i]
                    blurrer.blur(srcTmp, tmp, boxR)
                    mixLayer(curB, tmp, den, prevB, n)
                }

                for (i in 0 until n) {
                    val wl = 1f - abs(lvl[i] - k)
                    if (wl > 0f) {
                        outR[i] += curR[i] * wl
                        outG[i] += curG[i] * wl
                        outB[i] += curB[i] * wl
                    }
                }

                val sR = prevR; prevR = curR; curR = sR
                val sG = prevG; prevG = curG; curG = sG
                val sB = prevB; prevB = curB; curB = sB
            }
        }

        // ---- 6) composite onto the full-resolution photo -------------------
        val outPixels = IntArray(n)
        for (i in 0 until n) {
            val r = outR[i].roundToInt().coerceIn(0, 255)
            val g = outG[i].roundToInt().coerceIn(0, 255)
            val b = outB[i].roundToInt().coerceIn(0, 255)
            outPixels[i] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
        }
        val blurredSmall = Bitmap.createBitmap(outPixels, w, h, Bitmap.Config.ARGB_8888)

        // Where the CoC is ~0 the untouched full-res pixels win.
        val edge = max(0.75f, 0.5f * radii[1])
        val blurWeight = FloatArray(n) { smoothstep(0f, edge, coc[it]) }

        val fw = base.width
        val fh = base.height
        val blurredFull = Bitmap.createScaledBitmap(blurredSmall, fw, fh, true)
        val bg = blurredFull.copy(Bitmap.Config.ARGB_8888, true)
        val weightSmall = alphaValuesToBitmap(blurWeight, w, h)
        val weightFull = Bitmap.createScaledBitmap(weightSmall, fw, fh, true)
        val cut = Paint().apply {
            isFilterBitmap = true
            xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_IN)
        }
        Canvas(bg).drawBitmap(weightFull, 0f, 0f, cut)

        val output = base.copy(Bitmap.Config.ARGB_8888, true)
        Canvas(output).drawBitmap(bg, 0f, 0f, Paint(Paint.FILTER_BITMAP_FLAG))
        return output
    }

    // ------------------------------------------------------------ internals

    /** cur = num/den where den is trustworthy, else the previous (less blurry) level. */
    private fun mixLayer(cur: FloatArray, num: FloatArray, den: FloatArray, prev: FloatArray, n: Int) {
        for (i in 0 until n) {
            val d = den[i]
            val a = (d / 0.15f).coerceIn(0f, 1f)
            val v = if (d > 1e-4f) num[i] / d else prev[i]
            cur[i] = (v * a + prev[i] * (1f - a)).coerceIn(0f, 255f * 2f)
        }
    }

    /**
     * Focus depth = median depth of the subject's head band (top 40% of the
     * solid mask). Using the whole mask would pull the plane toward limbs or
     * legs that point at the camera and blur the face.
     */
    private fun focusDepth(mask: FloatArray, depth: FloatArray, w: Int, h: Int): Float {
        val n = w * h
        val solid = FloatArray(n) { if (mask[it] > 0.9f) 1f else 0f }
        val eroded = MaskRefiner.boxFilter(solid, w, h, 4)
        var core = BooleanArray(n) { eroded[it] > 0.995f }
        var count = core.count { it }
        if (count < 200) {
            core = BooleanArray(n) { mask[it] > 0.5f }
            count = core.count { it }
        }
        if (count == 0) return median(depth, n) { true }

        var topRow = -1
        var bottomRow = -1
        for (y in 0 until h) {
            var any = false
            val row = y * w
            for (x in 0 until w) if (core[row + x]) { any = true; break }
            if (any) {
                if (topRow < 0) topRow = y
                bottomRow = y
            }
        }
        val bandBottom = min(h - 1, topRow + max(8, ((bottomRow - topRow) * 0.40f).toInt()))
        var bandCount = 0
        for (y in topRow..bandBottom) {
            val row = y * w
            for (x in 0 until w) if (core[row + x]) bandCount++
        }
        return if (bandCount >= 150) {
            val vals = FloatArray(bandCount)
            var j = 0
            for (y in topRow..bandBottom) {
                val row = y * w
                for (x in 0 until w) if (core[row + x]) vals[j++] = depth[row + x]
            }
            vals.sort()
            vals[vals.size / 2]
        } else {
            median(depth, n) { core[it] }
        }
    }

    private inline fun median(v: FloatArray, n: Int, pick: (Int) -> Boolean): Float {
        var c = 0
        for (i in 0 until n) if (pick(i)) c++
        if (c == 0) return 0.5f
        val vals = FloatArray(c)
        var j = 0
        for (i in 0 until n) if (pick(i)) vals[j++] = v[i]
        vals.sort()
        return vals[c / 2]
    }

    private fun smoothstep(e0: Float, e1: Float, x: Float): Float {
        if (x <= e0) return 0f
        if (x >= e1) return 1f
        val t = (x - e0) / (e1 - e0)
        return t * t * (3f - 2f * t)
    }

    private fun resizeBilinear(src: FloatArray, sw: Int, sh: Int, dw: Int, dh: Int): FloatArray {
        val out = FloatArray(dw * dh)
        val xs0 = IntArray(dw)
        val xs1 = IntArray(dw)
        val xw = FloatArray(dw)
        val xr = sw.toFloat() / dw
        for (x in 0 until dw) {
            val fx = ((x + 0.5f) * xr - 0.5f).coerceIn(0f, (sw - 1).toFloat())
            xs0[x] = fx.toInt()
            xs1[x] = min(xs0[x] + 1, sw - 1)
            xw[x] = fx - xs0[x]
        }
        val yr = sh.toFloat() / dh
        for (y in 0 until dh) {
            val fy = ((y + 0.5f) * yr - 0.5f).coerceIn(0f, (sh - 1).toFloat())
            val y0 = fy.toInt()
            val y1 = min(y0 + 1, sh - 1)
            val wy = fy - y0
            val r0 = y0 * sw
            val r1 = y1 * sw
            val o = y * dw
            for (x in 0 until dw) {
                val a = src[r0 + xs0[x]] * (1f - xw[x]) + src[r0 + xs1[x]] * xw[x]
                val b = src[r1 + xs0[x]] * (1f - xw[x]) + src[r1 + xs1[x]] * xw[x]
                out[o + x] = a * (1f - wy) + b * wy
            }
        }
        return out
    }

    /** Edge-clamped 3-pass box blur (~Gaussian) on a single float plane. */
    private class Blurrer(private val w: Int, private val h: Int) {
        private val tmp = FloatArray(w * h)
        private val colSum = FloatArray(w)

        fun blur(src: FloatArray, dst: FloatArray, r: Int) {
            boxH(src, tmp, r)
            boxV(tmp, dst, r)
            repeat(2) {
                boxH(dst, tmp, r)
                boxV(tmp, dst, r)
            }
        }

        private fun boxH(src: FloatArray, dst: FloatArray, r: Int) {
            val inv = 1f / (2 * r + 1)
            for (y in 0 until h) {
                val base = y * w
                var sum = 0f
                for (k in -r..r) sum += src[base + k.coerceIn(0, w - 1)]
                for (x in 0 until w) {
                    dst[base + x] = sum * inv
                    sum += src[base + min(x + r + 1, w - 1)] - src[base + max(x - r, 0)]
                }
            }
        }

        private fun boxV(src: FloatArray, dst: FloatArray, r: Int) {
            val inv = 1f / (2 * r + 1)
            java.util.Arrays.fill(colSum, 0f)
            for (k in -r..r) {
                val row = k.coerceIn(0, h - 1) * w
                for (x in 0 until w) colSum[x] += src[row + x]
            }
            for (y in 0 until h) {
                val o = y * w
                for (x in 0 until w) dst[o + x] = colSum[x] * inv
                val addRow = min(y + r + 1, h - 1) * w
                val remRow = max(y - r, 0) * w
                for (x in 0 until w) colSum[x] += src[addRow + x] - src[remRow + x]
            }
        }
    }
}
