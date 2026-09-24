package com.devfahim00.netcam.processing

import android.graphics.Bitmap
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.segmentation.subject.SubjectSegmentation
import com.google.mlkit.vision.segmentation.subject.SubjectSegmenter
import com.google.mlkit.vision.segmentation.subject.SubjectSegmenterOptions
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

/**
 * Thin wrapper around ML Kit Subject Segmentation.
 *
 * The API is "unbundled": the model is downloaded through Google Play services
 * the first time it is used on a device. If that fails (no Play services, no
 * network on first use) the app degrades gracefully and saves a normal photo.
 */
object SegmentationManager {

    private val segmenter: SubjectSegmenter by lazy {
        SubjectSegmentation.getClient(
            SubjectSegmenterOptions.Builder()
                .enableForegroundBitmap()
                .build()
        )
    }

    /**
     * Runs segmentation on [source] and returns the subject cut-out bitmap
     * (subject pixels, transparent everywhere else), or null on failure.
     */
    suspend fun subjectBitmap(source: Bitmap): Bitmap? =
        suspendCancellableCoroutine { cont ->
            try {
                segmenter
                    .process(InputImage.fromBitmap(source, 0))
                    .addOnSuccessListener { result -> cont.resume(result.foregroundBitmap) }
                    .addOnFailureListener { cont.resume(null) }
                    .addOnCanceledListener { cont.resume(null) }
            } catch (t: Throwable) {
                cont.resume(null)
            }
        }
}
