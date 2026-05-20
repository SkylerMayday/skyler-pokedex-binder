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
    val width = imageProxy.width
    val height = imageProxy.height
    val guideW = (width * 0.75f).toInt()
    val guideH = (guideW * 88f / 63f).toInt()
    if (guideH >= height) return false

    val left = (width - guideW) / 2
    val top = (height - guideH) / 2
    val right = left + guideW
    val bottom = top + guideH

    val plane = imageProxy.planes[0]
    val buffer = plane.buffer
    val rowStride = plane.rowStride
    val pixelStride = plane.pixelStride

    fun luma(x: Int, y: Int): Int {
        val idx = y * rowStride + x * pixelStride
        return if (idx in 0 until buffer.limit()) buffer[idx].toInt() and 0xFF else 0
    }

    val sampleCount = 16
    val borderPx = 12
    var totalContrast = 0f
    var count = 0

    repeat(sampleCount) { i ->
        val t = i.toFloat() / sampleCount

        val tx = (left + guideW * t).toInt().coerceIn(borderPx, width - 1 - borderPx)
        totalContrast += Math.abs(
            luma(tx, (top - borderPx).coerceAtLeast(0)) -
            luma(tx, (top + borderPx).coerceAtMost(height - 1))
        )
        count++
        totalContrast += Math.abs(
            luma(tx, (bottom + borderPx).coerceAtMost(height - 1)) -
            luma(tx, (bottom - borderPx).coerceAtLeast(0))
        )
        count++

        val ly = (top + guideH * t).toInt().coerceIn(borderPx, height - 1 - borderPx)
        totalContrast += Math.abs(
            luma((left - borderPx).coerceAtLeast(0), ly) -
            luma((left + borderPx).coerceAtMost(width - 1), ly)
        )
        count++
        totalContrast += Math.abs(
            luma((right + borderPx).coerceAtMost(width - 1), ly) -
            luma((right - borderPx).coerceAtLeast(0), ly)
        )
        count++
    }

    return count > 0 && (totalContrast / count) > 35f
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
    var consecutiveDetections by remember { mutableIntStateOf(0) }
    var isCapturing by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        if (!cameraPermission.status.isGranted) cameraPermission.launchPermissionRequest()
    }

    LaunchedEffect(state) {
        if (state is ScannerState.Success) onDone()
        // Reset auto-capture state whenever we leave Idle
        if (state !is ScannerState.Idle) {
            consecutiveDetections = 0
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
                                        consecutiveDetections++
                                        if (consecutiveDetections >= 3 && !isCapturing) {
                                            isCapturing = true
                                            imageCapture?.takePicture(
                                                ContextCompat.getMainExecutor(context),
                                                object : ImageCapture.OnImageCapturedCallback() {
                                                    override fun onCaptureSuccess(image: ImageProxy) {
                                                        viewModel.processImage(image)
                                                    }
                                                    override fun onError(exc: ImageCaptureException) {
                                                        isCapturing = false
                                                        consecutiveDetections = 0
                                                        viewModel.onCaptureError(exc.message ?: "Capture failed")
                                                    }
                                                }
                                            )
                                        }
                                    } else {
                                        consecutiveDetections = 0
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
                                                consecutiveDetections = 0
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
