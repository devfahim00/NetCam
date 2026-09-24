package com.devfahim00.netcam.processing

import android.graphics.Bitmap
import com.devfahim00.netcam.util.upscaleMask
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.segmentation.Segmentation
import com.google.mlkit.vision.segmentation.SegmentationMask
import com.google.mlkit.vision.segmentation.Segmenter
import com.google.mlkit.vision.segmentation.selfie.SelfieSegmenterOptions
import com.google.mlkit.vision.segmentation.subject.SubjectSegmentation
import com.google.mlkit.vision.segmentation.subject.SubjectSegmenter
import com.google.mlkit.vision.segmentation.subject.SubjectSegmenterOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.nio.ByteBuffer
import java.nio.FloatBuffer
import kotlin.coroutines.resume
import kotlin.math.min
import kotlin.math.roundToInt

/** Which engine produced the mask. */
enum class MaskEngine { SUBJECT, SELFIE, SUBJECT_ZOOM }

/** A soft (per-pixel 0..1 confidence) subject mask at [width] x [height]. */
class SoftMask(
    val alpha: FloatArray,
    val width: Int,
    val height: Int,
    val engine: MaskEngine
)

/**
 * Multi-engine subject detection with graceful fallbacks:
 *
 *  1. ML Kit Subject Segmentation — any salient object, best matting quality.
 *  2. ML Kit Selfie Segmentation — bundled/offline person mask (works without
 *     Play services model downloads, very reliable for portraits of people).
 *  3. Center-zoom retry of (1) — helps when the subject is small in frame.
 *
 * Models are warmed up as soon as the camera starts so the first portrait
 * capture does not race the one-time model download.
 */
object SegmentationManager {

    /** Segmentation working resolution cap (model sweet spot, keeps latency low). */
    const val MAX_INPUT_SIDE = 1280

    private const val SUBJECT_TIMEOUT_MS = 12_000L
    private const val SELFIE_TIMEOUT_MS = 8_000L

    private val subjectSegmenter: SubjectSegmenter by lazy {
        SubjectSegmentation.getClient(
            SubjectSegmenterOptions.Builder()
                .enableForegroundBitmap()
                .build()
        )
    }

    private val selfieSegmenter: Segmenter by lazy {
        Segmentation.getClient(
            SelfieSegmenterOptions.Builder()
                .setDetectorMode(SelfieSegmenterOptions.SINGLE_IMAGE_MODE)
                .enableRawSizeMask()
                .build()
        )
    }

    @Volatile
    private var warmedUp = false

    /**
     * Fires a tiny image through both engines at camera start-up. For the
     * unbundled subject model this triggers the one-time Play services
     * download early, so it is ready by the time the user takes a portrait.
     */
    suspend fun warmup() {
        if (warmedUp) return
        warmedUp = true
        val tiny = Bitmap.createBitmap(96, 96, Bitmap.Config.ARGB_8888)
        val px = IntArray(96 * 96)
        for (y in 0 until 96) {
            for (x in 0 until 96) {
                val v = ((x + y) * 255 / 190).coerceAtMost(255)
                px[y * 96 + x] = (0xFF shl 24) or (v shl 16) or (v shl 8) or v
            }
        }
        tiny.setPixels(px, 0, 96, 0, 0, 96, 96)
        runCatching { withTimeoutOrNull(6_000L) { subjectCutout(tiny) } }
        runCatching { withTimeoutOrNull(6_000L) { selfieMask(tiny) } }
        runCatching { tiny.recycle() }
    }

    /**
     * Runs the full detection chain on [source] (ideally capped to
     * [MAX_INPUT_SIDE]) and returns the best soft mask found, or null when no
     * believable subject exists anywhere in the engines.
     */
    suspend fun detect(source: Bitmap): SoftMask? = withContext(Dispatchers.Default) {
        val w = source.width
        val h = source.height

        // 1) Any-object subject segmentation.
        val cutout = runCatching {
            withTimeoutOrNull(SUBJECT_TIMEOUT_MS) { subjectCutout(source) }
        }.getOrNull()
        if (cutout != null) {
            val alpha = cutoutToAlpha(cutout, w, h)
            val coverage = coverage(alpha)
            if (coverage in 0.004f..0.985f) {
                return@withContext SoftMask(alpha, w, h, MaskEngine.SUBJECT)
            }
        }

        // 2) Person segmentation (bundled model — always available offline).
        val person = runCatching {
            withTimeoutOrNull(SELFIE_TIMEOUT_MS) { selfieMask(source) }
        }.getOrNull()
        if (person != null) {
            val alpha = personToFullSize(person, w, h)
            val coverage = coverage(alpha)
            if (coverage in 0.01f..0.999f) {
                return@withContext SoftMask(alpha, w, h, MaskEngine.SELFIE)
            }
        }

        // 3) Subject segmentation on a center crop (small-subject rescue).
        val cw = (w * 0.6f).roundToInt().coerceAtLeast(32)
        val ch = (h * 0.6f).roundToInt().coerceAtLeast(32)
        if (cw < w || ch < h) {
            val cx = ((w - cw) / 2f).roundToInt()
            val cy = ((h - ch) / 2f).roundToInt()
            val crop = Bitmap.createBitmap(source, cx, cy, cw, ch)
            val zoomCutout = runCatching {
                withTimeoutOrNull(SUBJECT_TIMEOUT_MS) { subjectCutout(crop) }
            }.getOrNull()
            if (zoomCutout != null) {
                val cropAlpha = cutoutToAlpha(zoomCutout, cw, ch)
                if (coverage(cropAlpha) in 0.01f..0.98f) {
                    val full = FloatArray(w * h)
                    val inside = upscaleMask(cropAlpha, cw, ch, cw, ch)
                    for (y in 0 until ch) {
                        val srcRow = y * cw
                        val dstRow = (cy + y) * w + cx
                        System.arraycopy(inside, srcRow, full, dstRow, cw)
                    }
                    if (coverage(full) >= 0.004f) {
                        return@withContext SoftMask(full, w, h, MaskEngine.SUBJECT_ZOOM)
                    }
                }
            }
        }

        // One retry of the plain subject engine (model may have just finished
        // downloading) before giving up.
        if (cutout == null) {
            delay(600L)
            val retry = runCatching {
                withTimeoutOrNull(SUBJECT_TIMEOUT_MS) { subjectCutout(source) }
            }.getOrNull()
            if (retry != null) {
                val alpha = cutoutToAlpha(retry, w, h)
                if (coverage(alpha) in 0.004f..0.985f) {
                    return@withContext SoftMask(alpha, w, h, MaskEngine.SUBJECT)
                }
            }
        }

        null
    }

    // ---------------------------------------------------------------- engines

    private suspend fun subjectCutout(source: Bitmap): Bitmap? =
        suspendCancellableCoroutine { cont ->
            try {
                subjectSegmenter
                    .process(InputImage.fromBitmap(source, 0))
                    .addOnSuccessListener { result -> cont.resume(result.foregroundBitmap) }
                    .addOnFailureListener { cont.resume(null) }
                    .addOnCanceledListener { cont.resume(null) }
            } catch (t: Throwable) {
                cont.resume(null)
            }
        }

    private suspend fun selfieMask(source: Bitmap): SegmentationMask? =
        suspendCancellableCoroutine { cont ->
            try {
                selfieSegmenter
                    .process(InputImage.fromBitmap(source, 0))
                    .addOnSuccessListener { mask -> cont.resume(mask) }
                    .addOnFailureListener { cont.resume(null) }
                    .addOnCanceledListener { cont.resume(null) }
            } catch (t: Throwable) {
                cont.resume(null)
            }
        }

    // ---------------------------------------------------------------- helpers

    /** Extracts the soft alpha channel of a subject cut-out bitmap. */
    private fun cutoutToAlpha(cutout: Bitmap, expectW: Int, expectH: Int): FloatArray {
        val cw = cutout.width
        val ch = cutout.height
        val px = IntArray(cw * ch)
        cutout.getPixels(px, 0, cw, 0, 0, cw, ch)
        val alpha = FloatArray(cw * ch) { i -> (px[i] ushr 24) / 255f }
        if (cw == expectW && ch == expectH) return alpha
        return upscaleMask(alpha, cw, ch, expectW, expectH)
    }

    /** Converts a selfie [Mask] into a full-size float mask. */
    private fun personToFullSize(mask: SegmentationMask, w: Int, h: Int): FloatArray {
        val mw = mask.width
        val mh = mask.height
        val n = mw * mh
        val alpha = FloatArray(n)
        val buffer: ByteBuffer = mask.buffer
        val floatBuffer = buffer as? FloatBuffer
        if (floatBuffer != null) {
            floatBuffer.rewind()
            val readable = min(floatBuffer.remaining(), n)
            if (readable > 0) floatBuffer.get(alpha, 0, readable)
        } else {
            val bytes = ByteBuffer.allocate(n)
            buffer.rewind()
            val count = min(buffer.remaining(), n)
            if (count > 0) {
                buffer.get(bytes.array(), 0, count)
            }
            for (i in 0 until count) {
                alpha[i] = (bytes.array()[i].toInt() and 0xFF) / 255f
            }
        }
        return if (mw == w && mh == h) alpha else upscaleMask(alpha, mw, mh, w, h)
    }

    /** Fraction of the frame covered by the mask (alpha > 0.5). */
    private fun coverage(alpha: FloatArray): Float {
        var sum = 0f
        for (v in alpha) if (v > 0.5f) sum += 1f
        return if (alpha.isEmpty()) 0f else sum / alpha.size
    }
}
