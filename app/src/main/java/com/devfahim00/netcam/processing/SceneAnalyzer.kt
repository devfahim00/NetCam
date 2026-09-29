package com.devfahim00.netcam.processing

import android.graphics.Bitmap
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/** Classic smoothstep in [a, b]. */
internal fun smoothStep(a: Float, b: Float, x: Float): Float {
    if (b <= a) return if (x >= b) 1f else 0f
    val t = ((x - a) / (b - a)).coerceIn(0f, 1f)
    return t * t * (3f - 2f * t)
}

/** Soft "band" membership: 1 inside [lo, hi], fading out over [feather]. */
private fun band(x: Float, lo: Float, hi: Float, feather: Float): Float =
    smoothStep(lo - feather, lo, x) * (1f - smoothStep(hi, hi + feather, x))

/**
 * Low-resolution semantic map of a photo. Every field is a soft 0..1 mask at
 * [w] x [h] (a ~512 px proxy); a null field means "class not present".
 */
class SceneMap(
    val w: Int,
    val h: Int,
    val person: FloatArray?,
    val skin: FloatArray?,
    val sky: FloatArray?,
    val foliage: FloatArray?,
    val water: FloatArray?,
    val sunset: FloatArray?,
    val white: FloatArray?,
    /** 0 = flat / smooth area (noise shows), 1 = real structure (edges, texture). */
    val edge: FloatArray,
    val summary: String
) {
    /** Bilinear sample at proxy coordinates ([sx], [sy]); null field -> 0. */
    fun sample(f: FloatArray?, sx: Float, sy: Float): Float {
        if (f == null) return 0f
        val cx = sx.coerceIn(0f, (w - 1).toFloat())
        val cy = sy.coerceIn(0f, (h - 1).toFloat())
        val x0 = cx.toInt()
        val y0 = cy.toInt()
        val x1 = min(x0 + 1, w - 1)
        val y1 = min(y0 + 1, h - 1)
        val tx = cx - x0
        val ty = cy - y0
        val a = f[y0 * w + x0]
        val b = f[y0 * w + x1]
        val c = f[y1 * w + x0]
        val d = f[y1 * w + x1]
        val top = a + tx * (b - a)
        val bot = c + tx * (d - c)
        return top + ty * (bot - top)
    }
}

/**
 * Detects WHAT is in the photo (people/skin, sky, foliage, water, sunset
 * light, white/snow) so [PhotoEnhancer] can treat each region differently:
 * smooth + brighten skin, deepen sky, enrich greenery, keep whites clean,
 * and denoise flat regions harder than detailed ones.
 *
 * People come from the bundled ML Kit selfie segmenter (offline); everything
 * else is fast soft colour/position classification on a 512 px proxy, so it
 * adds only a few tens of milliseconds and works on any subject.
 */
object SceneAnalyzer {

    private const val TAG = "SceneAnalyzer"
    private const val PROXY_SIDE = 512
    private const val MIN_COVER = 0.004f

    suspend fun analyze(src: Bitmap): SceneMap? = withContext(Dispatchers.Default) {
        try {
            analyzeInternal(src)
        } catch (t: Throwable) {
            Log.w(TAG, "scene analysis failed", t)
            null
        }
    }

    private suspend fun analyzeInternal(src: Bitmap): SceneMap {
        val longest = max(src.width, src.height)
        val scale = min(1f, PROXY_SIDE.toFloat() / longest)
        val w = max(16, (src.width * scale).toInt())
        val h = max(16, (src.height * scale).toInt())
        val proxy = if (w == src.width && h == src.height) src
        else Bitmap.createScaledBitmap(src, w, h, true)
        val px = IntArray(w * h)
        proxy.getPixels(px, 0, w, 0, 0, w, h)

        var personArr: FloatArray? = try {
            SegmentationManager.personMask(proxy)?.alpha
        } catch (t: Throwable) {
            null
        }
        if (proxy !== src) proxy.recycle()
        if (personArr != null && personArr.size == w * h) {
            personArr = MaskRefiner.boxFilter(personArr, w, h, 2)
            if (mean(personArr) < 0.006f) personArr = null
        } else {
            personArr = null
        }

        val n = w * h
        val skinA = FloatArray(n)
        val skyA = FloatArray(n)
        val folA = FloatArray(n)
        val watA = FloatArray(n)
        val sunA = FloatArray(n)
        val whtA = FloatArray(n)
        val luma = FloatArray(n)
        val hMax = max(1, h - 1).toFloat()

        for (y in 0 until h) {
            val yn = y / hMax
            for (x in 0 until w) {
                val i = y * w + x
                val p = px[i]
                val r = ((p shr 16) and 0xFF) / 255f
                val g = ((p shr 8) and 0xFF) / 255f
                val b = (p and 0xFF) / 255f
                val mx = max(r, max(g, b))
                val mn = min(r, min(g, b))
                val d = mx - mn
                val s = if (mx <= 0f) 0f else d / mx
                val hue = when {
                    d < 1e-4f -> 0f
                    mx == r -> 60f * (((g - b) / d + 6f) % 6f)
                    mx == g -> 60f * ((b - r) / d + 2f)
                    else -> 60f * ((r - g) / d + 4f)
                }
                val hueS = if (hue > 180f) hue - 360f else hue
                luma[i] = 0.299f * r + 0.587f * g + 0.114f * b
                val pers = personArr?.get(i) ?: 0f
                val notPerson = 1f - pers

                val cb = 128f + (-0.168736f * r - 0.331264f * g + 0.5f * b) * 255f
                val cr = 128f + (0.5f * r - 0.418688f * g - 0.081312f * b) * 255f
                val skinColor = band(cb, 77f, 127f, 8f) * band(cr, 133f, 173f, 8f) *
                    band(hueS, -8f, 48f, 10f) *
                    smoothStep(0.12f, 0.25f, s) * (1f - smoothStep(0.75f, 0.9f, s)) *
                    smoothStep(0.18f, 0.32f, mx)
                skinA[i] = if (personArr != null) skinColor * smoothStep(0.2f, 0.6f, pers)
                else skinColor * 0.25f

                val blueHue = band(hue, 190f, 250f, 15f) * smoothStep(0.12f, 0.30f, s) *
                    smoothStep(0.30f, 0.50f, mx)
                val sky = blueHue * (1f - smoothStep(0.45f, 0.85f, yn)) * notPerson
                skyA[i] = sky
                watA[i] = band(hue, 170f, 235f, 15f) * smoothStep(0.15f, 0.35f, s) *
                    smoothStep(0.20f, 0.40f, mx) * smoothStep(0.40f, 0.70f, yn) *
                    notPerson * (1f - sky)

                folA[i] = band(hue, 65f, 165f, 15f) * smoothStep(0.16f, 0.32f, s) *
                    smoothStep(0.10f, 0.25f, mx) * notPerson

                sunA[i] = band(hueS, -20f, 45f, 10f) * smoothStep(0.35f, 0.55f, s) *
                    smoothStep(0.50f, 0.70f, mx) * (1f - smoothStep(0.5f, 0.8f, yn)) *
                    (1f - skinColor) * notPerson

                whtA[i] = smoothStep(0.82f, 0.92f, mx) * (1f - smoothStep(0.06f, 0.14f, s))
            }
        }

        // Structure map: where real detail lives (denoise/sharpen guide).
        val edge = FloatArray(n)
        for (y in 1 until h - 1) {
            for (x in 1 until w - 1) {
                val i = y * w + x
                val gx = luma[i + 1] - luma[i - 1]
                val gy = luma[i + w] - luma[i - w]
                edge[i] = smoothStep(0.03f, 0.14f, abs(gx) + abs(gy))
            }
        }
        val edgeSoft = MaskRefiner.boxFilter(edge, w, h, 2)

        val r = max(2, min(w, h) / 80)
        fun soft(f: FloatArray): FloatArray? {
            val b = MaskRefiner.boxFilter(f, w, h, r)
            return if (mean(b) < MIN_COVER) null else b
        }

        val skin = soft(skinA)
        val sky = soft(skyA)
        val foliage = soft(folA)
        val water = soft(watA)
        val sunset = soft(sunA)
        val white = soft(whtA)
        val person = personArr

        fun pct(f: FloatArray?): String = if (f == null) "0" else "%.1f".format(mean(f) * 100f)
        val summary = "person=${pct(person)}% skin=${pct(skin)}% sky=${pct(sky)}% " +
            "foliage=${pct(foliage)}% water=${pct(water)}% sunset=${pct(sunset)}% white=${pct(white)}%"
        Log.d(TAG, summary)
        return SceneMap(w, h, person, skin, sky, foliage, water, sunset, white, edgeSoft, summary)
    }

    private fun mean(a: FloatArray): Float {
        if (a.isEmpty()) return 0f
        var s = 0f
        for (v in a) s += v
        return s / a.size
    }
}
