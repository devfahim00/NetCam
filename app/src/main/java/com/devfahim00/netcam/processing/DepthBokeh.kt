package com.devfahim00.netcam.processing

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.util.Log
import com.devfahim00.netcam.util.alphaValuesToBitmap
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
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

    private val RADII = floatArrayOf(0f, 0.12f, 0.27f, 0.47f, 0.72f, 0.90f, 1.0f)  // fractions of maxCoc
    private const val TAG = "DepthBokeh"
    private const val BAND_PAD = 0.05f        // extra in-focus slack around the subject's own depth range
    private const val MIN_SPAN = 0.18f        // min depth span mapped to full blur (avoids over-amplifying noise)
    private const val FOREGROUND_SOFT = 0.75f
    private const val BLOOM_THRESHOLD = 0.72f
    private const val BLOOM_GAIN = 0.40f
    private const val SIGMA_FRAC = 0.0075f      // max blur sigma as a fraction of the long side (at strength 1.0)
    private const val COC_GAMMA = 1.45f         // >1: mid-distance objects stay recognisable, only the far scene melts
    private const val DETAIL_KEEP = 0.16f       // share of the original structure kept in the far background

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

        // ---- 2) whole-subject region --------------------------------------
        // (a) fill holes / gaps inside the segmentation mask,
        // (b) measure the subject's own depth range,
        // (c) grow the region over anything connected to it at the same depth
        //     (hand, second object, table the object stands on, ...).
        val filled = fillHoles(mask, w, h)
        val focus = focusDepth(filled, depth, w, h)
        val band = subjectBand(filled, depth, w, h, focus)
        val bandLo = band[0]
        val bandHi = band[1]
        val region = subjectRegion(filled, depth, gray, w, h, bandLo, bandHi)

        // ---- 3) circle of confusion from real depth ------------------------
        // Blur ramps from 0 at the subject's depth band to 100% at the
        // farthest (or nearest) depth actually present in the scene, so blur
        // grows smoothly with distance instead of saturating everywhere.
        val bgRef = depthPercentile(depth, region, n, 0.03f, 0f)
        val fgRef = depthPercentile(depth, region, n, 0.97f, 1f)
        val farSpan = max(bandLo - bgRef, MIN_SPAN)
        val nearSpan = max(fgRef - bandHi, MIN_SPAN)
        Log.d(TAG, "focus=$focus band=[$bandLo,$bandHi] bgRef=$bgRef fgRef=$fgRef " +
            "maskCov=${cov(mask)} filledCov=${cov(filled)} regionCov=${cov(region)}")

        // Slider above 1.0 grows at half speed so the background never turns into mush.
        val effStrength = if (strength <= 1f) strength else 1f + (strength - 1f) * 0.5f
        // box radius ~ sigma, coc is expressed in "sigma * 1.6" units (see boxR below)
        val maxCoc = 1.6f * side * SIGMA_FRAC * effStrength
        val coc01 = FloatArray(n)
        for (i in 0 until n) {
            val d = depth[i]
            var c = 0f
            if (d < bandLo) {
                c = ((bandLo - d) / farSpan).coerceIn(0f, 1f).pow(COC_GAMMA)
            } else if (d > bandHi) {
                c = ((d - bandHi) / nearSpan).coerceIn(0f, 1f).pow(COC_GAMMA) * FOREGROUND_SOFT
            }
            // The whole subject region is protected -> never blurred inside.
            coc01[i] = c * (1f - region[i])
        }
        val cocFiltered = MaskRefiner.guidedFilter(gray, coc01, w, h, 4, 1.0e-4f)
        val coc = FloatArray(n)
        // Re-apply protection after filtering so blur can never creep into the subject.
        for (i in 0 until n) coc[i] = cocFiltered[i].coerceIn(0f, 1f) * (1f - region[i]) * maxCoc

        // ---- 4) blur level per pixel -------------------------------------
        val radii = FloatArray(RADII.size) { RADII[it] * maxCoc }
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
            // Bloom: only genuine light *peaks* (bright AND much brighter than
            // their surroundings) are pushed up, so lights become discs while
            // large bright areas (walls, sky, curtains) are NOT blown out.
            val localMean = MaskRefiner.boxFilter(gray, w, h, max(6, side / 30))
            for (i in 0 until n) {
                val lum = gray[i]
                val peak = smoothstep(0.10f, 0.30f, lum - localMean[i]) *
                    smoothstep(0.70f, 0.93f, lum)
                if (peak > 0f) {
                    val boost = 1f + peak * BLOOM_GAIN
                    chR[i] *= boost
                    chG[i] *= boost
                    chB[i] *= boost
                }
            }

            // R, G, B (and the coverage plane) are independent, so each level's
            // blurs run on separate cores — same result, ~2-3x faster.
            val chans = arrayOf(chR, chG, chB)
            val blurrers = Array(4) { Blurrer(w, h) }
            val tmps = Array(3) { FloatArray(n) }
            val srcTmps = Array(3) { FloatArray(n) }
            var prev = Array(3) { FloatArray(n) }
            var cur = Array(3) { FloatArray(n) }
            for (i in 0 until n) {
                // L0 (sharp) approximated by the bloom-free original.
                val p = px[i]
                prev[0][i] = ((p shr 16) and 0xFF).toFloat()
                prev[1][i] = ((p shr 8) and 0xFF).toFloat()
                prev[2][i] = (p and 0xFF).toFloat()
            }
            val wk = FloatArray(n)
            val den = FloatArray(n)

            for (k in 1..maxNeeded) {
                val boxR = max(1, (radii[k] / 1.6f).roundToInt())
                val thr = if (k > 1) 0.85f * radii[k - 1] else 0f

                if (k == 1) {
                    val c0 = cur
                    runParallel(3) { c -> blurrers[c].blur(chans[c], c0[c], boxR) }
                } else {
                    // Only pixels at least as blurry as the previous level
                    // may contribute: sharp pixels never bleed into blur.
                    for (i in 0 until n) wk[i] = if (coc[i] >= thr) 1f else 0f
                    runParallel(4) { c ->
                        if (c == 3) {
                            blurrers[3].blur(wk, den, boxR)
                        } else {
                            val src = chans[c]
                            val st = srcTmps[c]
                            for (i in 0 until n) st[i] = src[i] * wk[i]
                            blurrers[c].blur(st, tmps[c], boxR)
                        }
                    }
                    val c0 = cur
                    val p0 = prev
                    runParallel(3) { c -> mixLayer(c0[c], tmps[c], den, p0[c], n) }
                }

                val cR = cur[0]; val cG = cur[1]; val cB = cur[2]
                for (i in 0 until n) {
                    val wl = 1f - abs(lvl[i] - k)
                    if (wl > 0f) {
                        outR[i] += cR[i] * wl
                        outG[i] += cG[i] * wl
                        outB[i] += cB[i] * wl
                    }
                }

                val swap = prev; prev = cur; cur = swap
            }
        }

        // ---- exposure guard ------------------------------------------------
        // Blurring must never brighten the photo: match the mean luma of the
        // blurred region to the original (small bloom allowance only).
        run {
            var sumIn = 0.0
            var sumOut = 0.0
            for (i in 0 until n) {
                val wgt = lvl[i].coerceIn(0f, 1f)
                if (wgt <= 0f) continue
                sumIn += wgt * (0.299 * ((px[i] shr 16) and 0xFF) + 0.587 * ((px[i] shr 8) and 0xFF) + 0.114 * (px[i] and 0xFF))
                sumOut += wgt * (0.299 * outR[i] + 0.587 * outG[i] + 0.114 * outB[i])
            }
            if (sumOut > 1e-3) {
                val gain = (sumIn * 1.02 / sumOut).toFloat().coerceIn(0.70f, 1f)
                Log.d(TAG, "exposureGuard gain=$gain")
                if (gain < 0.999f) {
                    for (i in 0 until n) {
                        val wgt = lvl[i].coerceIn(0f, 1f)
                        val g = 1f + (gain - 1f) * wgt
                        outR[i] *= g; outG[i] *= g; outB[i] *= g
                    }
                }
            }
        }

        // ---- detail retention ---------------------------------------------
        // A real lens still shows *what* is behind the subject (trees, shelves,
        // people as soft shapes). Fold a little of the original structure back
        // into the blurred areas, strongest where blur is strongest.
        for (i in 0 until n) {
            val t = (coc[i] / max(maxCoc, 1e-3f)).coerceIn(0f, 1f)
            val k = DETAIL_KEEP * smoothstep(0.05f, 0.6f, t) * (1f - 0.35f * t)
            if (k > 0f) {
                val p = px[i]
                outR[i] += (((p shr 16) and 0xFF) - outR[i]) * k
                outG[i] += (((p shr 8) and 0xFF) - outG[i]) * k
                outB[i] += ((p and 0xFF) - outB[i]) * k
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

    private val pool: java.util.concurrent.ExecutorService =
        java.util.concurrent.Executors.newFixedThreadPool(4) { r ->
            Thread(r, "bokeh-worker").apply { isDaemon = true }
        }

    /** Runs task(0..count-1) concurrently and waits for all of them. */
    private fun runParallel(count: Int, task: (Int) -> Unit) {
        val futures = (0 until count).map { c -> pool.submit(Runnable { task(c) }) }
        futures.forEach { it.get() }
    }

    /** cur = num/den where den is trustworthy, else the previous (less blurry) level. */
    private fun mixLayer(cur: FloatArray, num: FloatArray, den: FloatArray, prev: FloatArray, n: Int) {
        for (i in 0 until n) {
            val d = den[i]
            val a = (d / 0.15f).coerceIn(0f, 1f)
            val v = if (d > 1e-4f) num[i] / d else prev[i]
            cur[i] = (v * a + prev[i] * (1f - a)).coerceIn(0f, 255f * 2f)
        }
    }

    private fun cov(a: FloatArray): Float {
        var c = 0
        for (v in a) if (v > 0.5f) c++
        return if (a.isEmpty()) 0f else c.toFloat() / a.size
    }

    /**
     * Fills holes inside the subject mask: anything not reachable from the
     * image border through non-subject pixels is inside the subject. A small
     * morphological closing then bridges thin cracks in the middle.
     */
    private fun fillHoles(mask: FloatArray, w: Int, h: Int): FloatArray {
        val n = w * h
        val bin = BooleanArray(n) { mask[it] > 0.5f }
        // flood-fill the outside from the border
        val outside = BooleanArray(n)
        val q = IntArray(n)
        var qh = 0
        var qt = 0
        fun push(i: Int) {
            if (!bin[i] && !outside[i]) { outside[i] = true; q[qt++] = i }
        }
        for (x in 0 until w) { push(x); push((h - 1) * w + x) }
        for (y in 0 until h) { push(y * w); push(y * w + w - 1) }
        while (qh < qt) {
            val i = q[qh++]
            val x = i % w
            val y = i / w
            if (x > 0) push(i - 1)
            if (x < w - 1) push(i + 1)
            if (y > 0) push(i - w)
            if (y < h - 1) push(i + w)
        }
        val solid = FloatArray(n) { if (bin[it] || !outside[it]) 1f else 0f }

        // closing = dilate then erode
        val r = max(3, max(w, h) / 120)
        val dil = MaskRefiner.boxFilter(solid, w, h, r)
        val dilated = FloatArray(n) { if (dil[it] > 0.001f) 1f else 0f }
        val ero = MaskRefiner.boxFilter(dilated, w, h, r)
        val out = FloatArray(n)
        for (i in 0 until n) {
            val closed = if (ero[i] > 0.999f) 1f else 0f
            out[i] = max(mask[i], max(solid[i], closed))
        }
        return out
    }

    /** Robust depth range of the subject: 6th..94th percentile, padded. */
    private fun subjectBand(filled: FloatArray, depth: FloatArray, w: Int, h: Int, focus: Float): FloatArray {
        val n = w * h
        var count = 0
        for (i in 0 until n) if (filled[i] > 0.95f) count++
        if (count < 50) return floatArrayOf(focus - BAND_PAD, focus + BAND_PAD)
        val vals = FloatArray(count)
        var j = 0
        for (i in 0 until n) if (filled[i] > 0.95f) vals[j++] = depth[i]
        vals.sort()
        val p06 = vals[(count * 0.06f).toInt().coerceIn(0, count - 1)]
        val p94 = vals[(count * 0.94f).toInt().coerceIn(0, count - 1)]
        return floatArrayOf(min(p06, focus) - BAND_PAD, max(p94, focus) + BAND_PAD)
    }

    /**
     * Final "keep sharp" region: the filled mask plus every pixel that is
     * connected to it and lies inside the subject's depth band (limited to a
     * padded bounding box). Guided-filtered so edges snap to the photo.
     */
    private fun subjectRegion(
        filled: FloatArray, depth: FloatArray, gray: FloatArray,
        w: Int, h: Int, lo: Float, hi: Float
    ): FloatArray {
        val n = w * h
        var minX = w; var maxX = -1; var minY = h; var maxY = -1
        for (y in 0 until h) {
            val row = y * w
            for (x in 0 until w) if (filled[row + x] > 0.5f) {
                if (x < minX) minX = x
                if (x > maxX) maxX = x
                if (y < minY) minY = y
                if (y > maxY) maxY = y
            }
        }
        if (maxX < 0) return filled
        val padX = (w * 0.08f).toInt()
        val padY = (h * 0.08f).toInt()
        val bx0 = max(0, minX - padX); val bx1 = min(w - 1, maxX + padX)
        val by0 = max(0, minY - padY); val by1 = min(h - 1, maxY + padY)

        val inReg = BooleanArray(n)
        val q = IntArray(n)
        var qh = 0
        var qt = 0
        for (i in 0 until n) if (filled[i] > 0.5f) { inReg[i] = true; q[qt++] = i }
        val seedCount = qt
        val gLo = lo - 0.02f
        val gHi = hi + 0.02f
        fun tryAdd(j: Int, x: Int, y: Int) {
            if (inReg[j] || x < bx0 || x > bx1 || y < by0 || y > by1) return
            val d = depth[j]
            if (d in gLo..gHi) { inReg[j] = true; q[qt++] = j }
        }
        while (qh < qt) {
            val i = q[qh++]
            val x = i % w
            val y = i / w
            if (x > 0) tryAdd(i - 1, x - 1, y)
            if (x < w - 1) tryAdd(i + 1, x + 1, y)
            if (y > 0) tryAdd(i - w, x, y - 1)
            if (y < h - 1) tryAdd(i + w, x, y + 1)
        }
        // Runaway growth (e.g. a wall at the subject's depth): keep the mask only.
        val grownCount = qt
        val runaway = grownCount > 2.5f * seedCount && grownCount > 0.55f * n
        Log.d(TAG, "region seed=$seedCount grown=$grownCount runaway=$runaway")

        val grown = FloatArray(n) { if (inReg[it]) 1f else 0f }
        val smooth = MaskRefiner.boxFilter(grown, w, h, 2)
        val out = FloatArray(n)
        for (i in 0 until n) {
            val g = if (!runaway && smooth[i] > 0.6f) 1f else 0f
            out[i] = max(filled[i], g)
        }
        val side = max(w, h)
        val snapped = MaskRefiner.guidedFilter(gray, out, w, h, max(2, side / 250), 1.0e-4f)
        for (i in 0 until n) snapped[i] = max(snapped[i].coerceIn(0f, 1f), if (filled[i] > 0.9f) 1f else 0f)
        return snapped
    }

    /** Percentile of depth over pixels outside [region]; [fallback] if too few. */
    private fun depthPercentile(depth: FloatArray, region: FloatArray, n: Int, p: Float, fallback: Float): Float {
        val stride = max(1, n / 60000)
        var c = 0
        var i = 0
        while (i < n) { if (region[i] < 0.5f) c++; i += stride }
        if (c < 100) return fallback
        val vals = FloatArray(c)
        var j = 0
        i = 0
        while (i < n) { if (region[i] < 0.5f) vals[j++] = depth[i]; i += stride }
        vals.sort()
        return vals[(c * p).toInt().coerceIn(0, c - 1)]
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
