package com.devfahim00.netcam.processing

import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Edge-aware mask refinement — this is what separates a convincing portrait
 * from an obvious "cut-out sticker" effect:
 *
 *  1. A fast guided filter (integral-image box filters, O(N) regardless of
 *     radius) snaps the soft mask onto real image edges.
 *  2. A smoothstep remap feathers the transition and slightly erodes the
 *     subject side so no background color bleeds onto the subject.
 *  3. A two-pass chamfer distance transform builds a depth field from the
 *     subject boundary outward, used for graduated (not flat) bokeh.
 */
object MaskRefiner {

    private const val SQRT2 = 1.41421356f
    private const val GUIDED_EPS = 2.0e-3f

    /** Guided-filter + feathered smoothstep remap of a soft mask. */
    fun refine(guideGray: FloatArray, alpha: FloatArray, w: Int, h: Int): FloatArray {
        val radius = max(3, maxOf(w, h) / 120)
        val filtered = guidedFilter(guideGray, alpha, w, h, radius, GUIDED_EPS)

        // Feather: remap alpha so the 0.32..0.62 band becomes the soft edge.
        // Biasing the band above 0.5 erodes the subject by a fraction of a
        // pixel — just enough to kill background fringes.
        val t0 = 0.32f
        val t1 = 0.64f
        val out = FloatArray(filtered.size)
        for (i in out.indices) {
            val x = filtered[i]
            out[i] = if (x <= t0) {
                0f
            } else if (x >= t1) {
                1f
            } else {
                val t = (x - t0) / (t1 - t0)
                t * t * (3f - 2f * t)
            }
        }
        return out
    }

    /**
     * Normalized distance-from-subject field: 0 at the subject edge, 1 once
     * [maxDistPx] pixels away. Drives the depth-graded blur falloff.
     */
    fun distanceField(alpha: FloatArray, w: Int, h: Int, maxDistPx: Float): FloatArray {
        val inf = Float.MAX_VALUE
        val dist = FloatArray(w * h) { if (alpha[it] > 0.5f) 0f else inf }

        // Forward pass (top-left to bottom-right).
        for (y in 0 until h) {
            val row = y * w
            for (x in 0 until w) {
                val i = row + x
                if (dist[i] == 0f) continue
                var d = dist[i]
                if (y > 0) {
                    d = min(d, dist[i - w] + 1f)
                    if (x > 0) d = min(d, dist[i - w - 1] + SQRT2)
                    if (x < w - 1) d = min(d, dist[i - w + 1] + SQRT2)
                }
                if (x > 0) d = min(d, dist[i - 1] + 1f)
                dist[i] = d
            }
        }

        // Backward pass (bottom-right to top-left).
        for (y in h - 1 downTo 0) {
            val row = y * w
            for (x in w - 1 downTo 0) {
                val i = row + x
                if (dist[i] == 0f) continue
                var d = dist[i]
                if (y < h - 1) {
                    d = min(d, dist[i + w] + 1f)
                    if (x > 0) d = min(d, dist[i + w - 1] + SQRT2)
                    if (x < w - 1) d = min(d, dist[i + w + 1] + SQRT2)
                }
                if (x < w - 1) d = min(d, dist[i + 1] + 1f)
                dist[i] = d
            }
        }

        val out = FloatArray(dist.size)
        for (i in out.indices) {
            out[i] = if (dist[i] == 0f) 0f else (dist[i] / maxDistPx).coerceAtMost(1f)
        }
        return out
    }

    /** Fraction of the frame covered (alpha > 0.5). */
    fun coverage(alpha: FloatArray): Float {
        var sum = 0f
        for (v in alpha) if (v > 0.5f) sum += 1f
        return if (alpha.isEmpty()) 0f else sum / alpha.size
    }

    // ------------------------------------------------------------- internals

    /**
     * Classic guided filter (He et al.) with a grayscale guide, evaluated with
     * integral-image box filters so cost is independent of the radius.
     */
    private fun guidedFilter(
        guide: FloatArray,
        input: FloatArray,
        w: Int,
        h: Int,
        radius: Int,
        eps: Float
    ): FloatArray {
        val n = w * h

        val meanI = boxFilter(guide, w, h, radius)
        val meanP = boxFilter(input, w, h, radius)

        val ip = FloatArray(n)
        val ii = FloatArray(n)
        for (i in 0 until n) {
            ip[i] = guide[i] * input[i]
            ii[i] = guide[i] * guide[i]
        }
        val meanIp = boxFilter(ip, w, h, radius)
        val meanIi = boxFilter(ii, w, h, radius)

        val a = FloatArray(n)
        val b = FloatArray(n)
        for (i in 0 until n) {
            val varI = (meanIi[i] - meanI[i] * meanI[i]).coerceAtLeast(0f)
            val covIp = meanIp[i] - meanI[i] * meanP[i]
            a[i] = covIp / (varI + eps)
            b[i] = meanP[i] - a[i] * meanI[i]
        }

        val meanA = boxFilter(a, w, h, radius)
        val meanB = boxFilter(b, w, h, radius)

        val q = FloatArray(n)
        for (i in 0 until n) {
            q[i] = (meanA[i] * guide[i] + meanB[i]).coerceIn(0f, 1f)
        }
        return q
    }

    /** Mean filter via an integral image; windows are clamped at the borders. */
    private fun boxFilter(src: FloatArray, w: Int, h: Int, r: Int): FloatArray {
        val iw = w + 1
        val integral = DoubleArray(iw * (h + 1))
        for (y in 0 until h) {
            var rowSum = 0.0
            val rowIdx = (y + 1) * iw
            val prevRow = y * iw
            for (x in 0 until w) {
                rowSum += src[y * w + x]
                integral[rowIdx + x + 1] = integral[prevRow + x + 1] + rowSum
            }
        }

        val out = FloatArray(w * h)
        for (y in 0 until h) {
            val y0 = (y - r).coerceAtLeast(0)
            val y1 = (y + r + 1).coerceAtMost(h)
            val baseTop = y0 * iw
            val baseBottom = y1 * iw
            val rowOut = y * w
            for (x in 0 until w) {
                val x0 = (x - r).coerceAtLeast(0)
                val x1 = (x + r + 1).coerceAtMost(w)
                val area = ((x1 - x0) * (y1 - y0)).toDouble()
                val sum = integral[baseBottom + x1] - integral[baseTop + x1] -
                    integral[baseBottom + x0] + integral[baseTop + x0]
                out[rowOut + x] = (sum / area).toFloat()
            }
        }
        return out
    }
}
