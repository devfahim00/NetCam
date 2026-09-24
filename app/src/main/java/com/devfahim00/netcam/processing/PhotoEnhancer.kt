package com.devfahim00.netcam.processing

import android.graphics.Bitmap
import com.devfahim00.netcam.util.bitmapToGrayFloat
import com.devfahim00.netcam.util.fastBlur
import kotlin.math.max
import kotlin.math.min

/**
 * "Social-ready" color pipeline applied to every capture — the look that
 * makes stock camera photos feel flat compared to GCam:
 *
 *  1. Mild auto white balance (gray-world, tightly clamped).
 *  2. Local tone mapping: shadows opened / highlights rolled off relative to
 *     a blurred luminance base, mimicking HDR+ micro-contrast.
 *  3. Vibrance (boosts muted colors far more than saturated ones, so skin
 *     stays natural) plus a gentle saturation lift.
 *  4. Soft S-curve contrast with a subtle shadow lift.
 *  5. Luma-only unsharp mask for crisp, share-ready detail.
 *
 * All work happens on a single pixel array with LUTs and row caching, so a
 * 12 MP photo processes in well under a second on mid-range silicon.
 */
object PhotoEnhancer {

    private const val SATURATION = 0.13f
    private const val VIBRANCE = 0.32f
    private const val S_CURVE_MIX = 0.32f
    private const val SHADOW_LIFT = 0.045f
    private const val LOCAL_GAIN = 0.22f
    private const val SHARPEN_PHOTO = 0.30f
    private const val SHARPEN_PORTRAIT = 0.20f

    /** @param portrait true → slightly gentler sharpening (subject already pops). */
    fun enhance(src: Bitmap, portrait: Boolean): Bitmap {
        val w = src.width
        val h = src.height
        if (w < 2 || h < 2) return src

        val pixels = IntArray(w * h)
        src.getPixels(pixels, 0, w, 0, 0, w, h)

        val (gr, gg, gb) = whiteBalanceGains(pixels)
        val baseHalf = localLumaBase(src, w, h)
        val lut = buildToneLut()

        toneAndColorPass(pixels, w, h, lut, baseHalf, gr, gg, gb)
        sharpenPass(pixels, w, h, if (portrait) SHARPEN_PORTRAIT else SHARPEN_PHOTO)

        return Bitmap.createBitmap(pixels, w, h, Bitmap.Config.ARGB_8888)
    }

    // ------------------------------------------------------------------ tone

    private fun buildToneLut(): IntArray {
        val lut = IntArray(256)
        for (i in 0 until 256) {
            val x = i / 255f
            // Shadow lift.
            var v = x + SHADOW_LIFT * (1f - x) * (1f - x)
            // Mild S-curve: blend toward smoothstep.
            val s = v * v * (3f - 2f * v)
            v += S_CURVE_MIX * (s - v)
            // Gentle highlight rolloff.
            v = 1f - Math.pow((1f - v).toDouble(), 1.04).toFloat()
            lut[i] = (v.coerceIn(0f, 1f) * 255f + 0.5f).toInt()
        }
        return lut
    }

    private fun toneAndColorPass(
        pixels: IntArray,
        w: Int,
        h: Int,
        lut: IntArray,
        baseHalf: FloatArray?,
        gr: Float,
        gg: Float,
        gb: Float
    ) {
        val halfW = max(1, w / 2)
        val halfH = max(1, h / 2)

        for (y in 0 until h) {
            val row = y * w
            // Bilinear y setup for the local-luma base (half resolution).
            val fy = (y + 0.5f) / 2f - 0.5f
            val by0 = fy.toInt()
            val y0 = if (baseHalf != null) by0.coerceIn(0, halfH - 1) else 0
            val y1 = if (baseHalf != null) (by0 + 1).coerceIn(0, halfH - 1) else 0
            val ty = (fy - by0).coerceIn(0f, 1f)

            for (x in 0 until w) {
                val idx = row + x
                val p = pixels[idx]

                val r = (p shr 16) and 0xFF
                val g = (p shr 8) and 0xFF
                val b = p and 0xFF

                // White-balanced channels + luma.
                val rw = r * gr
                val gw = g * gg
                val bw = b * gb
                val lumaW = 0.299f * rw + 0.587f * gw + 0.114f * bw
                val yNorm = (lumaW / 255f).coerceIn(0f, 1f)

                // Local tone mapping against the blurred luma base.
                val outY: Int = if (baseHalf != null) {
                    val fx = (x + 0.5f) / 2f - 0.5f
                    val bx0 = fx.toInt()
                    val x0 = bx0.coerceIn(0, halfW - 1)
                    val x1 = (bx0 + 1).coerceIn(0, halfW - 1)
                    val tx = (fx - bx0).coerceIn(0f, 1f)

                    val r0 = y0 * halfW
                    val r1 = y1 * halfW
                    val top = baseHalf[r0 + x0] + tx * (baseHalf[r0 + x1] - baseHalf[r0 + x0])
                    val bot = baseHalf[r1 + x0] + tx * (baseHalf[r1 + x1] - baseHalf[r1 + x0])
                    val base = top + ty * (bot - top)

                    val adj = (yNorm + LOCAL_GAIN * (yNorm - base)).coerceIn(0f, 1f)
                    lut[(adj * 255f + 0.5f).toInt()]
                } else {
                    lut[(yNorm * 255f + 0.5f).toInt()]
                }

                // Vibrance + saturation around the new luma.
                val mx = max(r, max(g, b))
                val mn = min(r, min(g, b))
                val satPix = if (mx <= 0) 0f else (mx - mn) / mx.toFloat()
                val chromaScale = 1f + SATURATION + VIBRANCE * (1f - satPix)

                val outR = outY + (rw - lumaW) * chromaScale
                val outG = outY + (gw - lumaW) * chromaScale
                val outB = outY + (bw - lumaW) * chromaScale

                pixels[idx] = (0xFF shl 24) or
                    (outR.toInt().coerceIn(0, 255) shl 16) or
                    (outG.toInt().coerceIn(0, 255) shl 8) or
                    outB.toInt().coerceIn(0, 255)
            }
        }
    }

    // -------------------------------------------------------------- sharpen

    /**
     * In-place 5-tap luma unsharp mask. Original luma of the rows above and
     * below is cached before any pixel is written, so no full-size extra
     * buffer is needed.
     */
    private fun sharpenPass(pixels: IntArray, w: Int, h: Int, amount: Float) {
        if (w < 3 || h < 3) return

        fun lumaInto(y: Int, out: ByteArray) {
            val row = y * w
            for (x in 0 until w) {
                val p = pixels[row + x]
                out[x] = ((77 * ((p shr 16) and 0xFF) + 150 * ((p shr 8) and 0xFF) +
                    29 * (p and 0xFF)) shr 8).toByte()
            }
        }

        var prev = ByteArray(w) // luma of row y-1 (clamped)
        var cur = ByteArray(w)  // luma of row y
        var next = ByteArray(w) // luma of row y+1 (clamped)

        lumaInto(0, cur)
        System.arraycopy(cur, 0, next, 0, w)

        for (y in 0 until h) {
            val prevRow = if (y == 0) cur else prev
            val row = y * w
            for (x in 0 until w) {
                val xm = if (x > 0) x - 1 else x
                val xp = if (x < w - 1) x + 1 else x
                val center = cur[x].toInt() and 0xFF
                val avg = (
                    (prevRow[x].toInt() and 0xFF) + (next[x].toInt() and 0xFF) +
                        (cur[xm].toInt() and 0xFF) + (cur[xp].toInt() and 0xFF)
                    ) * 0.25f
                val delta = (center - avg) * amount

                val p = pixels[row + x]
                val r = (((p shr 16) and 0xFF) + delta).toInt().coerceIn(0, 255)
                val g = (((p shr 8) and 0xFF) + delta).toInt().coerceIn(0, 255)
                val b = ((p and 0xFF) + delta).toInt().coerceIn(0, 255)
                pixels[row + x] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
            }

            // Rotate buffers: cur becomes prev, next becomes cur, and the
            // recycled buffer is refilled with the luma of row y+2 (still
            // untouched pixels).
            val tmp = prev
            prev = cur
            cur = next
            next = tmp
            if (y + 2 < h) {
                lumaInto(y + 2, next)
            } else {
                System.arraycopy(cur, 0, next, 0, w)
            }
        }
    }

    // ------------------------------------------------------------------- awb

    private fun whiteBalanceGains(pixels: IntArray): Triple<Float, Float, Float> {
        val stride = max(1, pixels.size / 20_000)
        var sr = 0f
        var sg = 0f
        var sb = 0f
        var n = 0f
        var i = 0
        while (i < pixels.size) {
            val p = pixels[i]
            sr += (p shr 16) and 0xFF
            sg += (p shr 8) and 0xFF
            sb += p and 0xFF
            n += 1f
            i += stride
        }
        if (n <= 0f) return Triple(1f, 1f, 1f)
        val mr = sr / n
        val mg = sg / n
        val mb = sb / n
        val gray = (mr + mg + mb) / 3f
        fun gain(m: Float): Float = if (m <= 1f) 1f else (gray / m).coerceIn(0.94f, 1.06f)
        return Triple(gain(mr), gain(mg), gain(mb))
    }

    // ------------------------------------------------------------ local base

    /** Blurred luminance at half resolution (guide for local tone mapping). */
    private fun localLumaBase(src: Bitmap, w: Int, h: Int): FloatArray? {
        return try {
            val smallW = max(1, w / 8)
            val smallH = max(1, h / 8)
            val small = Bitmap.createScaledBitmap(src, smallW, smallH, true)
            val blurred = fastBlur(small, 2f, 16)
            val halfW = max(1, w / 2)
            val halfH = max(1, h / 2)
            val half = Bitmap.createScaledBitmap(blurred, halfW, halfH, true)
            bitmapToGrayFloat(half)
        } catch (t: Throwable) {
            null
        }
    }
}
