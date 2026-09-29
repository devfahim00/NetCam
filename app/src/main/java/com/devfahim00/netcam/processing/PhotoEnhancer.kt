package com.devfahim00.netcam.processing

import android.graphics.Bitmap
import android.util.Log
import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sqrt

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
 * Scene-aware pass ([SceneAnalyzer]): people/skin are gently smoothed and
 * brightened, sky is deepened, greenery/water/sunsets get a targeted colour
 * boost, whites stay clean, and denoise/sharpen adapt to flat vs detailed
 * regions so noise is not amplified.
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

    private const val TAG = "PhotoEnhancer"
    /** Median luma dark/underexposed shots are lifted toward. */
    private const val TARGET_MEDIAN = 0.50f
    private const val MIN_GAMMA = 0.55f
    private const val BASE_GRID = 4          // local-luma base is w/4 x h/4
    private const val DENOISE_MAX_PIXELS = 3_000_000
    private const val SKIN_SMOOTH = 0.55f
    private const val SKIN_SMOOTH_MAX_PIXELS = 2_000_000

    /** @param portrait true → slightly gentler sharpening (subject already pops). */
    suspend fun enhanceSmart(src: Bitmap, portrait: Boolean): Bitmap =
        enhance(src, portrait, SceneAnalyzer.analyze(src))

    fun enhance(src: Bitmap, portrait: Boolean, scene: SceneMap? = null): Bitmap {
        val w = src.width
        val h = src.height
        if (w < 2 || h < 2) return src

        val pixels = IntArray(w * h)
        src.getPixels(pixels, 0, w, 0, 0, w, h)

        // 0) Dark / noisy captures: measure, denoise, then lift exposure.
        val median = lumaMedian(pixels)
        val gamma = if (median < TARGET_MEDIAN) {
            (ln(TARGET_MEDIAN) / ln(max(median, 0.05f))).coerceIn(MIN_GAMMA, 1f)
        } else 1f
        val lift = 1f - gamma
        val noise = noiseLevel(pixels, w, h)
        val noiseN = ((noise - 0.4f) / 1.6f).coerceIn(0f, 1f)
        val mix = (0.20f + 0.65f * noiseN + 0.9f * lift).coerceIn(0f, 0.92f)
        Log.d(TAG, "median=$median gamma=$gamma noise=$noise mix=$mix scene=${scene?.summary}")
        if (mix > 0.18f) {
            runCatching { denoiseGuided(pixels, w, h, mix, lift, noiseN, scene) }
        }
        applyExposure(pixels, gamma)

        val (gr, gg, gb) = whiteBalanceGains(pixels)
        val lumaBase = localLumaBase(pixels, w, h)
        val lut = buildToneLut()

        toneAndColorPass(pixels, w, h, lut, lumaBase, gr, gg, gb, scene, noiseN)
        if (scene != null && scene.skin != null) {
            runCatching { skinSmoothPass(pixels, w, h, scene) }
        }
        sharpenPass(pixels, w, h, if (portrait) SHARPEN_PORTRAIT else SHARPEN_PHOTO, scene, noiseN)

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
        lumaBase: LumaBase?,
        gr: Float,
        gg: Float,
        gb: Float,
        scene: SceneMap?,
        noiseN: Float
    ) {
        val localGain = LOCAL_GAIN * (1f - 0.5f * noiseN)
        val sceneSx = if (scene != null) scene.w.toFloat() / w else 0f
        val sceneSy = if (scene != null) scene.h.toFloat() / h else 0f
        val baseW = lumaBase?.w ?: 1
        val baseH = lumaBase?.h ?: 1
        val baseData = lumaBase?.data
        val bs = BASE_GRID.toFloat()

        for (y in 0 until h) {
            val row = y * w
            // Bilinear y setup for the local-luma base (coarse grid).
            val fy = (y + 0.5f) / bs - 0.5f
            val by0 = Math.floor(fy.toDouble()).toInt()
            val y0 = by0.coerceIn(0, baseH - 1)
            val y1 = (by0 + 1).coerceIn(0, baseH - 1)
            val ty = (fy - by0).coerceIn(0f, 1f)
            val sy = (y + 0.5f) * sceneSy - 0.5f

            for (x in 0 until w) {
                val idx = row + x
                val sx = (x + 0.5f) * sceneSx - 0.5f
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
                val outY: Int = if (baseData != null) {
                    val fx = (x + 0.5f) / bs - 0.5f
                    val bx0 = Math.floor(fx.toDouble()).toInt()
                    val x0 = bx0.coerceIn(0, baseW - 1)
                    val x1 = (bx0 + 1).coerceIn(0, baseW - 1)
                    val tx = (fx - bx0).coerceIn(0f, 1f)

                    val r0 = y0 * baseW
                    val r1 = y1 * baseW
                    val top = baseData[r0 + x0] + tx * (baseData[r0 + x1] - baseData[r0 + x0])
                    val bot = baseData[r1 + x0] + tx * (baseData[r1 + x1] - baseData[r1 + x0])
                    val base = top + ty * (bot - top)

                    val adj = (yNorm + localGain * (yNorm - base)).coerceIn(0f, 1f)
                    lut[(adj * 255f + 0.5f).toInt()]
                } else {
                    lut[(yNorm * 255f + 0.5f).toInt()]
                }

                // Scene regions (0 when no scene map is available).
                var skin = 0f
                var person = 0f
                var sky = 0f
                var fol = 0f
                var wat = 0f
                var sun = 0f
                var white = 0f
                if (scene != null) {
                    skin = scene.sample(scene.skin, sx, sy)
                    person = scene.sample(scene.person, sx, sy)
                    sky = scene.sample(scene.sky, sx, sy)
                    fol = scene.sample(scene.foliage, sx, sy)
                    wat = scene.sample(scene.water, sx, sy)
                    sun = scene.sample(scene.sunset, sx, sy)
                    white = scene.sample(scene.white, sx, sy)
                }

                // Region luminance: brighter skin, deeper sky/greens.
                var yOut = outY.toFloat()
                yOut += (255f - yOut) * (0.075f * skin + 0.02f * person)
                yOut *= 1f - 0.07f * sky - 0.04f * fol

                // Vibrance + saturation around the new luma. Chroma boost is
                // damped in deep shadows so colour noise is not amplified.
                val mx = max(r, max(g, b))
                val mn = min(r, min(g, b))
                val satPix = if (mx <= 0) 0f else (mx - mn) / mx.toFloat()
                val dark = 0.35f + 0.65f * smoothStep(0.05f, 0.35f, yNorm)
                var boost = (SATURATION + VIBRANCE * (1f - satPix)) * dark
                boost += (0.38f * sky + 0.30f * fol + 0.20f * wat + 0.28f * sun) * dark
                var chromaScale = 1f + boost
                chromaScale *= 1f - 0.07f * skin
                chromaScale += (0.92f - chromaScale) * white
                chromaScale = chromaScale.coerceIn(0.6f, 2.1f)

                val outR = yOut + (rw - lumaW) * chromaScale + 2.5f * skin - 3f * fol
                val outG = yOut + (gw - lumaW) * chromaScale + 5f * fol
                val outB = yOut + (bw - lumaW) * chromaScale + 0.8f * skin

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
    private fun sharpenPass(
        pixels: IntArray,
        w: Int,
        h: Int,
        amount: Float,
        scene: SceneMap?,
        noiseN: Float
    ) {
        if (w < 3 || h < 3) return
        val core = 1.2f + 3.5f * noiseN
        val sceneSx = if (scene != null) scene.w.toFloat() / w else 0f
        val sceneSy = if (scene != null) scene.h.toFloat() / h else 0f

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
            val sy = (y + 0.5f) * sceneSy - 0.5f
            for (x in 0 until w) {
                val xm = if (x > 0) x - 1 else x
                val xp = if (x < w - 1) x + 1 else x
                val center = cur[x].toInt() and 0xFF
                val avg = (
                    (prevRow[x].toInt() and 0xFF) + (next[x].toInt() and 0xFF) +
                        (cur[xm].toInt() and 0xFF) + (cur[xp].toInt() and 0xFF)
                    ) * 0.25f
                val d = center - avg
                // Coring: tiny differences are noise, not detail -> not sharpened.
                var amt = amount * smoothStep(core * 0.4f, core * 1.6f, abs(d))
                if (scene != null) {
                    val sx = (x + 0.5f) * sceneSx - 0.5f
                    val edge = scene.sample(scene.edge, sx, sy)
                    val skin = scene.sample(scene.skin, sx, sy)
                    val fol = scene.sample(scene.foliage, sx, sy)
                    amt *= (0.30f + 0.70f * edge) * (1f - 0.85f * skin) * (1f + 0.35f * fol)
                }
                val delta = d * amt

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

    private class LumaBase(val data: FloatArray, val w: Int, val h: Int)

    /** Blurred luminance on a coarse grid (guide for local tone mapping). */
    private fun localLumaBase(px: IntArray, w: Int, h: Int): LumaBase? {
        return try {
            val gw = max(1, w / BASE_GRID)
            val gh = max(1, h / BASE_GRID)
            val g = FloatArray(gw * gh)
            for (gy in 0 until gh) {
                for (gx in 0 until gw) {
                    var acc = 0f
                    // 2x2 taps inside the block keeps this cheap on 12 MP frames.
                    for (t in 0 until 4) {
                        val x = min(w - 1, gx * BASE_GRID + (t and 1) * (BASE_GRID / 2) + BASE_GRID / 4)
                        val y = min(h - 1, gy * BASE_GRID + (t shr 1) * (BASE_GRID / 2) + BASE_GRID / 4)
                        val p = px[y * w + x]
                        acc += 0.299f * ((p shr 16) and 0xFF) + 0.587f * ((p shr 8) and 0xFF) + 0.114f * (p and 0xFF)
                    }
                    g[gy * gw + gx] = acc / 4f / 255f
                }
            }
            var blurred = MaskRefiner.boxFilter(g, gw, gh, max(2, min(gw, gh) / 40))
            blurred = MaskRefiner.boxFilter(blurred, gw, gh, max(2, min(gw, gh) / 40))
            LumaBase(blurred, gw, gh)
        } catch (t: Throwable) {
            null
        }
    }

    // ------------------------------------------------- exposure / denoise

    private fun lumaMedian(px: IntArray): Float {
        val stride = max(1, px.size / 40_000)
        val hist = IntArray(256)
        var n = 0
        var i = 0
        while (i < px.size) {
            val p = px[i]
            hist[(77 * ((p shr 16) and 0xFF) + 150 * ((p shr 8) and 0xFF) + 29 * (p and 0xFF)) shr 8]++
            n++
            i += stride
        }
        var acc = 0
        for (v in 0 until 256) {
            acc += hist[v]
            if (acc * 2 >= n) return v / 255f
        }
        return 0.5f
    }

    /**
     * Robust noise estimate in 0..255 luma units: median |Y - mean(4 neighbours)|.
     * Clean shots land around 0.3-0.5, grainy low-light shots 1.2+.
     */
    private fun noiseLevel(px: IntArray, w: Int, h: Int): Float {
        if (w < 5 || h < 5) return 0f
        val hist = IntArray(256)
        var n = 0
        val step = max(1, sqrt(px.size / 60_000f).toInt())
        var y = 1
        while (y < h - 1) {
            var x = 1
            while (x < w - 1) {
                val i = y * w + x
                fun l(j: Int): Int {
                    val p = px[j]
                    return (77 * ((p shr 16) and 0xFF) + 150 * ((p shr 8) and 0xFF) + 29 * (p and 0xFF)) shr 8
                }
                val avg = (l(i - 1) + l(i + 1) + l(i - w) + l(i + w)) / 4f
                hist[min(255, abs(l(i) - avg).toInt())]++
                n++
                x += step
            }
            y += step
        }
        if (n == 0) return 0f
        var acc = 0
        for (v in 0 until 256) {
            acc += hist[v]
            if (acc * 2 >= n) return v + 0.35f
        }
        return 0f
    }

    /** Highlight-protected gamma lift; chroma ratio is preserved. */
    private fun applyExposure(px: IntArray, gamma: Float) {
        if (gamma >= 0.995f) return
        val ratio = FloatArray(256)
        for (i in 0 until 256) {
            val y = max(i, 1) / 255f
            val yn = y.pow(gamma)
            val wgt = ((0.9f - y) / 0.5f).coerceIn(0f, 1f)
            ratio[i] = (y + (yn - y) * wgt) / y
        }
        for (i in px.indices) {
            val p = px[i]
            val r = (p shr 16) and 0xFF
            val g = (p shr 8) and 0xFF
            val b = p and 0xFF
            val k = ratio[(77 * r + 150 * g + 29 * b) shr 8]
            px[i] = (0xFF shl 24) or
                ((r * k + 0.5f).toInt().coerceIn(0, 255) shl 16) or
                ((g * k + 0.5f).toInt().coerceIn(0, 255) shl 8) or
                (b * k + 0.5f).toInt().coerceIn(0, 255)
        }
    }

    /**
     * Edge-aware denoise: per-channel guided filter (luma guide) on a <=3 MP
     * proxy, chroma smoothed harder than luma, then blended back into the
     * full-resolution frame by [mix].
     */
    private fun denoiseGuided(
        px: IntArray,
        w: Int,
        h: Int,
        mix: Float,
        lift: Float,
        noiseN: Float,
        scene: SceneMap?
    ) {
        val scale = if (w.toLong() * h > DENOISE_MAX_PIXELS) {
            sqrt(DENOISE_MAX_PIXELS.toFloat() / (w.toFloat() * h))
        } else 1f
        val sw = max(8, (w * scale).toInt())
        val sh = max(8, (h * scale).toInt())

        val full = Bitmap.createBitmap(px, w, h, Bitmap.Config.ARGB_8888)
        val small = if (sw == w && sh == h) full else Bitmap.createScaledBitmap(full, sw, sh, true)
        val sp = IntArray(sw * sh)
        small.getPixels(sp, 0, sw, 0, 0, sw, sh)
        small.recycle()
        if (full !== small) full.recycle()

        val n = sw * sh
        val r = FloatArray(n); val g = FloatArray(n); val b = FloatArray(n); val y = FloatArray(n)
        for (i in 0 until n) {
            val p = sp[i]
            r[i] = ((p shr 16) and 0xFF) / 255f
            g[i] = ((p shr 8) and 0xFF) / 255f
            b[i] = (p and 0xFF) / 255f
            y[i] = 0.299f * r[i] + 0.587f * g[i] + 0.114f * b[i]
        }
        val rad = max(2, min(sw, sh) / 160)
        val e = 0.004f + 0.03f * lift + 0.02f * noiseN
        val eps = e * e
        val dr = MaskRefiner.guidedFilter(y, r, sw, sh, rad, eps)
        val dg = MaskRefiner.guidedFilter(y, g, sw, sh, rad, eps)
        val db = MaskRefiner.guidedFilter(y, b, sw, sh, rad, eps)

        // Chroma: stronger edge-aware smoothing of (channel - luma).
        val yd = FloatArray(n) { 0.299f * dr[it] + 0.587f * dg[it] + 0.114f * db[it] }
        val cr = FloatArray(n) { dr[it] - yd[it] }
        val cg = FloatArray(n) { dg[it] - yd[it] }
        val cb = FloatArray(n) { db[it] - yd[it] }
        val ceps = 0.02f * 0.02f
        val crf = MaskRefiner.guidedFilter(yd, cr, sw, sh, rad * 3, ceps)
        val cgf = MaskRefiner.guidedFilter(yd, cg, sw, sh, rad * 3, ceps)
        val cbf = MaskRefiner.guidedFilter(yd, cb, sw, sh, rad * 3, ceps)

        val out = IntArray(n)
        for (i in 0 until n) {
            val ro = ((yd[i] + crf[i]).coerceIn(0f, 1f) * 255f + 0.5f).toInt()
            val go = ((yd[i] + cgf[i]).coerceIn(0f, 1f) * 255f + 0.5f).toInt()
            val bo = ((yd[i] + cbf[i]).coerceIn(0f, 1f) * 255f + 0.5f).toInt()
            out[i] = (0xFF shl 24) or (ro shl 16) or (go shl 8) or bo
        }
        var den = Bitmap.createBitmap(out, sw, sh, Bitmap.Config.ARGB_8888)
        if (sw != w || sh != h) {
            val up = Bitmap.createScaledBitmap(den, w, h, true)
            den.recycle()
            den = up
        }
        val dp = IntArray(w * h)
        den.getPixels(dp, 0, w, 0, 0, w, h)
        den.recycle()

        // Per-pixel strength: flat areas (sky, skin, walls) and shadows are
        // denoised harder than detailed/bright areas so texture survives.
        val sceneSx = if (scene != null) scene.w.toFloat() / w else 0f
        val sceneSy = if (scene != null) scene.h.toFloat() / h else 0f
        for (yy in 0 until h) {
            val sy = (yy + 0.5f) * sceneSy - 0.5f
            for (xx in 0 until w) {
                val i = yy * w + xx
                val a = px[i]
                val c = dp[i]
                val edge = if (scene != null) {
                    scene.sample(scene.edge, (xx + 0.5f) * sceneSx - 0.5f, sy)
                } else 0.5f
                val yA = (77 * ((a shr 16) and 0xFF) + 150 * ((a shr 8) and 0xFF) + 29 * (a and 0xFF)) shr 8
                val dk = 1f - yA / 255f
                val m = (mix * (1.2f - 0.7f * edge) * (1f + 0.6f * dk * dk)).coerceIn(0f, 0.95f)
                val keep = 1f - m
                val ro = (((a shr 16) and 0xFF) * keep + ((c shr 16) and 0xFF) * m + 0.5f).toInt()
                val go = (((a shr 8) and 0xFF) * keep + ((c shr 8) and 0xFF) * m + 0.5f).toInt()
                val bo = ((a and 0xFF) * keep + (c and 0xFF) * m + 0.5f).toInt()
                px[i] = (0xFF shl 24) or (ro.coerceIn(0, 255) shl 16) or (go.coerceIn(0, 255) shl 8) or bo.coerceIn(0, 255)
            }
        }
    }

    /**
     * Beauty pass: edge-preserving smoothing (guided filter) blended in only
     * where skin was detected, so faces/body get smooth while eyes, hair,
     * lips and the background keep their detail.
     */
    private fun skinSmoothPass(px: IntArray, w: Int, h: Int, scene: SceneMap) {
        val skinMap = scene.skin ?: return
        val scale = if (w.toLong() * h > SKIN_SMOOTH_MAX_PIXELS) {
            sqrt(SKIN_SMOOTH_MAX_PIXELS.toFloat() / (w.toFloat() * h))
        } else 1f
        val sw = max(8, (w * scale).toInt())
        val sh = max(8, (h * scale).toInt())

        val full = Bitmap.createBitmap(px, w, h, Bitmap.Config.ARGB_8888)
        val small = if (sw == w && sh == h) full else Bitmap.createScaledBitmap(full, sw, sh, true)
        val sp = IntArray(sw * sh)
        small.getPixels(sp, 0, sw, 0, 0, sw, sh)
        if (small !== full) small.recycle()
        full.recycle()

        val n = sw * sh
        val r = FloatArray(n); val g = FloatArray(n); val b = FloatArray(n); val y = FloatArray(n)
        for (i in 0 until n) {
            val p = sp[i]
            r[i] = ((p shr 16) and 0xFF) / 255f
            g[i] = ((p shr 8) and 0xFF) / 255f
            b[i] = (p and 0xFF) / 255f
            y[i] = 0.299f * r[i] + 0.587f * g[i] + 0.114f * b[i]
        }
        val rad = max(3, min(sw, sh) / 110)
        val eps = 0.03f * 0.03f
        val fr = MaskRefiner.guidedFilter(y, r, sw, sh, rad, eps)
        val fg = MaskRefiner.guidedFilter(y, g, sw, sh, rad, eps)
        val fb = MaskRefiner.guidedFilter(y, b, sw, sh, rad, eps)
        val out = IntArray(n)
        for (i in 0 until n) {
            out[i] = (0xFF shl 24) or
                ((fr[i] * 255f + 0.5f).toInt().coerceIn(0, 255) shl 16) or
                ((fg[i] * 255f + 0.5f).toInt().coerceIn(0, 255) shl 8) or
                (fb[i] * 255f + 0.5f).toInt().coerceIn(0, 255)
        }
        var sm = Bitmap.createBitmap(out, sw, sh, Bitmap.Config.ARGB_8888)
        if (sw != w || sh != h) {
            val up = Bitmap.createScaledBitmap(sm, w, h, true)
            sm.recycle()
            sm = up
        }
        val dp = IntArray(w * h)
        sm.getPixels(dp, 0, w, 0, 0, w, h)
        sm.recycle()

        val sceneSx = scene.w.toFloat() / w
        val sceneSy = scene.h.toFloat() / h
        for (yy in 0 until h) {
            val sy = (yy + 0.5f) * sceneSy - 0.5f
            for (xx in 0 until w) {
                val wgt = scene.sample(skinMap, (xx + 0.5f) * sceneSx - 0.5f, sy) * SKIN_SMOOTH
                if (wgt < 0.01f) continue
                val i = yy * w + xx
                val a = px[i]
                val c = dp[i]
                val keep = 1f - wgt
                val ro = (((a shr 16) and 0xFF) * keep + ((c shr 16) and 0xFF) * wgt + 0.5f).toInt()
                val go = (((a shr 8) and 0xFF) * keep + ((c shr 8) and 0xFF) * wgt + 0.5f).toInt()
                val bo = ((a and 0xFF) * keep + (c and 0xFF) * wgt + 0.5f).toInt()
                px[i] = (0xFF shl 24) or (ro.coerceIn(0, 255) shl 16) or (go.coerceIn(0, 255) shl 8) or bo.coerceIn(0, 255)
            }
        }
    }
}
