package com.devfahim00.netcam.processing

import android.graphics.Bitmap
import com.devfahim00.netcam.util.decodeJpegBytesCapped
import kotlin.math.max

/**
 * Low-light noise reduction ("night mode lite").
 *
 * Strategy — the only way to truly beat noise is to average information:
 *  1. Capture a short burst of frames.
 *  2. Estimate the global translation of every frame against the reference
 *     (pyramid-free strided SAD search — hand-shake between burst frames is
 *     almost purely translational thanks to OIS/EIS).
 *  3. Robust temporal fusion: average pixels that agree with the reference,
 *     reject deviant ones (moving subjects fall back to the reference, so
 *     no ghosts). Averaging N frames gives ~sqrt(N) noise reduction.
 *  4. Edge-aware spatial cleanup: cross-bilateral chroma denoise (kills the
 *     ugly color speckle of night shots) + a gentle flat-region luma pass
 *     that never touches edges or texture.
 */
object NightDenoise {

    /** Resolution ceiling for the multi-frame path (memory + time budget). */
    const val MAX_FUSION_PIXELS = 8_000_000

    /** Average scene luma (0..1) below which we treat the scene as low light. */
    const val LOW_LIGHT_LUMA = 0.235f

    /** Extra frames captured after the reference when the night path kicks in. */
    const val EXTRA_FRAMES = 3

    // ---- luma helpers ----

    private inline fun lumaOf(r: Int, g: Int, b: Int): Int = (r * 77 + g * 151 + b * 28) ushr 8

    /** Grid-sampled mean luminance of a decoded bitmap (0..1). */
    fun averageLuma(src: Bitmap): Float {
        val stepX = max(1, src.width / 48)
        val stepY = max(1, src.height / 36)
        var sum = 0L
        var n = 0L
        var y = 0
        while (y < src.height) {
            var x = 0
            while (x < src.width) {
                val p = src.getPixel(x, y)
                sum += lumaOf((p shr 16) and 0xFF, (p shr 8) and 0xFF, p and 0xFF)
                n++
                x += stepX
            }
            y += stepY
        }
        return if (n == 0L) 1f else (sum.toFloat() / n) / 255f
    }

    fun isLowLightBitmap(src: Bitmap): Boolean = averageLuma(src) < LOW_LIGHT_LUMA

    private fun lumaByteArray(px: IntArray): ByteArray {
        val out = ByteArray(px.size)
        for (i in px.indices) {
            val p = px[i]
            out[i] = lumaOf((p shr 16) and 0xFF, (p shr 8) and 0xFF, p and 0xFF).toByte()
        }
        return out
    }

    // ---- alignment ----

    /**
     * Global translation (dx, dy) of [frameLuma] relative to [refLuma], in
     * pixels. Coarse search ±12 px step 2, then a ±2 refinement, on a strided
     * sample grid — a few dozen milliseconds even at 8 MP.
     */
    private fun estimateShift(refLuma: ByteArray, frameLuma: ByteArray, w: Int, h: Int): IntArray {
        val step = max(8, w / 400)
        var bestDx = 0
        var bestDy = 0
        var bestScore = Long.MAX_VALUE

        fun score(dx: Int, dy: Int): Long {
            var sad = 0L
            var n = 0L
            var y = step
            while (y < h - step) {
                val sy = y + dy
                if (sy in 0 until h) {
                    val row = y * w
                    val srow = sy * w
                    var x = step
                    while (x < w - step) {
                        val sx = x + dx
                        if (sx in 0 until w) {
                            val d = (refLuma[row + x].toInt() and 0xFF) -
                                (frameLuma[srow + sx].toInt() and 0xFF)
                            sad += if (d < 0) -d else d
                            n++
                        }
                        x += step
                    }
                }
                y += step
            }
            return if (n == 0L) Long.MAX_VALUE else (sad * 256) / n
        }

        var dy = -12
        while (dy <= 12) {
            var dx = -12
            while (dx <= 12) {
                val s = score(dx, dy)
                if (s < bestScore) {
                    bestScore = s; bestDx = dx; bestDy = dy
                }
                dx += 2
            }
            dy += 2
        }
        dy = bestDy - 2
        while (dy <= bestDy + 2) {
            var dx = bestDx - 2
            while (dx <= bestDx + 2) {
                if (dx != bestDx || dy != bestDy) {
                    val s = score(dx, dy)
                    if (s < bestScore) {
                        bestScore = s; bestDx = dx; bestDy = dy
                    }
                }
                dx++
            }
            dy++
        }
        return intArrayOf(bestDx, bestDy)
    }

    // ---- temporal fusion ----

    /**
     * Decodes the burst one frame at a time (peak memory stays around
     * ~16 bytes/pixel), aligns each frame to the reference and produces a
     * rejection-weighted average followed by the spatial cleanup passes.
     *
     * @param shots JPEG bytes + rotation degrees, shots[0] is the reference.
     * @param cap max pixel count per decoded frame.
     */
    fun fuseFromJpegs(shots: List<Pair<ByteArray, Int>>, cap: Int): Bitmap? {
        if (shots.isEmpty()) return null
        val ref = decodeJpegBytesCapped(shots[0].first, shots[0].second, cap) ?: return null
        val w = ref.width
        val h = ref.height
        val refPx = IntArray(w * h).also { ref.getPixels(it, 0, w, 0, 0, w, h) }
        ref.recycle()
        val refLuma = lumaByteArray(refPx)

        val accR = ShortArray(w * h)
        val accG = ShortArray(w * h)
        val accB = ShortArray(w * h)
        val cnt = ByteArray(w * h)
        for (i in refPx.indices) {
            val p = refPx[i]
            accR[i] = ((p shr 16) and 0xFF).toShort()
            accG[i] = ((p shr 8) and 0xFF).toShort()
            accB[i] = (p and 0xFF).toShort()
            cnt[i] = 1
        }

        for (k in 1 until shots.size) {
            val bmp = decodeJpegBytesCapped(shots[k].first, shots[k].second, cap)
            if (bmp == null || bmp.width != w || bmp.height != h) {
                bmp?.recycle()
                continue
            }
            val px = IntArray(w * h).also { bmp.getPixels(it, 0, w, 0, 0, w, h) }
            bmp.recycle()
            val frameLuma = lumaByteArray(px)
            val shift = estimateShift(refLuma, frameLuma, w, h)
            val dx = shift[0]
            val dy = shift[1]

            var y = 0
            while (y < h) {
                val sy = y + dy
                if (sy in 0 until h) {
                    val row = y * w
                    val srow = sy * w
                    var x = 0
                    while (x < w) {
                        val sx = x + dx
                        if (sx in 0 until w) {
                            val i = row + x
                            val p = px[srow + sx]
                            val l = lumaOf((p shr 16) and 0xFF, (p shr 8) and 0xFF, p and 0xFF)
                            val d = l - (refLuma[i].toInt() and 0xFF)
                            if (d >= -26 && d <= 26 && cnt[i] < 5) {
                                accR[i] = (accR[i] + ((p shr 16) and 0xFF)).toShort()
                                accG[i] = (accG[i] + ((p shr 8) and 0xFF)).toShort()
                                accB[i] = (accB[i] + (p and 0xFF)).toShort()
                                cnt[i] = (cnt[i] + 1).toByte()
                            }
                        }
                        x++
                    }
                }
                y++
            }
        }

        val out = IntArray(w * h)
        for (i in out.indices) {
            val c = cnt[i].toInt()
            out[i] = if (c <= 1) {
                refPx[i]
            } else {
                (0xFF shl 24) or
                    ((accR[i] / c) shl 16) or
                    ((accG[i] / c) shl 8) or
                    (accB[i] / c)
            }
        }
        denoiseInPlace(out, w, h)
        return Bitmap.createBitmap(out, w, h, Bitmap.Config.ARGB_8888)
    }

    /** Single-frame fallback: edge-aware chroma + flat-region luma denoise. */
    fun denoiseSingle(src: Bitmap): Bitmap {
        val w = src.width
        val h = src.height
        val px = IntArray(w * h).also { src.getPixels(it, 0, w, 0, 0, w, h) }
        denoiseInPlace(px, w, h)
        return Bitmap.createBitmap(px, w, h, Bitmap.Config.ARGB_8888)
    }

    // ---- spatial denoise ----

    private fun denoiseInPlace(px: IntArray, w: Int, h: Int) {
        if (w < 8 || h < 8 || px.size != w * h) return
        val n = w * h
        val luma = ByteArray(n)
        for (i in 0 until n) {
            val p = px[i]
            luma[i] = lumaOf((p shr 16) and 0xFF, (p shr 8) and 0xFF, p and 0xFF).toByte()
        }

        // Pass 1 — chroma: cross-bilateral smoothing of (r-g, b-g) guided by
        // luma edges. Color speckle dies, detail in luma is untouched.
        val chroma = px.copyOf()
        var y0 = 2
        while (y0 < h - 2) {
            var x0 = 2
            while (x0 < w - 2) {
                val i = y0 * w + x0
                val yc = luma[i].toInt() and 0xFF
                val pc = px[i]
                val gc = (pc shr 8) and 0xFF
                var wSum = 1f
                var rgSum = (((pc shr 16) and 0xFF) - gc).toFloat()
                var bgSum = ((pc and 0xFF) - gc).toFloat()
                for (t in 0 until 4) {
                    val j = when (t) {
                        0 -> i - 2
                        1 -> i + 2
                        2 -> i - 2 * w
                        else -> i + 2 * w
                    }
                    val pj = px[j]
                    val yj = luma[j].toInt() and 0xFF
                    val dl = yj - yc
                    val wt = 1f / (1f + dl * dl * 0.02f)
                    val gj = (pj shr 8) and 0xFF
                    wSum += wt
                    rgSum += wt * (((pj shr 16) and 0xFF) - gj)
                    bgSum += wt * ((pj and 0xFF) - gj)
                }
                val rg = (rgSum / wSum).toInt().coerceIn(-255, 255)
                val bg = (bgSum / wSum).toInt().coerceIn(-255, 255)
                val r = (gc + rg).coerceIn(0, 255)
                val b = (gc + bg).coerceIn(0, 255)
                chroma[i] = (0xFF shl 24) or (r shl 16) or (gc shl 8) or b
                x0++
            }
            y0++
        }
        System.arraycopy(chroma, 0, px, 0, n)

        // Pass 2 — luma: 3x3 mean blended at 40% but only inside locally flat
        // patches (luma spread <= 12). Edges / texture / skin detail survive.
        val smooth = px.copyOf()
        var y1 = 1
        while (y1 < h - 1) {
            var x1 = 1
            while (x1 < w - 1) {
                val i = y1 * w + x1
                val yc = luma[i].toInt() and 0xFF
                var minL = 255
                var maxL = 0
                var sr = 0
                var sg = 0
                var sb = 0
                for (t in 0 until 9) {
                    val j = when (t) {
                        0 -> i - w - 1
                        1 -> i - w
                        2 -> i - w + 1
                        3 -> i - 1
                        4 -> i
                        5 -> i + 1
                        6 -> i + w - 1
                        7 -> i + w
                        else -> i + w + 1
                    }
                    val yv = luma[j].toInt() and 0xFF
                    if (yv < minL) minL = yv
                    if (yv > maxL) maxL = yv
                    val pj = px[j]
                    sr += (pj shr 16) and 0xFF
                    sg += (pj shr 8) and 0xFF
                    sb += pj and 0xFF
                }
                if (maxL - minL <= 12) {
                    val mr = sr / 9
                    val mg = sg / 9
                    val mb = sb / 9
                    val p = px[i]
                    val r = ((p shr 16) and 0xFF) + ((mr - ((p shr 16) and 0xFF)) * 2 / 5)
                    val g = ((p shr 8) and 0xFF) + ((mg - ((p shr 8) and 0xFF)) * 2 / 5)
                    val b = (p and 0xFF) + ((mb - (p and 0xFF)) * 2 / 5)
                    smooth[i] = (0xFF shl 24) or (r.coerceIn(0, 255) shl 16) or
                        (g.coerceIn(0, 255) shl 8) or b.coerceIn(0, 255)
                }
                x1++
            }
            y1++
        }
        System.arraycopy(smooth, 0, px, 0, n)
    }
}
