package com.devfahim00.netcam.ui

import android.Manifest
import android.content.pm.PackageManager
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.FocusMeteringAction
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.devfahim00.netcam.R
import com.devfahim00.netcam.camera.CameraMode
import com.devfahim00.netcam.camera.FlashMode
import com.devfahim00.netcam.camera.FocusTarget
import com.devfahim00.netcam.processing.PortraitProcessor
import com.devfahim00.netcam.processing.PortraitResult
import com.devfahim00.netcam.save.ImageSaver
import com.devfahim00.netcam.ui.components.FlashPill
import com.devfahim00.netcam.ui.components.FocusIndicator
import com.devfahim00.netcam.ui.components.GalleryButton
import com.devfahim00.netcam.ui.components.GlassIconButton
import com.devfahim00.netcam.ui.components.GlassSurface
import com.devfahim00.netcam.ui.components.ModeSelector
import com.devfahim00.netcam.ui.components.ShutterButton
import com.devfahim00.netcam.ui.components.ZoomChip
import com.devfahim00.netcam.ui.theme.Accent
import com.devfahim00.netcam.util.await
import com.devfahim00.netcam.util.centerThumbnail
import com.devfahim00.netcam.util.decodeJpegBytes
import com.devfahim00.netcam.util.openAppSettings
import com.devfahim00.netcam.util.openGallery
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val PHOTO_MAX_SIDE = 4096
private const val PORTRAIT_MAX_SIDE = 2560

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

    // Re-check on resume (handles the user granting from system settings).
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

    // ----- camera / ui state -----
    var mode by remember { mutableStateOf(CameraMode.PHOTO) }
    var flashMode by remember { mutableStateOf(FlashMode.OFF) }
    var lensFacing by remember { mutableStateOf(CameraSelector.LENS_FACING_BACK) }
    var camera by remember { mutableStateOf<Camera?>(null) }
    var zoomRatio by remember { mutableStateOf(1f) }
    var isProcessing by remember { mutableStateOf(false) }
    var lastThumbnail by remember { mutableStateOf<ImageBitmap?>(null) }
    var lastUri by remember { mutableStateOf<Uri?>(null) }
    var focusTarget by remember { mutableStateOf<FocusTarget?>(null) }
    var toastRes by remember { mutableStateOf<Int?>(null) }

    val previewView = remember { PreviewView(context) }
    val imageCapture = remember {
        ImageCapture.Builder()
            .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
            .build()
    }

    LaunchedEffect(toastRes) {
        toastRes?.let {
            Toast.makeText(context, it, Toast.LENGTH_SHORT).show()
            toastRes = null
        }
    }

    // ----- bind / rebind camera -----
    LaunchedEffect(lensFacing) {
        val provider = ProcessCameraProvider.getInstance(context).await()
        val preview = Preview.Builder().build().also {
            it.setSurfaceProvider(previewView.surfaceProvider)
        }
        val selector = CameraSelector.Builder().requireLensFacing(lensFacing).build()
        provider.unbindAll()
        try {
            val cam = provider.bindToLifecycle(lifecycleOwner, selector, preview, imageCapture)
            camera = cam
            cam.cameraControl.enableTorch(flashMode == FlashMode.TORCH)
            val zoomState = cam.cameraInfo.zoomState.value
            zoomRatio = zoomState?.zoomRatio ?: 1f
            cam.cameraControl.setZoomRatio(zoomRatio)
        } catch (e: Exception) {
            toastRes = R.string.camera_error
        }
    }

    // Keep torch in sync with the flash mode.
    LaunchedEffect(flashMode) {
        camera?.cameraControl?.enableTorch(flashMode == FlashMode.TORCH)
    }

    if (!hasCameraPermission) {
        PermissionScreen(
            showSettings = permissionDeniedOnce,
            onRequest = { permissionLauncher.launch(Manifest.permission.CAMERA) },
            onOpenSettings = { context.openAppSettings() }
        )
        return
    }

    // ----- capture -----
    fun capturePhoto() {
        if (isProcessing) return
        isProcessing = true
        val portrait = mode == CameraMode.PORTRAIT
        haptic.performHapticFeedback(HapticFeedbackType.LongPress)

        imageCapture.flashMode = when (flashMode) {
            FlashMode.ON -> ImageCapture.FLASH_MODE_ON
            FlashMode.AUTO -> ImageCapture.FLASH_MODE_AUTO
            else -> ImageCapture.FLASH_MODE_OFF
        }

        imageCapture.takePicture(
            ContextCompat.getMainExecutor(context),
            object : ImageCapture.OnImageCapturedCallback() {
                override fun onCaptureSuccess(image: ImageProxy) {
                    val buffer = image.planes[0].buffer
                    val bytes = ByteArray(buffer.remaining()).also { buffer.get(it) }
                    val rotation = image.imageInfo.rotationDegrees
                    image.close()

                    scope.launch {
                        val maxSide = if (portrait) PORTRAIT_MAX_SIDE else PHOTO_MAX_SIDE
                        val decoded = withContext(Dispatchers.IO) {
                            decodeJpegBytes(bytes, rotation, maxSide)
                        }
                        if (decoded == null) {
                            isProcessing = false
                            toastRes = R.string.capture_failed
                            return@launch
                        }

                        var toSave = decoded
                        var messageRes = R.string.saved_to_gallery

                        if (portrait) {
                            when (val result = PortraitProcessor.process(decoded)) {
                                is PortraitResult.Success -> {
                                    toSave = result.bitmap
                                    messageRes = R.string.portrait_saved
                                }
                                is PortraitResult.NoSubject ->
                                    messageRes = R.string.subject_not_found
                                is PortraitResult.Failed ->
                                    messageRes = R.string.portrait_unavailable
                            }
                        }

                        val uri = withContext(Dispatchers.IO) {
                            ImageSaver.saveBitmap(context, toSave)
                        }
                        if (uri == null) {
                            isProcessing = false
                            toastRes = R.string.save_failed
                            return@launch
                        }

                        lastUri = uri
                        lastThumbnail = withContext(Dispatchers.Default) {
                            toSave.centerThumbnail(360).asImageBitmap()
                        }
                        isProcessing = false
                        toastRes = messageRes
                    }
                }

                override fun onError(exception: ImageCaptureException) {
                    isProcessing = false
                    toastRes = R.string.capture_failed
                }
            }
        )
    }

    // ----- layout -----
    Box(
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
                        val factory = previewView.meteringPointFactory
                        val point = factory.createPoint(offset.x, offset.y)
                        cam.cameraControl.startFocusAndMetering(
                            FocusMeteringAction.Builder(point).build()
                        )
                        focusTarget = FocusTarget(offset.x, offset.y, System.nanoTime())
                    }
                }
                .pointerInput(Unit) {
                    detectTransformGestures { _, _, zoom, _ ->
                        val cam = camera ?: return@detectTransformGestures
                        val zoomState = cam.cameraInfo.zoomState.value
                        val min = zoomState?.minZoomRatio ?: 1f
                        val max = zoomState?.maxZoomRatio ?: 5f
                        val target = (zoomRatio * zoom).coerceIn(min, max)
                        zoomRatio = target
                        cam.cameraControl.setZoomRatio(target)
                    }
                }
        )

        // Top & bottom scrims for control legibility
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
                .height(230.dp)
                .align(Alignment.BottomCenter)
                .background(
                    Brush.verticalGradient(
                        listOf(Color.Transparent, Color.Black.copy(alpha = 0.65f))
                    )
                )
        )

        FocusIndicator(
            target = focusTarget,
            onFinished = { focusTarget = null }
        )

        // ----- top bar -----
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .statusBarsPadding()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            GlassSurface(
                shape = RoundedCornerShape(50),
                contentPadding = 10.dp
            ) {
                Text(
                    text = stringResource(R.string.app_name).uppercase(),
                    letterSpacing = 3.sp,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color.White
                )
            }
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
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
                FlashPill(
                    flashMode = flashMode,
                    onCycle = {
                        flashMode = flashMode.next()
                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                    }
                )
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
                Text(
                    text = stringResource(R.string.portrait_hint),
                    fontSize = 12.sp,
                    color = Color.White.copy(alpha = 0.75f),
                    modifier = Modifier.padding(bottom = 10.dp)
                )
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

        // ----- portrait processing overlay -----
        AnimatedVisibility(
            visible = isProcessing && mode == CameraMode.PORTRAIT,
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
                            text = stringResource(R.string.processing_portrait),
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
    }
}
