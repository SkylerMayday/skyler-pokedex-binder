package com.skyler.pokedexbinder.ui.scanner

import android.Manifest
import android.os.Handler
import android.os.Looper
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.hilt.navigation.compose.hiltViewModel
import coil.compose.AsyncImage
import com.google.accompanist.permissions.ExperimentalPermissionsApi
import com.google.accompanist.permissions.isGranted
import com.google.accompanist.permissions.rememberPermissionState
import com.skyler.pokedexbinder.data.model.TcgCard
import java.util.concurrent.Executors

// ------- Card detection -------------------------------------------------------

private fun detectCardInFrame(imageProxy: ImageProxy): Boolean {
    val iw = imageProxy.width
    val ih = imageProxy.height
    val rotation = imageProxy.imageInfo.rotationDegrees

    // Camera delivers landscape frames even in portrait mode.
    // rotationDegrees tells us how much to rotate the image to match the display.
    // We work in "display space" (portrait) and map back to image coords for sampling.
    val isRotated = rotation == 90 || rotation == 270
    val dispW = if (isRotated) ih else iw   // display-space width
    val dispH = if (isRotated) iw else ih   // display-space height

    // Guide frame in display space (portrait card ratio 63:88)
    val guideW = (dispW * 0.75f).toInt()
    val guideH = (guideW * 88f / 63f).toInt()
    if (guideH >= dispH) return false

    val dLeft  = (dispW - guideW) / 2
    val dTop   = (dispH - guideH) / 2
    val dRight = dLeft + guideW
    val dBot   = dTop  + guideH

    val plane     = imageProxy.planes[0]
    val buffer    = plane.buffer
    val rowStride = plane.rowStride
    val pixStride = plane.pixelStride

    // Map display (dx, dy) → image (ix, iy) based on rotation
    fun toImage(dx: Int, dy: Int): Pair<Int, Int> = when (rotation) {
        90  -> Pair(dy,          ih - 1 - dx)
        270 -> Pair(iw - 1 - dy, dx)
        180 -> Pair(iw - 1 - dx, ih - 1 - dy)
        else -> Pair(dx, dy)
    }

    fun luma(dx: Int, dy: Int): Int {
        val cx = dx.coerceIn(0, dispW - 1)
        val cy = dy.coerceIn(0, dispH - 1)
        val (ix, iy) = toImage(cx, cy)
        val idx = iy.coerceIn(0, ih - 1) * rowStride + ix.coerceIn(0, iw - 1) * pixStride
        return if (idx in 0 until buffer.limit()) buffer[idx].toInt() and 0xFF else 0
    }

    val sampleCount = 16
    val borderPx   = 20   // wider sampling window for real-world conditions
    var totalContrast = 0f
    var count = 0

    repeat(sampleCount) { i ->
        val t = i.toFloat() / sampleCount

        // Top edge
        val tx = (dLeft + guideW * t).toInt()
        totalContrast += Math.abs(luma(tx, dTop - borderPx) - luma(tx, dTop + borderPx))
        count++
        // Bottom edge
        totalContrast += Math.abs(luma(tx, dBot + borderPx) - luma(tx, dBot - borderPx))
        count++
        // Left / right edges
        val ly = (dTop + guideH * t).toInt()
        totalContrast += Math.abs(luma(dLeft - borderPx, ly) - luma(dLeft + borderPx, ly))
        count++
        totalContrast += Math.abs(luma(dRight + borderPx, ly) - luma(dRight - borderPx, ly))
        count++
    }

    return count > 0 && (totalContrast / count) > 20f
}

// ------- Card frame overlay ---------------------------------------------------

@Composable
private fun CardFrameOverlay(cardDetected: Boolean, modifier: Modifier = Modifier) {
    val frameColor by animateColorAsState(
        targetValue = if (cardDetected) Color(0xFF4CAF50) else Color.White,
        animationSpec = tween(durationMillis = 250),
        label = "frame_color"
    )

    Box(modifier = modifier) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val guideW = size.width * 0.75f
            val guideH = guideW * 88f / 63f
            val left = (size.width - guideW) / 2f
            val top = (size.height - guideH) / 2f

            // Dark surround — four rectangles around the guide window
            val dark = Color(0x99000000)
            // top strip
            drawRect(color = dark, topLeft = Offset.Zero, size = Size(size.width, top))
            // bottom strip
            drawRect(color = dark, topLeft = Offset(0f, top + guideH), size = Size(size.width, size.height - top - guideH))
            // left strip
            drawRect(color = dark, topLeft = Offset(0f, top), size = Size(left, guideH))
            // right strip
            drawRect(color = dark, topLeft = Offset(left + guideW, top), size = Size(size.width - left - guideW, guideH))

            // Corner brackets
            val bracketLen = guideW * 0.12f
            val strokeWidth = 4.dp.toPx()
            val stroke = Stroke(width = strokeWidth, cap = StrokeCap.Round)

            // Top-left
            drawLine(frameColor, Offset(left, top + bracketLen), Offset(left, top), stroke.width)
            drawLine(frameColor, Offset(left, top), Offset(left + bracketLen, top), stroke.width)
            // Top-right
            drawLine(frameColor, Offset(left + guideW - bracketLen, top), Offset(left + guideW, top), stroke.width)
            drawLine(frameColor, Offset(left + guideW, top), Offset(left + guideW, top + bracketLen), stroke.width)
            // Bottom-left
            drawLine(frameColor, Offset(left, top + guideH - bracketLen), Offset(left, top + guideH), stroke.width)
            drawLine(frameColor, Offset(left, top + guideH), Offset(left + bracketLen, top + guideH), stroke.width)
            // Bottom-right
            drawLine(frameColor, Offset(left + guideW - bracketLen, top + guideH), Offset(left + guideW, top + guideH), stroke.width)
            drawLine(frameColor, Offset(left + guideW, top + guideH - bracketLen), Offset(left + guideW, top + guideH), stroke.width)
        }

        // Hint text at bottom of guide area
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .align(Alignment.BottomCenter)
                .padding(bottom = 96.dp),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = if (cardDetected) "Card detected!" else "Align card to frame",
                color = if (cardDetected) Color(0xFF4CAF50) else Color.White,
                style = MaterialTheme.typography.bodyMedium,
                textAlign = TextAlign.Center
            )
        }
    }
}

// ------- Camera preview -------------------------------------------------------

@Composable
private fun CameraPreview(
    modifier: Modifier,
    onImageCaptureReady: (ImageCapture) -> Unit,
    onCardPresenceChanged: (Boolean) -> Unit
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val analysisExecutor = remember { Executors.newSingleThreadExecutor() }
    val mainHandler = remember { Handler(Looper.getMainLooper()) }

    DisposableEffect(Unit) {
        onDispose { analysisExecutor.shutdown() }
    }

    AndroidView(
        factory = { ctx ->
            val previewView = PreviewView(ctx)
            val future = ProcessCameraProvider.getInstance(ctx)
            future.addListener({
                val provider = future.get()
                val preview = Preview.Builder().build().also {
                    it.setSurfaceProvider(previewView.surfaceProvider)
                }
                val capture = ImageCapture.Builder().build()
                onImageCaptureReady(capture)

                val analysis = ImageAnalysis.Builder()
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .build()
                analysis.setAnalyzer(analysisExecutor) { imageProxy ->
                    val detected = detectCardInFrame(imageProxy)
                    imageProxy.close()
                    mainHandler.post { onCardPresenceChanged(detected) }
                }

                provider.unbindAll()
                provider.bindToLifecycle(
                    lifecycleOwner,
                    CameraSelector.DEFAULT_BACK_CAMERA,
                    preview, capture, analysis
                )
            }, ContextCompat.getMainExecutor(ctx))
            previewView
        },
        modifier = modifier
    )
}

// ------- Main screen ----------------------------------------------------------

@OptIn(ExperimentalPermissionsApi::class, ExperimentalMaterial3Api::class)
@Composable
fun ScannerScreen(
    onDone: () -> Unit,
    onSearchManually: () -> Unit,
    onNavigateToSettings: () -> Unit,
    onBack: () -> Unit,
    viewModel: ScannerViewModel = hiltViewModel()
) {
    val cameraPermission = rememberPermissionState(Manifest.permission.CAMERA)
    val state by viewModel.state.collectAsState()
    var imageCapture by remember { mutableStateOf<ImageCapture?>(null) }
    val context = LocalContext.current

    // Auto-capture state
    var detectionStartMs by remember { mutableLongStateOf(0L) }
    var isCapturing by remember { mutableStateOf(false) }
    val holdDurationMs = 1500L

    LaunchedEffect(Unit) {
        if (!cameraPermission.status.isGranted) cameraPermission.launchPermissionRequest()
    }

    LaunchedEffect(state) {
        if (state is ScannerState.Success) onDone()
        // Reset auto-capture state whenever we leave Idle
        if (state !is ScannerState.Idle) {
            detectionStartMs = 0L
            isCapturing = false
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Scan Card") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        }
    ) { padding ->
        Column(modifier = Modifier.padding(padding).fillMaxSize()) {
            when (val s = state) {
                is ScannerState.Idle -> {
                    if (cameraPermission.status.isGranted) {
                        var cardDetected by remember { mutableStateOf(false) }

                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxWidth()
                        ) {
                            CameraPreview(
                                modifier = Modifier.fillMaxSize(),
                                onImageCaptureReady = { imageCapture = it },
                                onCardPresenceChanged = { detected ->
                                    cardDetected = detected
                                    if (detected) {
                                        val now = System.currentTimeMillis()
                                        if (detectionStartMs == 0L) detectionStartMs = now
                                        if (!isCapturing && now - detectionStartMs >= holdDurationMs) {
                                            isCapturing = true
                                            imageCapture?.takePicture(
                                                ContextCompat.getMainExecutor(context),
                                                object : ImageCapture.OnImageCapturedCallback() {
                                                    override fun onCaptureSuccess(image: ImageProxy) {
                                                        viewModel.processImage(image)
                                                    }
                                                    override fun onError(exc: ImageCaptureException) {
                                                        isCapturing = false
                                                        detectionStartMs = 0L
                                                        viewModel.onCaptureError(exc.message ?: "Capture failed")
                                                    }
                                                }
                                            )
                                        }
                                    } else {
                                        detectionStartMs = 0L
                                    }
                                }
                            )
                            CardFrameOverlay(
                                cardDetected = cardDetected,
                                modifier = Modifier.fillMaxSize()
                            )
                        }

                        // Manual capture button as fallback
                        Button(
                            onClick = {
                                if (!isCapturing) {
                                    isCapturing = true
                                    imageCapture?.takePicture(
                                        ContextCompat.getMainExecutor(context),
                                        object : ImageCapture.OnImageCapturedCallback() {
                                            override fun onCaptureSuccess(image: ImageProxy) {
                                                viewModel.processImage(image)
                                            }
                                            override fun onError(exc: ImageCaptureException) {
                                                isCapturing = false
                                                detectionStartMs = 0L
                                                viewModel.onCaptureError(exc.message ?: "Capture failed")
                                            }
                                        }
                                    )
                                }
                            },
                            enabled = !isCapturing,
                            modifier = Modifier
                                .align(Alignment.CenterHorizontally)
                                .padding(16.dp)
                        ) { Text(if (isCapturing) "Capturing…" else "Capture") }
                    } else {
                        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            Text("Camera permission required", modifier = Modifier.padding(16.dp))
                        }
                    }
                }
                is ScannerState.Scanning -> {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator()
                    }
                }
                is ScannerState.NoApiKey -> {
                    ScannerMessage(
                        message = "No Gemini API key set. Add your free key in Settings to use the scanner.",
                        primaryLabel = "Go to Settings",
                        onPrimary = onNavigateToSettings,
                        secondaryLabel = "Search Manually",
                        onSecondary = onSearchManually
                    )
                }
                is ScannerState.RateLimited -> {
                    ScannerMessage(
                        message = "You've hit today's scan limit. Try again tomorrow.",
                        primaryLabel = "Search Manually",
                        onPrimary = onSearchManually
                    )
                }
                is ScannerState.HighConfidence -> {
                    CardConfirmation(
                        card = s.card,
                        onConfirm = { viewModel.confirmCard(s.card) },
                        onDismiss = { viewModel.reset() }
                    )
                }
                is ScannerState.LowConfidence -> {
                    CardSelectionList(
                        cards = s.cards,
                        onSelect = { viewModel.confirmCard(it) },
                        onSearchManually = onSearchManually,
                        onBack = { viewModel.reset() }
                    )
                }
                is ScannerState.Error -> {
                    ScannerMessage(
                        message = s.message,
                        isError = true,
                        primaryLabel = "Retry",
                        onPrimary = { viewModel.reset() },
                        secondaryLabel = "Search Manually",
                        onSecondary = onSearchManually
                    )
                }
                is ScannerState.Success -> { /* handled by LaunchedEffect above */ }
            }
        }
    }
}

// ------- Helpers --------------------------------------------------------------

@Composable
private fun ScannerMessage(
    message: String,
    isError: Boolean = false,
    primaryLabel: String,
    onPrimary: () -> Unit,
    secondaryLabel: String? = null,
    onSecondary: (() -> Unit)? = null
) {
    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text(
            text = message,
            color = if (isError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
            style = MaterialTheme.typography.bodyLarge
        )
        Spacer(Modifier.height(24.dp))
        Button(onClick = onPrimary, modifier = Modifier.fillMaxWidth()) { Text(primaryLabel) }
        if (secondaryLabel != null && onSecondary != null) {
            Spacer(Modifier.height(8.dp))
            OutlinedButton(onClick = onSecondary, modifier = Modifier.fillMaxWidth()) { Text(secondaryLabel) }
        }
    }
}

@Composable
private fun CardConfirmation(card: TcgCard, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize().padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text("Is this the right card?", style = MaterialTheme.typography.titleMedium)
        AsyncImage(
            model = card.imageUrl,
            contentDescription = card.name,
            contentScale = ContentScale.Fit,
            modifier = Modifier.weight(1f).fillMaxWidth()
        )
        Text("${card.name} · ${card.setName} · #${card.number}")
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = onDismiss, modifier = Modifier.weight(1f)) { Text("Wrong card") }
            Button(onClick = onConfirm, modifier = Modifier.weight(1f)) { Text("Confirm") }
        }
    }
}

@Composable
private fun CardSelectionList(
    cards: List<TcgCard>,
    onSelect: (TcgCard) -> Unit,
    onSearchManually: () -> Unit,
    onBack: () -> Unit
) {
    Column(modifier = Modifier.fillMaxSize()) {
        if (cards.isEmpty()) {
            Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                Text("No cards found — try scanning again or search manually")
            }
        } else {
            LazyColumn(modifier = Modifier.weight(1f)) {
                items(cards) { card ->
                    ListItem(
                        headlineContent = { Text(card.name) },
                        supportingContent = { Text("${card.setName} · #${card.number}") },
                        leadingContent = {
                            AsyncImage(
                                model = card.imageUrl,
                                contentDescription = null,
                                modifier = Modifier.size(56.dp)
                            )
                        },
                        modifier = Modifier.clickable { onSelect(card) }
                    )
                    HorizontalDivider()
                }
            }
        }
        Row(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            OutlinedButton(onClick = onBack, modifier = Modifier.weight(1f)) { Text("Back") }
            OutlinedButton(onClick = onSearchManually, modifier = Modifier.weight(1f)) { Text("Search Manually") }
        }
    }
}
