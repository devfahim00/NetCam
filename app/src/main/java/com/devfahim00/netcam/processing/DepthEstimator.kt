package com.devfahim00.netcam.processing

import android.content.Context
import android.graphics.Bitmap
import org.tensorflow.lite.Interpreter
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel

/**
 * Monocular (single-photo) depth estimation with MiDaS on TFLite.
 *
 * The network sees the photo at 256x256 and returns a *relative inverse depth*
 * map (bigger value = closer to the camera). We only need the ordering of
 * things in the scene — "this wall is behind that person" — not metric
 * distances, so relative depth is exactly what a portrait blur needs.
 *
 * The model file (`midas.tflite`) is fetched into `app/src/main/assets` by the
 * `downloadDepthModel` Gradle task, so it never has to live in git.
 * If it is missing or fails to load, [estimate] returns null and the portrait
 * pipeline transparently falls back to the old mask-distance blur.
 */
object DepthEstimator {

    private const val ASSET = "midas.tflite"
    const val SIZE = 256

    private val lock = Any()

    @Volatile
    private var interpreter: Interpreter? = null

    @Volatile
    private var failed = false

    /** Loads the model once (safe to call repeatedly, from any thread). */
    fun init(context: Context) {
        if (interpreter != null || failed) return
        synchronized(lock) {
            if (interpreter != null || failed) return
            try {
                val fd = context.applicationContext.assets.openFd(ASSET)
                val model = FileInputStream(fd.fileDescriptor).channel.map(
                    FileChannel.MapMode.READ_ONLY,
                    fd.startOffset,
                    fd.declaredLength
                )
                val options = Interpreter.Options().apply { setNumThreads(4) }
                interpreter = Interpreter(model, options)
            } catch (t: Throwable) {
                failed = true
            }
        }
    }

    val isAvailable: Boolean get() = interpreter != null

    /**
     * Returns a [SIZE] x [SIZE] depth map normalised to 0..1 (robust 2%..98%
     * percentile stretch, 1 = nearest), or null when the model is unavailable.
     */
    fun estimate(source: Bitmap): FloatArray? {
        val interp = interpreter ?: return null
        return try {
            val small = Bitmap.createScaledBitmap(source, SIZE, SIZE, true)
            val px = IntArray(SIZE * SIZE)
            small.getPixels(px, 0, SIZE, 0, 0, SIZE, SIZE)

            val input = ByteBuffer.allocateDirect(4 * SIZE * SIZE * 3).order(ByteOrder.nativeOrder())
            for (p in px) {
                val r = ((p shr 16) and 0xFF) / 255f
                val g = ((p shr 8) and 0xFF) / 255f
                val b = (p and 0xFF) / 255f
                input.putFloat((r - 0.485f) / 0.229f)
                input.putFloat((g - 0.456f) / 0.224f)
                input.putFloat((b - 0.406f) / 0.225f)
            }
            input.rewind()

            val output = ByteBuffer.allocateDirect(4 * SIZE * SIZE).order(ByteOrder.nativeOrder())
            synchronized(lock) { interp.run(input, output) }
            output.rewind()
            val depth = FloatArray(SIZE * SIZE)
            output.asFloatBuffer().get(depth)

            val sorted = depth.copyOf()
            sorted.sort()
            val lo = sorted[(sorted.size * 0.02f).toInt()]
            val hi = sorted[((sorted.size * 0.98f).toInt()).coerceAtMost(sorted.size - 1)]
            val span = hi - lo
            if (span < 1e-6f) return null
            for (i in depth.indices) {
                depth[i] = ((depth[i] - lo) / span).coerceIn(0f, 1f)
            }
            depth
        } catch (t: Throwable) {
            null
        }
    }
}
