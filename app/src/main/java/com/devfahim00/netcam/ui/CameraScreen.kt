@file:OptIn(androidx.camera.camera2.interop.ExperimentalCamera2Interop::class)

package com.devfahim00.netcam.ui

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraMetadata
import android.hardware.camera2.CaptureRequest
import android.net.Uri
import android.os.SystemClock
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.camera2.interop.Camera2CameraControl
import androidx.camera.camera2.interop.Camera2CameraInfo
import androidx.camera.camera2.interop.CaptureRequestOptions
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.FocusMeteringAction
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.core.resolutionselector.AspectRatioStrategy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.devfahim00.netcam.R
import com.devfahim00.netcam.camera.AspectRatioOption
import com.devfahim00.netcam.camera.CameraMode
import com.devfahim00.netcam.camera.FlashMode
import com.devfahim00.netcam.camera.FocusResult
import com.devfahim00.netcam.camera.FocusTarget
import com.devfahim00.netcam.camera.PortraitStage
import com.devfahim00.netcam.processing.HdrFusion
import com.devfahim00.netcam.processing.NightDenoise
import com.devfahim00.netcam.processing.PhotoEnhancer
import com.devfahim00.netcam.processing.PortraitProcessor
import com.devfahim00.netcam.processing.PortraitResult
import com.devfahim00.netcam.processing.SegmentationManager
import com.devfahim00.netcam.save.ImageSaver
import com.devfahim00.netcam.settings.AppSettings
import com.devfahim00.netcam.settings.SettingsStore
import com.devfahim00.netcam.ui.components.AspectChip
import com.devfahim00.netcam.ui.components.BokehSlider
import com.devfahim00.netcam.ui.components.EvSlider
import com.devfahim00.netcam.ui.components.FlashPill
import com.devfahim00.netcam.ui.components.FocusIndicator
import com.devfahim00.netcam.ui.components.FocusModeChip
import com.devfahim00.netcam.ui.components.GalleryButton
import com.devfahim00.netcam.ui.components.GlassIconButton
import com.devfahim00.netcam.ui.components.GlassSliderVertical
import com.devfahim00.netcam.ui.components.GlassSurface
import com.devfahim00.netcam.ui.components.HdrChip
import com.devfahim00.netcam.ui.components.ModeSelector
import com.devfahim00.netcam.ui.components.NightChip
import com.devfahim00.netcam.ui.components.SettingsButton
import com.devfahim00.netcam.ui.components.SettingsSheet
import com.devfahim00.netcam.ui.components.ShutterButton
import com.devfahim00.netcam.ui.components.ZoomChip
import com.devfahim00.netcam.ui.components.focusDistanceLabel
import com.devfahim00.netcam.ui.theme.Accent
import com.devfahim00.netcam.util.await
import com.devfahim00.netcam.util.centerThumbnail
import com.devfahim00.netcam.util.cropCenterSquare
import com.devfahim00.netcam.util.decodeJpegBytesCapped
import com.devfahim00.netcam.util.mirrorHorizontal
import com.devfahim00.netcam.util.openAppSettings
import com.devfahim00.netcam.util.openGallery
import com.devfahim00.netcam.util.safeBitmapPixelBudget
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import kotlin.coroutines.resume
import kotlin.math.min
import kotlin.math.roundToInt

@Composable
fun CameraScreen() {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val scope = rememberCoroutineScope()
    val haptic = LocalHapticFeedback.current

    // ----- permission state -----
    var hasCameraPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
                PackageManager.PERMISSION_GRANTED
        )
    }
    var permissionDeniedOnce by rememberSaveable { mutableStateOf(false) }
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        hasCameraPermission = granted
        if (!granted) permissionDeniedOnce = true
    }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                hasCameraPermission =
                    ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
                        PackageManager.PERMISSION_GRANTED
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    // ----- settings (persisted) -----
    var settings by remember { mutableStateOf(SettingsStore.load(context)) }
    fun updateSettings(transform: (AppSettings) -> AppSettings) {
        settings = transform(settings)
        SettingsStore.save(context, settings)
    }

    // Bokeh strength lives in local state while dragging; it is only written
    // to SharedPreferences when the drag ends (no I/O per frame).
    var bokehLocal by remember(settings.bokehStrength) {
        mutableStateOf(settings.bokehStrength)
    }

    // ----- camera / ui state -----
    var mode by remember { mutableStateOf(CameraMode.PHOTO) }
    var flashMode by remember { mutableStateOf(FlashMode.OFF) }
    var lensFacing by remember { mutableStateOf(CameraSelector.LENS_FACING_BACK) }
    var camera by remember { mutableStateOf<Camera?>(null) }
    var zoomRatio by remember { mutableStateOf(1f) }
    var isProcessing by remember { mutableStateOf(false) }
    var processingStage by remember { mutableStateOf<String?>(null) }
    var lastThumbnail by remember { mutableStateOf<ImageBitmap?>(null) }
    var lastUri by remember { mutableStateOf<Uri?>(null) }
    var focusTarget by remember { mutableStateOf<FocusTarget?>(null) }
    var toastRes by remember { mutableStateOf<Int?>(null) }
    var showSettings by remember { mutableStateOf(false) }

    // Manual focus state (diopters; 0 = infinity).
    var mfEnabled by remember { mutableStateOf(false) }
    var mfDiopters by remember { mutableStateOf(0f) }
    var minFocusDistance by remember { mutableStateOf<Float?>(null) }

    // Exposure compensation (EV) — GCam style bias on top of auto exposure.
    var evIndex by remember { mutableStateOf(0) }
    var evRange by remember { mutableStateOf<IntRange?>(null) }
    var evStep by remember { mutableStateOf(1f) }
    var evTarget by remember { mutableStateOf<Offset?>(null) }
    var evLastInteraction by remember { mutableLongStateOf(0L) }

    // Ambient scene brightness from a tiny analysis stream (0..1), used to
    // trigger the multi-frame night noise reduction path.
    var ambientLuma by remember { mutableStateOf<Float?>(null) }
    val lumaGate = remember { AtomicLong(0L) }

    val previewView = remember { PreviewView(context) }

    val imageCapture = remember(settings.aspect, settings.hdr) {
        val ratioStrategy = when (settings.aspect) {
            AspectRatioOption.R4_3, AspectRatioOption.SQUARE ->
                AspectRatioStrategy.RATIO_4_3_FALLBACK_AUTO_STRATEGY
            AspectRatioOption.R16_9 ->
                AspectRatioStrategy.RATIO_16_9_FALLBACK_AUTO_STRATEGY
        }
        ImageCapture.Builder()
            .setResolutionSelector(
                ResolutionSelector.Builder()
                    .setAspectRatioStrategy(ratioStrategy)
                    .build()
            )
            .setCaptureMode(
                if (settings.hdr) {
                    ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY
                } else {
                    ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY
                }
            )
            .build()
    }

    // Tiny low-res analysis stream that continuously meters scene brightness.
    val analysisExecutor = remember { Executors.newSingleThreadExecutor() }
    DisposableEffect(Unit) {
        onDispose { analysisExecutor.shutdown() }
    }
    val imageAnalysis = remember {
        ImageAnalysis.Builder()
            .setResolutionSelector(
                ResolutionSelector.Builder()
                    .setResolutionStrategy(
                        ResolutionStrategy(
                            android.util.Size(160, 120),
                            ResolutionStrategy.FALLBACK_RULE_CLOSEST_LOWER_THEN_HIGHER
                        )
                    )
                    .build()
            )
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .build()
            .also { analysis ->
                analysis.setAnalyzer(analysisExecutor, ImageAnalysis.Analyzer { image ->
                    try {
                        val now = SystemClock.uptimeMillis()
                        if (now - lumaGate.get() >= 450L) {
                            lumaGate.set(now)
                            val plane = image.planes[0]
                            val buffer = plane.buffer
                            val rowStride = plane.rowStride
                            val pixelStride = plane.pixelStride
                            val iw = image.width
                            val ih = image.height
                            val stepX = maxOf(1, iw / 32)
                            val stepY = maxOf(1, ih / 24)
                            val cap = buffer.capacity()
                            var sum = 0L
                            var count = 0
                            var row = 0
                            while (row < ih) {
                                val rowBase = row * rowStride
                                var col = 0
                                while (col < iw) {
                                    val idx = rowBase + col * pixelStride
                                    if (idx < cap) {
                                        sum += buffer.get(idx).toInt() and 0xFF
                                        count++
                                    }
                                    col += stepX
                                }
                                row += stepY
                            }
                            if (count > 0) {
                                ambientLuma = sum.toFloat() / count / 255f
                            }
                        }
                    } catch (t: Throwable) {
                        // The meter must never crash the camera.
                    } finally {
                        image.close()
                    }
                })
            }
    }

    LaunchedEffect(toastRes) {
        toastRes?.let {
            Toast.makeText(context, it, Toast.LENGTH_SHORT).show()
            toastRes = null
        }
    }

    // Applies an exposure compensation index (clamped to the device range).
    fun applyEv(index: Int) {
        val range = evRange ?: return
        val clamped = index.coerceIn(range.first, range.last)
        evIndex = clamped
        camera?.cameraControl?.setExposureCompensationIndex(clamped)
    }

    // ----- bind / rebind camera -----
    LaunchedEffect(lensFacing, settings.aspect, settings.hdr) {
        val provider = ProcessCameraProvider.getInstance(context).await()

        previewView.scaleType = if (settings.aspect == AspectRatioOption.SQUARE) {
            PreviewView.ScaleType.FIT_CENTER
        } else {
            PreviewView.ScaleType.FILL_CENTER
        }

        val ratioStrategy = when (settings.aspect) {
            AspectRatioOption.R4_3, AspectRatioOption.SQUARE ->
                AspectRatioStrategy.RATIO_4_3_FALLBACK_AUTO_STRATEGY
            AspectRatioOption.R16_9 ->
                AspectRatioStrategy.RATIO_16_9_FALLBACK_AUTO_STRATEGY
        }
        val preview = Preview.Builder()
            .setResolutionSelector(
                ResolutionSelector.Builder()
                    .setAspectRatioStrategy(ratioStrategy)
                    .build()
            )
            .build()
            .also { it.setSurfaceProvider(previewView.surfaceProvider) }

        val selector = CameraSelector.Builder().requireLensFacing(lensFacing).build()
        provider.unbindAll()
        try {
            val cam = provider.bindToLifecycle(
                lifecycleOwner, selector, preview, imageCapture, imageAnalysis
            )
            camera = cam
            cam.cameraControl.enableTorch(flashMode == FlashMode.TORCH)
            val zoomState = cam.cameraInfo.zoomState.value
            zoomRatio = zoomState?.zoomRatio ?: 1f
            cam.cameraControl.setZoomRatio(zoomRatio)

            // Manual focus capability of this lens.
            minFocusDistance = runCatching {
                Camera2CameraInfo.from(cam.cameraInfo)
                    .getCameraCharacteristic(CameraCharacteristics.LENS_INFO_MINIMUM_FOCUS_DISTANCE)
            }.getOrNull()?.takeIf { it.isFinite() && it > 0f }
            if (minFocusDistance == null) mfEnabled = false
            mfDiopters = 0f

            // Exposure compensation range / step for the EV slider.
            val exposureState = cam.cameraInfo.exposureState
            evRange = if (exposureState.isExposureCompensationSupported) {
                val range = exposureState.exposureCompensationRange
                val rational = exposureState.exposureCompensationStep
                evStep = if (rational.denominator != 0) {
                    rational.numerator.toFloat() / rational.denominator.toFloat()
                } else {
                    1f
                }
                range.lower..range.upper
            } else {
                null
            }
            evIndex = 0
            evTarget = null
            focusTarget = null

            // Kick an initial AF/AE cycle so the camera converges right after
            // startup instead of waiting for the user's first tap.
            if (!mfEnabled && previewView.width > 0 && previewView.height > 0) {
                delay(450)
                runCatching {
                    val factory = previewView.meteringPointFactory
                    val point = factory.createPoint(
                        previewView.width / 2f,
                        previewView.height / 2f
                    )
                    cam.cameraControl.startFocusAndMetering(
                        FocusMeteringAction.Builder(point)
                            .setAutoCancelDuration(3, TimeUnit.SECONDS)
                            .build()
                    )
                }
            }

            // Warm up segmentation models early (triggers the one-time
            // Play services download for the subject model).
            launch { SegmentationManager.warmup() }
        } catch (e: Exception) {
            toastRes = R.string.camera_error
        }
    }

    // Keep torch in sync with the flash mode.
    LaunchedEffect(flashMode) {
        camera?.cameraControl?.enableTorch(flashMode == FlashMode.TORCH)
    }

    // ----- manual focus + hardware noise reduction (one shared override) -----
    // NOTE: Camera2CameraControl.setCaptureRequestOptions REPLACES all
    // previously set options, so the noise-reduction mode must be part of the
    // same options set as the manual focus keys or it would be dropped.
    LaunchedEffect(mfEnabled, camera, minFocusDistance) {
        val cam = camera ?: return@LaunchedEffect
        val c2 = Camera2CameraControl.from(cam.cameraControl)
        val nrHighQuality = runCatching {
            Camera2CameraInfo.from(cam.cameraInfo)
                .getCameraCharacteristic(
                    CameraCharacteristics.NOISE_REDUCTION_AVAILABLE_NOISE_REDUCTION_MODES
                )
                ?.contains(CameraMetadata.NOISE_REDUCTION_MODE_HIGH_QUALITY) ?: false
        }.getOrDefault(false)

        if (mfEnabled && (minFocusDistance ?: 0f) > 0f) {
            // Going manual: drop any running AF metering first.
            runCatching { cam.cameraControl.cancelFocusAndMetering() }
            snapshotFlow { mfDiopters }
                .collectLatest { diopters ->
                    delay(50)
                    runCatching {
                        val builder = CaptureRequestOptions.Builder()
                        if (nrHighQuality) {
                            builder.setCaptureRequestOption(
                                CaptureRequest.NOISE_REDUCTION_MODE,
                                CameraMetadata.NOISE_REDUCTION_MODE_HIGH_QUALITY
                            )
                        }
                        builder.setCaptureRequestOption(
                            CaptureRequest.CONTROL_AF_MODE,
                            CameraMetadata.CONTROL_AF_MODE_OFF
                        )
                        builder.setCaptureRequestOption(
                            CaptureRequest.LENS_FOCUS_DISTANCE,
                            diopters
                        )
                        c2.setCaptureRequestOptions(builder.build()).await()
                    }
                }
        } else {
            if (nrHighQuality) {
                runCatching {
                    val builder = CaptureRequestOptions.Builder()
                        .setCaptureRequestOption(
                            CaptureRequest.NOISE_REDUCTION_MODE,
                            CameraMetadata.NOISE_REDUCTION_MODE_HIGH_QUALITY
                        )
                    c2.setCaptureRequestOptions(builder.build()).await()
                }
            } else {
                runCatching { c2.clearCaptureRequestOptions().await() }
            }
        }
    }

    // Auto-hide the EV slider (and its focus ring) after a quiet period.
    LaunchedEffect(evTarget, evLastInteraction) {
        if (evTarget == null) return@LaunchedEffect
        delay(6000)
        evTarget = null
        focusTarget = null
    }

    if (!hasCameraPermission) {
        PermissionScreen(
            showSettings = permissionDeniedOnce,
            onRequest = { permissionLauncher.launch(Manifest.permission.CAMERA) },
            onOpenSettings = { context.openAppSettings() }
        )
        return
    }

    // ----- capture helpers -----

    /** Takes a single in-memory picture; returns JPEG bytes + rotation. */
    suspend fun takePictureBytes(): Pair<ByteArray, Int>? =
        suspendCancellableCoroutine { cont ->
            try {
                imageCapture.takePicture(
                    ContextCompat.getMainExecutor(context),
                    object : ImageCapture.OnImageCapturedCallback() {
                        override fun onCaptureSuccess(image: ImageProxy) {
                            val buffer = image.planes[0].buffer
                            val bytes = ByteArray(buffer.remaining()).also { buffer.get(it) }
                            val rotation = image.imageInfo.rotationDegrees
                            image.close()
                            if (cont.isActive) cont.resume(bytes to rotation)
                        }

                        override fun onError(exception: ImageCaptureException) {
                            if (cont.isActive) cont.resume(null)
                        }
                    }
                )
            } catch (t: Throwable) {
                if (cont.isActive) cont.resume(null)
            }
        }

    fun captureCapPixels(): Int = minOf(settings.quality.maxPixels, safeBitmapPixelBudget())

    suspend fun decodeShot(bytes: ByteArray, rotation: Int, maxPixels: Int): Bitmap? =
        withContext(Dispatchers.IO) { decodeJpegBytesCapped(bytes, rotation, maxPixels) }

    fun stageText(stage: PortraitStage): String = when (stage) {
        PortraitStage.DETECT -> context.getString(R.string.stage_detect)
        PortraitStage.REFINE -> context.getString(R.string.stage_refine)
        PortraitStage.BOKEH -> context.getString(R.string.stage_bokeh)
        PortraitStage.ENHANCE -> context.getString(R.string.stage_enhance)
    }

    /**
     * 3-frame HDR bracket centered on the user's current EV bias:
     * userEV / userEV-2 / userEV+2, fused with ghost-aware weights.
     */
    suspend fun captureHdrBracket(cam: Camera): Bitmap? {
        val control = cam.cameraControl
        val evState = cam.cameraInfo.exposureState
        val stepRational = evState.exposureCompensationStep
        if (stepRational.denominator == 0) return null
        val step = stepRational.numerator.toFloat() / stepRational.denominator.toFloat()
        if (step <= 0f) return null
        val range = evState.exposureCompensationRange
        val baseIdx = evIndex.coerceIn(range.lower, range.upper)

        fun indexFor(stops: Float): Int =
            (baseIdx + (stops / step).roundToInt()).coerceIn(range.lower, range.upper)

        suspend fun shotAt(stops: Float): Pair<ByteArray, Int>? {
            runCatching { control.setExposureCompensationIndex(indexFor(stops)).await() }
            delay(320)
            return takePictureBytes()
        }

        processingStage = context.getString(R.string.hdr_capturing, 1, 3)
        val ev0 = shotAt(0f)
        processingStage = context.getString(R.string.hdr_capturing, 2, 3)
        val evMinus = shotAt(-2f)
        processingStage = context.getString(R.string.hdr_capturing, 3, 3)
        val evPlus = shotAt(2f)
        // Restore the user's EV bias, not flat zero.
        runCatching { control.setExposureCompensationIndex(baseIdx).await() }

        val shots = listOfNotNull(ev0, evMinus, evPlus)
        if (shots.isEmpty()) return null

        val cap = minOf(HdrFusion.MAX_FUSION_PIXELS, safeBitmapPixelBudget())
        val frames = mutableListOf<Bitmap>()
        for (shot in shots) {
            decodeShot(shot.first, shot.second, cap)?.let { frames.add(it) }
        }
        if (frames.isEmpty()) return null
        if (frames.size < 2) return frames[0]

        processingStage = context.getString(R.string.hdr_fusing)
        return withContext(Dispatchers.Default) { HdrFusion.fuse(frames) } ?: frames[0]
    }

    /** Crop / mirror / portrait / enhance / save. */
    suspend fun processAndSave(captured: Bitmap) {
        val portrait = mode == CameraMode.PORTRAIT
        var toSave: Bitmap
        var messageRes = R.string.saved_to_gallery

        val prepared = withContext(Dispatchers.Default) {
            var bmp = captured
            if (settings.aspect == AspectRatioOption.SQUARE) {
                bmp = bmp.cropCenterSquare()
            }
            if (lensFacing == CameraSelector.LENS_FACING_FRONT && settings.mirrorFront) {
                bmp = bmp.mirrorHorizontal()
            }
            bmp
        }

        if (portrait) {
            processingStage = context.getString(R.string.stage_detect)
            when (val result = PortraitProcessor.process(
                prepared,
                bokehLocal * 1.5f,
                settings.autoEnhance
            ) { stage ->
                withContext(Dispatchers.Main) { processingStage = stageText(stage) }
            }) {
                is PortraitResult.Success -> {
                    toSave = result.bitmap
                    messageRes = R.string.portrait_saved
                }
                is PortraitResult.NoSubject -> {
                    messageRes = R.string.subject_not_found
                    toSave = if (settings.autoEnhance) {
                        processingStage = context.getString(R.string.stage_enhance)
                        withContext(Dispatchers.Default) {
                            PhotoEnhancer.enhance(prepared, portrait = false)
                        }
                    } else {
                        prepared
                    }
                }
                is PortraitResult.Failed -> {
                    messageRes = R.string.portrait_unavailable
                    toSave = if (settings.autoEnhance) {
                        processingStage = context.getString(R.string.stage_enhance)
                        withContext(Dispatchers.Default) {
                            PhotoEnhancer.enhance(prepared, portrait = false)
                        }
                    } else {
                        prepared
                    }
                }
            }
            processingStage = null
        } else if (settings.autoEnhance) {
            processingStage = context.getString(R.string.stage_enhance)
            toSave = withContext(Dispatchers.Default) {
                PhotoEnhancer.enhance(prepared, portrait = false)
            }
            processingStage = null
        } else {
            toSave = prepared
        }

        val uri = withContext(Dispatchers.IO) {
            ImageSaver.saveBitmap(context, toSave, settings.quality.jpegQuality)
        }
        if (uri == null) {
            isProcessing = false
            toastRes = R.string.save_failed
            return
        }

        lastUri = uri
        lastThumbnail = withContext(Dispatchers.Default) {
            toSave.centerThumbnail(360).asImageBitmap()
        }
        isProcessing = false
        toastRes = messageRes
    }

    // ----- capture -----
    fun capturePhoto() {
        if (isProcessing) return
        isProcessing = true
        haptic.performHapticFeedback(HapticFeedbackType.LongPress)

        val portrait = mode == CameraMode.PORTRAIT
        if (portrait) {
            processingStage = context.getString(R.string.processing_portrait)
        }

        imageCapture.flashMode = when (flashMode) {
            FlashMode.ON -> ImageCapture.FLASH_MODE_ON
            FlashMode.AUTO -> ImageCapture.FLASH_MODE_AUTO
            else -> ImageCapture.FLASH_MODE_OFF
        }

        val cam = camera
        val hdrPossible = settings.hdr &&
            flashMode == FlashMode.OFF &&
            cam != null &&
            runCatching {
                cam.cameraInfo.exposureState.isExposureCompensationSupported
            }.getOrDefault(false)

        scope.launch {
            var captured: Bitmap? = null

            if (hdrPossible && cam != null) {
                captured = captureHdrBracket(cam)
            } else {
                val shot = takePictureBytes()
                if (shot == null) {
                    isProcessing = false
                    processingStage = null
                    toastRes = R.string.capture_failed
                    return@launch
                }

                // Low light? Fire a quick burst and fuse it — that is the
                // only way to genuinely cut noise (~sqrt(N) SNR gain).
                val nightWanted = flashMode == FlashMode.OFF &&
                    (ambientLuma?.let { it < NightDenoise.LOW_LIGHT_LUMA } ?: false)

                if (nightWanted) {
                    processingStage = context.getString(R.string.night_capturing)
                    val extras = (0 until NightDenoise.EXTRA_FRAMES).mapNotNull {
                        takePictureBytes()
                    }
                    if (extras.isNotEmpty()) {
                        processingStage = context.getString(R.string.night_fusing)
                        val nightCap = minOf(
                            NightDenoise.MAX_FUSION_PIXELS,
                            safeBitmapPixelBudget() / 2
                        )
                        captured = withContext(Dispatchers.Default) {
                            NightDenoise.fuseFromJpegs(listOf(shot) + extras, nightCap)
                        }
                    }
                }

                if (captured == null) {
                    captured = decodeShot(shot.first, shot.second, captureCapPixels())
                    // Single-frame fallback: at least clean the chroma noise.
                    val single = captured
                    if (nightWanted && single != null) {
                        captured = withContext(Dispatchers.Default) {
                            if (NightDenoise.isLowLightBitmap(single)) {
                                NightDenoise.denoiseSingle(single)
                            } else {
                                single
                            }
                        }
                    }
                }
            }

            if (captured == null) {
                isProcessing = false
                processingStage = null
                toastRes = R.string.capture_failed
                return@launch
            }

            try {
                processAndSave(captured!!)
            } catch (t: Throwable) {
                isProcessing = false
                processingStage = null
                toastRes = R.string.capture_failed
            }
        }
    }

    // ----- layout -----
    val isNightScene = flashMode == FlashMode.OFF &&
        (ambientLuma?.let { it < NightDenoise.LOW_LIGHT_LUMA } ?: false)

    BoxWithConstraints(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
    ) {
        // Camera preview + gestures
        AndroidView(
            factory = { previewView },
            modifier = Modifier
                .fillMaxSize()
                .pointerInput(Unit) {
                    detectTapGestures { offset: Offset ->
                        val cam = camera ?: return@detectTapGestures
                        if (mfEnabled) return@detectTapGestures
                        val id = System.nanoTime()
                        focusTarget = FocusTarget(
                            offset.x, offset.y, id, FocusResult.PENDING
                        )
                        // The EV slider anchors right next to the ring.
                        evTarget = offset
                        evLastInteraction = SystemClock.uptimeMillis()

                        val factory = previewView.meteringPointFactory
                        val point = factory.createPoint(offset.x, offset.y)
                        val action = FocusMeteringAction.Builder(point)
                            .setAutoCancelDuration(4, TimeUnit.SECONDS)
                            .build()
                        val future = runCatching {
                            cam.cameraControl.startFocusAndMetering(action)
                        }.getOrNull()
                        if (future == null) {
                            focusTarget = focusTarget?.copy(result = FocusResult.SUCCESS)
                        } else {
                            scope.launch {
                                val result = runCatching { future.await() }.getOrNull()
                                if (focusTarget?.id == id) {
                                    focusTarget = focusTarget?.copy(
                                        result = if (result?.isFocusSuccessful == true) {
                                            FocusResult.SUCCESS
                                        } else {
                                            FocusResult.FAIL
                                        }
                                    )
                                }
                            }
                        }
                    }
                }
                .pointerInput(Unit) {
                    detectTransformGestures { _, _, zoom, _ ->
                        val cam = camera ?: return@detectTransformGestures
                        val zoomState = cam.cameraInfo.zoomState.value
                        val minZoom = zoomState?.minZoomRatio ?: 1f
                        val maxZoom = zoomState?.maxZoomRatio ?: 5f
                        val target = (zoomRatio * zoom).coerceIn(minZoom, maxZoom)
                        zoomRatio = target
                        cam.cameraControl.setZoomRatio(target)
                    }
                }
        )

        // Top & Bottom scrims for control legibility
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(130.dp)
                .align(Alignment.TopCenter)
                .background(
                    Brush.verticalGradient(
                        listOf(Color.Black.copy(alpha = 0.5f), Color.Transparent)
                    )
                )
        )
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(250.dp)
                .align(Alignment.BottomCenter)
                .background(
                    Brush.verticalGradient(
                        listOf(Color.Transparent, Color.Black.copy(alpha = 0.65f))
                    )
                )
        )

        // 1:1 framing guide (preview is letterboxed to 4:3, so this square
        // matches the exact capture area).
        if (settings.aspect == AspectRatioOption.SQUARE) {
            Canvas(Modifier.fillMaxSize()) {
                val side = min(size.width, size.height)
                val left = (size.width - side) / 2f
                val top = (size.height - side) / 2f
                val dim = Color.Black.copy(alpha = 0.55f)
                drawRect(
                    color = dim,
                    topLeft = Offset(0f, 0f),
                    size = Size(size.width, top)
                )
                drawRect(
                    color = dim,
                    topLeft = Offset(0f, top + side),
                    size = Size(size.width, size.height - top - side)
                )
                drawRect(
                    color = dim,
                    topLeft = Offset(0f, top),
                    size = Size(left, side)
                )
                drawRect(
                    color = dim,
                    topLeft = Offset(left + side, top),
                    size = Size(size.width - left - side, side)
                )
                drawRect(
                    color = Color.White.copy(alpha = 0.65f),
                    topLeft = Offset(left, top),
                    size = Size(side, side),
                    style = Stroke(width = 1.5.dp.toPx())
                )
            }
        }

        FocusIndicator(target = focusTarget)

        // ----- GCam-style exposure slider (appears after tap-to-focus) -----
        val currentEvRange = evRange
        val currentAnchor = evTarget
        if (currentAnchor != null && currentEvRange != null &&
            currentEvRange.last > currentEvRange.first && !mfEnabled
        ) {
            EvSlider(
                evIndex = evIndex,
                minIndex = currentEvRange.first,
                maxIndex = currentEvRange.last,
                step = evStep,
                anchor = currentAnchor,
                areaWidthPx = constraints.maxWidth,
                areaHeightPx = constraints.maxHeight,
                onValueChange = { idx ->
                    evLastInteraction = SystemClock.uptimeMillis()
                    applyEv(idx)
                },
                onAutoReset = {
                    evLastInteraction = SystemClock.uptimeMillis()
                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                    applyEv(0)
                },
                onInteraction = { evLastInteraction = SystemClock.uptimeMillis() }
            )
        }

        // ----- manual focus slider -----
        AnimatedVisibility(
            visible = mfEnabled && (minFocusDistance ?: 0f) > 0f,
            modifier = Modifier.align(Alignment.CenterEnd),
            enter = fadeIn(tween(180)),
            exit = fadeOut(tween(180))
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.padding(end = 14.dp)
            ) {
                GlassSliderVertical(
                    fraction = (mfDiopters / (minFocusDistance ?: 1f)).coerceIn(0f, 1f),
                    onFractionChange = { fraction ->
                        minFocusDistance?.let { mfDiopters = fraction * it }
                    }
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    text = focusDistanceLabel(mfDiopters, minFocusDistance ?: 1f),
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color.White,
                    textAlign = TextAlign.Center,
                    modifier = Modifier
                        .clip(RoundedCornerShape(10.dp))
                        .background(Color.Black.copy(alpha = 0.35f))
                        .padding(horizontal = 8.dp, vertical = 4.dp)
                )
            }
        }

        // ----- top bar -----
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .statusBarsPadding()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                if (zoomRatio > 1.02f) {
                    ZoomChip(
                        zoom = zoomRatio,
                        onReset = {
                            zoomRatio = 1f
                            camera?.cameraControl?.setZoomRatio(1f)
                        }
                    )
                }
                if (isNightScene) {
                    NightChip()
                }
            }
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                AspectChip(
                    aspect = settings.aspect,
                    onCycle = {
                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                        updateSettings { it.copy(aspect = it.aspect.next()) }
                    }
                )
                HdrChip(
                    enabled = settings.hdr,
                    onToggle = {
                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                        updateSettings { it.copy(hdr = !it.hdr) }
                    }
                )
                if ((minFocusDistance ?: 0f) > 0f) {
                    FocusModeChip(
                        manual = mfEnabled,
                        onToggle = {
                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                            mfEnabled = !mfEnabled
                        }
                    )
                }
                FlashPill(
                    flashMode = flashMode,
                    onCycle = {
                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                        flashMode = flashMode.next()
                    }
                )
                SettingsButton(onClick = { showSettings = true })
            }
        }

        // ----- bottom controls -----
        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 18.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            AnimatedVisibility(
                visible = mode == CameraMode.PORTRAIT,
                enter = fadeIn(tween(180)),
                exit = fadeOut(tween(180))
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    BokehSlider(
                        strength = bokehLocal,
                        onChange = { value -> bokehLocal = value },
                        onDragFinished = {
                            updateSettings { it.copy(bokehStrength = bokehLocal) }
                        },
                        modifier = Modifier
                            .fillMaxWidth(0.78f)
                            .padding(bottom = 10.dp)
                    )
                    Text(
                        text = stringResource(R.string.portrait_hint),
                        fontSize = 12.sp,
                        color = Color.White.copy(alpha = 0.75f),
                        modifier = Modifier.padding(bottom = 10.dp)
                    )
                }
            }
            ModeSelector(
                mode = mode,
                onModeChange = {
                    mode = it
                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                }
            )
            Spacer(Modifier.height(24.dp))
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 26.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier.weight(1f),
                    contentAlignment = Alignment.Center
                ) {
                    GalleryButton(
                        thumbnail = lastThumbnail,
                        onClick = { context.openGallery(lastUri) }
                    )
                }
                Box(
                    modifier = Modifier.weight(1f),
                    contentAlignment = Alignment.Center
                ) {
                    ShutterButton(
                        isBusy = isProcessing,
                        isPortrait = mode == CameraMode.PORTRAIT,
                        onCapture = ::capturePhoto
                    )
                }
                Box(
                    modifier = Modifier.weight(1f),
                    contentAlignment = Alignment.Center
                ) {
                    GlassIconButton(
                        size = 56.dp,
                        onClick = {
                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                            mfEnabled = false
                            lensFacing = if (lensFacing == CameraSelector.LENS_FACING_BACK) {
                                CameraSelector.LENS_FACING_FRONT
                            } else {
                                CameraSelector.LENS_FACING_BACK
                            }
                        }
                    ) {
                        Icon(
                            painter = painterResource(R.drawable.ic_flip),
                            contentDescription = stringResource(R.string.cd_switch_camera),
                            tint = Color.White,
                            modifier = Modifier.size(24.dp)
                        )
                    }
                }
            }
        }

        // ----- processing overlay -----
        AnimatedVisibility(
            visible = isProcessing && (mode == CameraMode.PORTRAIT || processingStage != null),
            enter = fadeIn(tween(150)),
            exit = fadeOut(tween(150))
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.45f))
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = {}
                    ),
                contentAlignment = Alignment.Center
            ) {
                GlassSurface(
                    shape = RoundedCornerShape(28.dp),
                    contentPadding = 28.dp
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        CircularProgressIndicator(
                            color = Accent,
                            strokeWidth = 3.dp,
                            modifier = Modifier.size(40.dp)
                        )
                        Spacer(Modifier.height(16.dp))
                        Text(
                            text = processingStage
                                ?: stringResource(R.string.processing_portrait),
                            color = Color.White,
                            fontSize = 15.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                        Spacer(Modifier.height(6.dp))
                        Text(
                            text = stringResource(R.string.processing_subtitle),
                            color = Color.White.copy(alpha = 0.6f),
                            fontSize = 12.sp
                        )
                    }
                }
            }
        }

        // ----- settings sheet -----
        SettingsSheet(
            visible = showSettings,
            settings = settings,
            onDismiss = { showSettings = false },
            onChange = { newSettings -> updateSettings { newSettings } }
        )
    }
}
