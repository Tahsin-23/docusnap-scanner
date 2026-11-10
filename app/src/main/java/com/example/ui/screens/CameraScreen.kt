package com.example.ui.screens

import android.Manifest
import android.content.pm.PackageManager
import android.view.ViewGroup
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Cameraswitch
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.FlashOff
import androidx.compose.material.icons.filled.FlashOn
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.outlined.NoPhotography
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import coil.compose.AsyncImage
import com.example.R
import com.example.cv.DocumentDetector
import com.example.model.NormalizedQuad
import com.example.model.ScanMode
import com.example.ui.theme.ScannerAmber
import com.example.ui.theme.ScannerCyan
import com.example.ui.theme.ScannerEmerald
import java.io.File
import java.util.concurrent.Executors
import kotlin.math.min

/**
 * Full-featured CameraX capture screen supporting:
 * - On-demand CAMERA permission request & graceful recovery
 * - Rear-facing camera default + switching between available cameras
 * - Live real-time document boundary detection overlay & stability auto-capture
 * - Flashlight (torch) toggle when supported
 * - Zoom slider & presets when supported
 * - Immediate post-capture review with Retake, Add Another Page, and Use Scan / Photo
 * - Automatic cleanup of camera streams when closing
 */
@Composable
fun CameraScreen(
    scanMode: ScanMode,
    existingPageCount: Int,
    initialAutoCapture: Boolean,
    onCapturedFileConfirmed: (File, Boolean) -> Unit, // (file, stayForMorePages)
    onImportUris: (List<android.net.Uri>) -> Unit,
    onDoneToEditor: () -> Unit,
    onCancel: () -> Unit
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    BackHandler(onBack = onCancel)

    var hasCameraPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
                PackageManager.PERMISSION_GRANTED
        )
    }
    var permissionDeniedOnce by remember { mutableStateOf(false) }

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        hasCameraPermission = granted
        if (!granted) permissionDeniedOnce = true
    }

    val galleryLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickMultipleVisualMedia(maxItems = 25)
    ) { uris ->
        if (uris.isNotEmpty()) {
            onImportUris(uris)
        }
    }

    LaunchedEffect(Unit) {
        if (!hasCameraPermission) {
            permissionLauncher.launch(Manifest.permission.CAMERA)
        }
    }

    if (!hasCameraPermission) {
        CameraPermissionFallbackView(
            wasDenied = permissionDeniedOnce,
            onRequestPermission = { permissionLauncher.launch(Manifest.permission.CAMERA) },
            onPickFromGallery = {
                galleryLauncher.launch(
                    PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                )
            },
            onCancel = onCancel
        )
        return
    }

    // CameraX runtime states
    var lensFacing by remember { mutableIntStateOf(CameraSelector.LENS_FACING_BACK) }
    var canSwitchCamera by remember { mutableStateOf(false) }
    var cameraError by remember { mutableStateOf<String?>(null) }
    var activeCamera by remember { mutableStateOf<Camera?>(null) }
    var imageCaptureUseCase by remember { mutableStateOf<ImageCapture?>(null) }

    var hasFlashUnit by remember { mutableStateOf(false) }
    var isTorchEnabled by remember { mutableStateOf(false) }
    var minZoomRatio by remember { mutableFloatStateOf(1f) }
    var maxZoomRatio by remember { mutableFloatStateOf(1f) }
    var currentZoomRatio by remember { mutableFloatStateOf(1f) }

    // Live boundary detection state (used in ScanMode.DOCUMENT)
    var liveQuad by remember { mutableStateOf<NormalizedQuad?>(null) }
    var analysisAspectRatio by remember { mutableFloatStateOf(0.75f) } // width / height
    var stableFrameCount by remember { mutableIntStateOf(0) }
    var autoCaptureEnabled by remember { mutableStateOf(initialAutoCapture && scanMode == ScanMode.DOCUMENT) }
    var isCapturing by remember { mutableStateOf(false) }
    var pendingReviewFile by remember { mutableStateOf<File?>(null) }

    val analysisExecutor = remember { Executors.newSingleThreadExecutor() }
    val previewView = remember {
        PreviewView(context).apply {
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
            scaleType = PreviewView.ScaleType.FIT_CENTER
            implementationMode = PreviewView.ImplementationMode.COMPATIBLE
        }
    }

    fun triggerCapture() {
        val capture = imageCaptureUseCase ?: return
        if (isCapturing || pendingReviewFile != null) return
        isCapturing = true

        val outFile = File(context.cacheDir, "raw_capture_${System.currentTimeMillis()}.jpg")
        val outputOptions = ImageCapture.OutputFileOptions.Builder(outFile).build()

        capture.takePicture(
            outputOptions,
            ContextCompat.getMainExecutor(context),
            object : ImageCapture.OnImageSavedCallback {
                override fun onImageSaved(outputFileResults: ImageCapture.OutputFileResults) {
                    isCapturing = false
                    stableFrameCount = 0
                    pendingReviewFile = outFile
                }

                override fun onError(exception: ImageCaptureException) {
                    isCapturing = false
                    cameraError = "Capture failed: ${exception.message ?: "Unknown camera error"}"
                }
            }
        )
    }

    // Bind / rebind CameraX when lensFacing changes, and release tracks when screen closes
    DisposableEffect(lifecycleOwner, lensFacing) {
        val providerFuture = ProcessCameraProvider.getInstance(context)
        var boundProvider: ProcessCameraProvider? = null

        providerFuture.addListener({
            try {
                val provider = providerFuture.get()
                boundProvider = provider
                provider.unbindAll()

                val hasBack = runCatching {
                    provider.hasCamera(CameraSelector.DEFAULT_BACK_CAMERA)
                }.getOrDefault(false)
                val hasFront = runCatching {
                    provider.hasCamera(CameraSelector.DEFAULT_FRONT_CAMERA)
                }.getOrDefault(false)
                canSwitchCamera = hasBack && hasFront

                if (!hasBack && !hasFront) {
                    cameraError = context.getString(R.string.camera_unavailable_message)
                    return@addListener
                }

                val effectiveLens = when {
                    lensFacing == CameraSelector.LENS_FACING_BACK && hasBack -> CameraSelector.LENS_FACING_BACK
                    lensFacing == CameraSelector.LENS_FACING_FRONT && hasFront -> CameraSelector.LENS_FACING_FRONT
                    hasBack -> CameraSelector.LENS_FACING_BACK
                    else -> CameraSelector.LENS_FACING_FRONT
                }

                val selector = CameraSelector.Builder().requireLensFacing(effectiveLens).build()
                val preview = Preview.Builder().build().also {
                    it.surfaceProvider = previewView.surfaceProvider
                }
                val capture = ImageCapture.Builder()
                    .setCaptureMode(ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY)
                    .build()
                imageCaptureUseCase = capture

                val analysis = ImageAnalysis.Builder()
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .build()

                var lastAnalyzedMs = 0L
                analysis.setAnalyzer(analysisExecutor) { imageProxy ->
                    try {
                        val now = System.currentTimeMillis()
                        if (scanMode == ScanMode.DOCUMENT && pendingReviewFile == null && !isCapturing && now - lastAnalyzedMs >= 160L) {
                            lastAnalyzedMs = now
                            val plane = imageProxy.planes.firstOrNull()
                            if (plane != null) {
                                val buffer = plane.buffer
                                val bytes = ByteArray(buffer.remaining())
                                buffer.get(bytes)
                                val rot = imageProxy.imageInfo.rotationDegrees
                                val uprightW = if (rot == 90 || rot == 270) imageProxy.height else imageProxy.width
                                val uprightH = if (rot == 90 || rot == 270) imageProxy.width else imageProxy.height
                                val det = DocumentDetector.detectFromYPlane(
                                    yBytes = bytes,
                                    srcWidth = imageProxy.width,
                                    srcHeight = imageProxy.height,
                                    rowStride = plane.rowStride,
                                    pixelStride = plane.pixelStride,
                                    rotationDegrees = rot
                                )

                                previewView.post {
                                    analysisAspectRatio = uprightW.toFloat() / uprightH.coerceAtLeast(1).toFloat()
                                    if (det.isDetected && det.quad != null) {
                                        val prev = liveQuad
                                        liveQuad = det.quad
                                        if (prev != null && prev.maxCornerDelta(det.quad) < 0.036f) {
                                            stableFrameCount = (stableFrameCount + 1).coerceAtMost(7)
                                        } else {
                                            stableFrameCount = 1
                                        }
                                    } else {
                                        liveQuad = null
                                        stableFrameCount = 0
                                    }
                                }
                            }
                        }
                    } catch (_: Exception) {
                        // Never crash on frame analysis errors
                    } finally {
                        imageProxy.close()
                    }
                }

                val camera = provider.bindToLifecycle(
                    lifecycleOwner,
                    selector,
                    preview,
                    capture,
                    analysis
                )
                activeCamera = camera
                cameraError = null
                hasFlashUnit = camera.cameraInfo.hasFlashUnit()
                isTorchEnabled = false
                camera.cameraInfo.zoomState.value?.let { zs ->
                    minZoomRatio = zs.minZoomRatio
                    maxZoomRatio = zs.maxZoomRatio
                    currentZoomRatio = zs.zoomRatio
                }
            } catch (e: Exception) {
                cameraError = e.localizedMessage ?: context.getString(R.string.camera_unavailable_message)
            }
        }, ContextCompat.getMainExecutor(context))

        onDispose {
            runCatching { boundProvider?.unbindAll() }
            activeCamera = null
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            runCatching { analysisExecutor.shutdown() }
        }
    }

    // Trigger Auto-Capture when stable across consecutive frames
    LaunchedEffect(stableFrameCount, autoCaptureEnabled, pendingReviewFile) {
        if (scanMode == ScanMode.DOCUMENT && autoCaptureEnabled && stableFrameCount >= 6 && pendingReviewFile == null && !isCapturing) {
            triggerCapture()
        }
    }

    // Post-capture Review Screen (Capture / Retake / Cancel / Add Page)
    val reviewFile = pendingReviewFile
    if (reviewFile != null) {
        CapturedReviewOverlay(
            capturedFile = reviewFile,
            scanMode = scanMode,
            existingPageCount = existingPageCount,
            onRetake = {
                reviewFile.delete()
                pendingReviewFile = null
                stableFrameCount = 0
            },
            onScanAnotherPage = {
                pendingReviewFile = null
                onCapturedFileConfirmed(reviewFile, true)
            },
            onConfirmAndContinue = {
                pendingReviewFile = null
                onCapturedFileConfirmed(reviewFile, false)
            },
            onCancelAll = {
                reviewFile.delete()
                pendingReviewFile = null
                onCancel()
            }
        )
        return
    }

    if (cameraError != null) {
        CameraUnavailableView(
            errorMessage = cameraError ?: stringResource(R.string.camera_unavailable_message),
            onImportFromGallery = {
                galleryLauncher.launch(
                    PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                )
            },
            onCancel = onCancel
        )
        return
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .testTag("camera_preview_root")
    ) {
        AndroidView(
            factory = { previewView },
            modifier = Modifier.fillMaxSize()
        )

        // Live Document Boundary Overlay (or Photography Rule-of-Thirds Grid)
        Canvas(modifier = Modifier.fillMaxSize()) {
            val viewW = size.width
            val viewH = size.height
            if (viewW <= 0f || viewH <= 0f) return@Canvas

            val imgAspect = analysisAspectRatio.coerceAtLeast(0.1f)
            val scale = min(viewW / imgAspect, viewH)
            val drawnW = imgAspect * scale
            val drawnH = scale
            val offsetX = (viewW - drawnW) * 0.5f
            val offsetY = (viewH - drawnH) * 0.5f

            if (scanMode == ScanMode.DOCUMENT) {
                val q = liveQuad
                if (q != null) {
                    val isStable = stableFrameCount >= 4
                    val strokeColor = if (isStable) ScannerEmerald else ScannerCyan
                    val fillColor = strokeColor.copy(alpha = 0.18f)

                    val pTL = Offset(offsetX + q.topLeft.x * drawnW, offsetY + q.topLeft.y * drawnH)
                    val pTR = Offset(offsetX + q.topRight.x * drawnW, offsetY + q.topRight.y * drawnH)
                    val pBR = Offset(offsetX + q.bottomRight.x * drawnW, offsetY + q.bottomRight.y * drawnH)
                    val pBL = Offset(offsetX + q.bottomLeft.x * drawnW, offsetY + q.bottomLeft.y * drawnH)

                    val path = Path().apply {
                        moveTo(pTL.x, pTL.y)
                        lineTo(pTR.x, pTR.y)
                        lineTo(pBR.x, pBR.y)
                        lineTo(pBL.x, pBL.y)
                        close()
                    }
                    drawPath(path = path, color = fillColor)
                    drawPath(
                        path = path,
                        color = strokeColor,
                        style = Stroke(width = 3.5.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round)
                    )
                    listOf(pTL, pTR, pBR, pBL).forEach { corner ->
                        drawCircle(color = Color.White, radius = 8.dp.toPx(), center = corner)
                        drawCircle(color = strokeColor, radius = 5.5.dp.toPx(), center = corner)
                    }
                }
            } else {
                // Subtle framing grid for Take Photo mode
                val gridColor = Color.White.copy(alpha = 0.22f)
                val thirdW = viewW / 3f
                val thirdH = viewH / 3f
                drawLine(gridColor, Offset(thirdW, 0f), Offset(thirdW, viewH), strokeWidth = 1.dp.toPx())
                drawLine(gridColor, Offset(thirdW * 2f, 0f), Offset(thirdW * 2f, viewH), strokeWidth = 1.dp.toPx())
                drawLine(gridColor, Offset(0f, thirdH), Offset(viewW, thirdH), strokeWidth = 1.dp.toPx())
                drawLine(gridColor, Offset(0f, thirdH * 2f), Offset(viewW, thirdH * 2f), strokeWidth = 1.dp.toPx())
            }
        }

        // Top HUD Controls
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .statusBarsPadding()
                .padding(horizontal = 16.dp, vertical = 12.dp)
                .align(Alignment.TopCenter),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(
                    onClick = onCancel,
                    modifier = Modifier
                        .size(48.dp)
                        .background(Color.Black.copy(alpha = 0.5f), CircleShape)
                        .testTag("camera_cancel_button")
                ) {
                    Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.camera_cancel), tint = Color.White)
                }

                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    if (scanMode == ScanMode.DOCUMENT) {
                        FilterChip(
                            selected = autoCaptureEnabled,
                            onClick = { autoCaptureEnabled = !autoCaptureEnabled },
                            leadingIcon = {
                                Icon(
                                    imageVector = Icons.Filled.AutoAwesome,
                                    contentDescription = null,
                                    modifier = Modifier.size(16.dp)
                                )
                            },
                            label = {
                                Text(if (autoCaptureEnabled) "Auto Capture" else "Manual")
                            },
                            colors = FilterChipDefaults.filterChipColors(
                                containerColor = Color.Black.copy(alpha = 0.55f),
                                labelColor = Color.White,
                                iconColor = Color.White,
                                selectedContainerColor = ScannerCyan.copy(alpha = 0.88f),
                                selectedLabelColor = Color.White,
                                selectedLeadingIconColor = Color.White
                            ),
                            modifier = Modifier.testTag("camera_auto_toggle")
                        )
                    }

                    if (hasFlashUnit) {
                        IconButton(
                            onClick = {
                                val next = !isTorchEnabled
                                isTorchEnabled = next
                                activeCamera?.cameraControl?.enableTorch(next)
                            },
                            modifier = Modifier
                                .size(48.dp)
                                .background(
                                    if (isTorchEnabled) ScannerAmber else Color.Black.copy(alpha = 0.5f),
                                    CircleShape
                                )
                                .testTag("camera_flash_button")
                        ) {
                            Icon(
                                imageVector = if (isTorchEnabled) Icons.Filled.FlashOn else Icons.Filled.FlashOff,
                                contentDescription = stringResource(R.string.camera_toggle_flash),
                                tint = Color.White
                            )
                        }
                    }

                    if (canSwitchCamera) {
                        IconButton(
                            onClick = {
                                lensFacing = if (lensFacing == CameraSelector.LENS_FACING_BACK) {
                                    CameraSelector.LENS_FACING_FRONT
                                } else {
                                    CameraSelector.LENS_FACING_BACK
                                }
                            },
                            modifier = Modifier
                                .size(48.dp)
                                .background(Color.Black.copy(alpha = 0.5f), CircleShape)
                                .testTag("camera_switch_button")
                        ) {
                            Icon(
                                imageVector = Icons.Filled.Cameraswitch,
                                contentDescription = stringResource(R.string.camera_switch_lens),
                                tint = Color.White
                            )
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            // Live Detection / Mode Status Pill
            val statusText = when {
                scanMode == ScanMode.PHOTO -> stringResource(R.string.camera_status_photo_mode)
                stableFrameCount >= 5 -> stringResource(R.string.camera_status_ready)
                liveQuad != null -> stringResource(R.string.camera_status_detected)
                else -> stringResource(R.string.camera_status_searching)
            }
            val pillColor = when {
                scanMode == ScanMode.PHOTO -> Color.Black.copy(alpha = 0.65f)
                stableFrameCount >= 4 -> ScannerEmerald.copy(alpha = 0.9f)
                liveQuad != null -> ScannerCyan.copy(alpha = 0.85f)
                else -> Color.Black.copy(alpha = 0.65f)
            }

            Surface(
                color = pillColor,
                shape = RoundedCornerShape(20.dp)
            ) {
                Text(
                    text = statusText,
                    style = MaterialTheme.typography.labelLarge,
                    color = Color.White,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                )
            }
        }

        // Bottom Controls: Zoom + Gallery + Capture Shutter + Multi-Page Done
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .background(Color.Black.copy(alpha = 0.65f))
                .padding(horizontal = 20.dp, vertical = 16.dp)
                .align(Alignment.BottomCenter),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            if (maxZoomRatio > minZoomRatio + 0.1f) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 8.dp)
                ) {
                    Text(
                        text = String.format(java.util.Locale.US, "%.1fx", currentZoomRatio),
                        style = MaterialTheme.typography.labelSmall,
                        color = Color.White,
                        modifier = Modifier.width(42.dp)
                    )
                    Slider(
                        value = currentZoomRatio,
                        onValueChange = { ratio ->
                            currentZoomRatio = ratio
                            activeCamera?.cameraControl?.setZoomRatio(ratio)
                        },
                        valueRange = minZoomRatio..maxZoomRatio.coerceAtMost(5f),
                        modifier = Modifier
                            .weight(1f)
                            .testTag("camera_zoom_slider")
                    )
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Gallery Import Shortcut
                IconButton(
                    onClick = {
                        galleryLauncher.launch(
                            PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                        )
                    },
                    modifier = Modifier
                        .size(52.dp)
                        .background(Color.White.copy(alpha = 0.16f), CircleShape)
                        .testTag("camera_gallery_button")
                ) {
                    Icon(
                        imageVector = Icons.Filled.PhotoLibrary,
                        contentDescription = "Import from Gallery",
                        tint = Color.White
                    )
                }

                // Shutter Capture Button
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier.size(78.dp)
                ) {
                    IconButton(
                        onClick = { triggerCapture() },
                        enabled = !isCapturing,
                        modifier = Modifier
                            .size(74.dp)
                            .border(4.dp, Color.White, CircleShape)
                            .padding(6.dp)
                            .background(
                                if (scanMode == ScanMode.DOCUMENT) ScannerCyan else Color.White,
                                CircleShape
                            )
                            .testTag("camera_capture_button")
                    ) {
                        if (isCapturing) {
                            CircularProgressIndicator(
                                color = Color.Black,
                                strokeWidth = 2.5.dp,
                                modifier = Modifier.size(28.dp)
                            )
                        }
                    }
                }

                // Multi-page tray / Continue button if pages already exist
                if (existingPageCount > 0) {
                    Button(
                        onClick = onDoneToEditor,
                        shape = RoundedCornerShape(24.dp),
                        modifier = Modifier.testTag("camera_finish_pages_button")
                    ) {
                        Text("$existingPageCount pg • Edit")
                    }
                } else {
                    Spacer(modifier = Modifier.size(52.dp))
                }
            }
        }
    }
}

@Composable
private fun CapturedReviewOverlay(
    capturedFile: File,
    scanMode: ScanMode,
    existingPageCount: Int,
    onRetake: () -> Unit,
    onScanAnotherPage: () -> Unit,
    onConfirmAndContinue: () -> Unit,
    onCancelAll: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF0B1120))
            .statusBarsPadding()
            .navigationBarsPadding()
            .padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = if (scanMode == ScanMode.PHOTO) "Captured Photo" else "Captured Scan (Page ${existingPageCount + 1})",
                style = MaterialTheme.typography.titleMedium,
                color = Color.White,
                fontWeight = FontWeight.SemiBold
            )
            IconButton(
                onClick = onCancelAll,
                modifier = Modifier.testTag("review_cancel_button")
            ) {
                Icon(Icons.Filled.Close, contentDescription = "Cancel capture", tint = Color.White)
            }
        }

        Spacer(modifier = Modifier.height(12.dp))

        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .clip(RoundedCornerShape(16.dp))
                .background(Color.Black),
            contentAlignment = Alignment.Center
        ) {
            AsyncImage(
                model = capturedFile,
                contentDescription = "Captured image preview",
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Fit
            )
        }

        Spacer(modifier = Modifier.height(16.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            OutlinedButton(
                onClick = onRetake,
                modifier = Modifier
                    .weight(1f)
                    .height(50.dp)
                    .testTag("camera_retake_button")
            ) {
                Icon(Icons.Filled.Refresh, contentDescription = null, tint = Color.White)
                Spacer(modifier = Modifier.width(6.dp))
                Text("Retake", color = Color.White)
            }

            if (scanMode == ScanMode.DOCUMENT) {
                OutlinedButton(
                    onClick = onScanAnotherPage,
                    modifier = Modifier
                        .weight(1f)
                        .height(50.dp)
                        .testTag("camera_add_more_button")
                ) {
                    Text("+ Next Page", color = ScannerCyan)
                }
            }

            Button(
                onClick = onConfirmAndContinue,
                modifier = Modifier
                    .weight(1.2f)
                    .height(50.dp)
                    .testTag("camera_confirm_button")
            ) {
                Icon(Icons.Filled.Check, contentDescription = null)
                Spacer(modifier = Modifier.width(6.dp))
                Text(if (scanMode == ScanMode.PHOTO) "Edit Photo" else "Continue")
            }
        }
    }
}

@Composable
private fun CameraPermissionFallbackView(
    wasDenied: Boolean,
    onRequestPermission: () -> Unit,
    onPickFromGallery: () -> Unit,
    onCancel: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .statusBarsPadding()
            .navigationBarsPadding()
            .padding(28.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Icon(
            imageVector = Icons.Outlined.NoPhotography,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(64.dp)
        )
        Spacer(modifier = Modifier.height(18.dp))
        Text(
            text = stringResource(R.string.camera_permission_title),
            style = MaterialTheme.typography.headlineSmall,
            textAlign = TextAlign.Center
        )
        Spacer(modifier = Modifier.height(10.dp))
        Text(
            text = if (wasDenied) {
                "Camera permission was denied. Enable Camera access in Android App Settings or tap below to request again, or import images directly from your Gallery."
            } else {
                stringResource(R.string.camera_permission_message)
            },
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center
        )
        Spacer(modifier = Modifier.height(24.dp))
        Button(
            onClick = onRequestPermission,
            modifier = Modifier
                .fillMaxWidth()
                .height(50.dp)
                .testTag("grant_camera_permission_button")
        ) {
            Text(stringResource(R.string.camera_grant_permission))
        }
        Spacer(modifier = Modifier.height(12.dp))
        OutlinedButton(
            onClick = onPickFromGallery,
            modifier = Modifier
                .fillMaxWidth()
                .height(50.dp)
                .testTag("fallback_gallery_button")
        ) {
            Icon(Icons.Filled.PhotoLibrary, contentDescription = null)
            Spacer(modifier = Modifier.width(8.dp))
            Text("Import from Gallery Instead")
        }
        Spacer(modifier = Modifier.height(8.dp))
        OutlinedButton(
            onClick = onCancel,
            modifier = Modifier
                .fillMaxWidth()
                .height(48.dp)
        ) {
            Text("Back to Home")
        }
    }
}

@Composable
private fun CameraUnavailableView(
    errorMessage: String,
    onImportFromGallery: () -> Unit,
    onCancel: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .statusBarsPadding()
            .navigationBarsPadding()
            .padding(28.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Icon(
            imageVector = Icons.Outlined.NoPhotography,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.error,
            modifier = Modifier.size(64.dp)
        )
        Spacer(modifier = Modifier.height(16.dp))
        Text(
            text = stringResource(R.string.camera_unavailable_title),
            style = MaterialTheme.typography.headlineSmall,
            textAlign = TextAlign.Center
        )
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = errorMessage,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center
        )
        Spacer(modifier = Modifier.height(24.dp))
        Button(
            onClick = onImportFromGallery,
            modifier = Modifier
                .fillMaxWidth()
                .height(50.dp)
        ) {
            Icon(Icons.Filled.PhotoLibrary, contentDescription = null)
            Spacer(modifier = Modifier.width(8.dp))
            Text("Import from Gallery")
        }
        Spacer(modifier = Modifier.height(10.dp))
        OutlinedButton(
            onClick = onCancel,
            modifier = Modifier
                .fillMaxWidth()
                .height(48.dp)
        ) {
            Text("Back")
        }
    }
}
