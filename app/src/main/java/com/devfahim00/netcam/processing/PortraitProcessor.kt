package com.devfahim00.netcam.processing

import android.graphics.Bitmap
import com.devfahim00.netcam.camera.PortraitStage
import com.devfahim00.netcam.util.bitmapToGrayFloat
import com.devfahim00.netcam.util.fitToArea
import com.devfahim00.netcam.util.safeBitmapPixelBudget
import com.devfahim00.netcam.util.scaleLongestSideTo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Outcome of a portrait capture. */
sealed class PortraitResult {
    data class Success(val bitmap: Bitmap) : PortraitResult()
    object NoSubject : PortraitResult()
    object Failed : PortraitResult()
}

/**
 * GCam-grade portrait pipeline:
 *
 *  1. detect the subject (multi-engine, see [SegmentationManager]),
 *  2. refine the soft mask with an edge-aware guided filter + feathering
 *     (see [MaskRefiner]) — this is what fixes jagged/haloed edges,
 *  3. composite a depth-graded 3-layer bokeh behind the sharp subject
 *     (see [BokehCompositor]) at capture resolution,
 *  4. run the punchy "social-ready" color pipeline (see [PhotoEnhancer]).
 */
object PortraitProcessor {

    private const val WORKING_SIDE = 1280

    /**
     * @param source decoded, upright capture (any size).
     * @param strength user bokeh strength, 0..1.5.
     * @param enhance apply the punchy color pipeline.
     * @param onStage invoked (on the main thread) as processing progresses.
     */
    suspend fun process(
        source: Bitmap,
        strength: Float,
        enhance: Boolean,
        onStage: suspend (PortraitStage) -> Unit = {}
    ): PortraitResult = withContext(Dispatchers.Default) {
        try {
            onStage(PortraitStage.DETECT)

            val base = source.fitToArea(safeBitmapPixelBudget())
            val safeBase = if (base.config == Bitmap.Config.ARGB_8888) {
                base
            } else {
                base.copy(Bitmap.Config.ARGB_8888, false)
            }

            val working = scaleLongestSideTo(safeBase, WORKING_SIDE)
            val workW = working.width
            val workH = working.height

            val soft = SegmentationManager.detect(working)
                ?: return@withContext PortraitResult.NoSubject

            onStage(PortraitStage.REFINE)
            val gray = bitmapToGrayFloat(working)
            val alpha = MaskRefiner.refine(gray, soft.alpha, workW, workH)
            if (MaskRefiner.coverage(alpha) < 0.004f) {
                return@withContext PortraitResult.NoSubject
            }
            val dist = MaskRefiner.distanceField(
                alpha, workW, workH, maxOf(workW, workH) / 6f
            )

            onStage(PortraitStage.BOKEH)
            var output = BokehCompositor.composite(safeBase, workW, workH, alpha, dist, strength)

            if (enhance) {
                onStage(PortraitStage.ENHANCE)
                output = PhotoEnhancer.enhance(output, portrait = true)
            }

            PortraitResult.Success(output)
        } catch (t: Throwable) {
            PortraitResult.Failed
        }
    }
}
