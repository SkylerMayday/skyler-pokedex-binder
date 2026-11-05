package com.skyler.pokedexbinder.ui.scanner

import android.Manifest
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.MotionEvent
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
import com.skyler.pokedexbinder.domain.ScanProvider
import kotlinx.coroutines.delay
import java.util.concurrent.Executor
import java.util.concurrent.Executors

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
            val guideW = size.width * GUIDE_FRAME_WIDTH_RATIO
            val guideH = guideW * CARD_ASPECT_RATIO
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

            // Full border — a single continuous edge is an unambiguous alignment reference for
            // all 4 sides, unlike the previous 4 corner-only brackets.
            val strokeWidth = 4.dp.toPx()
            val stroke = Stroke(width = strokeWidth)
            drawRect(
                color = frameColor,
                topLeft = Offset(left, top),
                size = Size(guideW, guideH),
                style = stroke
            )
        }

        // Hint text at bottom of guide area
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .align(Alignment.BottomCenter)
                .padding(bottom = 96.dp),
            contentAlignment = Alignment.Center
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    text = if (cardDetected) "Card detected!" else "Align card to frame",
                    color = if (cardDetected) Color(0xFF4CAF50) else Color.White,
                    style = MaterialTheme.typography.bodyMedium,
                    textAlign = TextAlign.Center
                )
                // Distance text kept next to GUIDE_FRAME_WIDTH_RATIO's own derivation comment
                // in spirit — update both together if the ratio/target distance ever changes.
                if (!cardDetected) {
                    Text(
                        text = "Hold card ~4 in (10 cm) back",
                        color = Color.White,
                        style = MaterialTheme.typography.bodySmall,
                        textAlign = TextAlign.Center
                    )
                }
            }
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
    val mainExecutor = remember { Executor { command -> mainHandler.post(command) } }
    // Holds the bound ProcessCameraProvider so onRelease (below) can unbind it the moment this
    // AndroidView leaves composition — bindToLifecycle's own automatic unbind only fires when
    // LocalLifecycleOwner.current actually stops, which lags navigating away from this composable
    // by enough to race the PreviewView's SurfaceView teardown (observed as repeated
    // "BufferQueue has been abandoned" errors: CameraX still pushing frames to a surface Compose
    // already released).
    val cameraProviderHolder = remember { arrayOfNulls<ProcessCameraProvider>(1) }

    DisposableEffect(Unit) {
        onDispose { analysisExecutor.shutdown() }
    }

    AndroidView(
        factory = { ctx ->
            val previewView = PreviewView(ctx)
            var camera: Camera? = null
            var wasCardDetected = false
            // True once the most recently issued focus-and-metering request has settled
            // (success or failure — either way capture must not stay blocked on it forever).
            var focusLocked = false
            // Monotonically increasing token so a stale, superseded request's late completion
            // can never incorrectly re-lock focus over a newer, still-in-flight request.
            var focusRequestId = 0
            // True while a triggerFocus() call is still waiting on requestFocusAndMetering to
            // settle. Guards the edge-triggered auto-refocus below: without it, a flickering
            // detected/not-detected transition (normal hand jitter while holding a card in frame)
            // fires a new startFocusAndMetering() before the previous one resolves, which CameraX
            // cancels outright (observed as repeated "Cancelled by another startFocusAndMetering()"
            // — up to 9 in under 2s during a single scan).
            var focusInFlight = false
            // 0L until camera bind actually completes; used to gate presence-reporting below.
            var bindCompletedAtMs = 0L

            fun triggerFocus(x: Float, y: Float) {
                val cam = camera ?: return
                focusRequestId++
                val myRequestId = focusRequestId
                focusLocked = false
                focusInFlight = true
                requestFocusAndMetering(cam, previewView, x, y, mainExecutor, mainHandler) {
                    if (myRequestId == focusRequestId) {
                        focusLocked = true
                        focusInFlight = false
                    }
                }
            }

            // Builds a fresh Preview/ImageCapture/ImageAnalysis trio on the default back camera.
            fun buildUseCases(): Triple<Preview, ImageCapture, ImageAnalysis> {
                val previewBuilder = Preview.Builder()
                val captureBuilder = ImageCapture.Builder()
                val analysisBuilder = ImageAnalysis.Builder()
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                val previewUseCase = previewBuilder.build().also {
                    it.setSurfaceProvider(previewView.surfaceProvider)
                }
                val captureUseCase = captureBuilder.build()
                val analysisUseCase = analysisBuilder.build()
                analysisUseCase.setAnalyzer(analysisExecutor) { imageProxy ->
                    val detected = detectCardInFrame(imageProxy)
                    imageProxy.close()
                    mainHandler.post {
                        // Only report presence once the most recently issued focus request has
                        // actually settled — gates ScannerScreen's auto-capture hold-timer on
                        // real focus convergence, not just contrast-heuristic detection.
                        // On the not-detected -> detected transition frame, focusLocked may still
                        // reflect a stale, unrelated prior request (bind-time focus or a previous
                        // card's settled focus) that has nothing to do with *this* card — so the
                        // transition frame's own report is forced false, without reordering the
                        // presence-report line ahead of the edge-triggered refocus call below.
                        val justTransitioned = detected && !wasCardDetected
                        val withinPostBindDelay = bindCompletedAtMs == 0L ||
                            (System.currentTimeMillis() - bindCompletedAtMs) < POST_BIND_DETECTION_DELAY_MS
                        onCardPresenceChanged(detected && focusLocked && !justTransitioned && !withinPostBindDelay)
                        // Edge-triggered refocus: only on the not-detected -> detected transition,
                        // so a card sitting still in frame doesn't spam focus-metering calls.
                        if (justTransitioned && !focusInFlight) {
                            // Guide-frame center is always (width/2, height/2) — the overlay's
                            // GUIDE_FRAME_WIDTH_RATIO/88:63 ratio math isn't needed here since
                            // CameraPreview and CardFrameOverlay are same-sized siblings
                            // centered the same way.
                            triggerFocus(previewView.width / 2f, previewView.height / 2f)
                        }
                        wasCardDetected = detected
                    }
                }
                return Triple(previewUseCase, captureUseCase, analysisUseCase)
            }

            val future = ProcessCameraProvider.getInstance(ctx)
            future.addListener({
                val provider = future.get()
                cameraProviderHolder[0] = provider

                // Constrains preview/capture/analysis to one shared field of view via the
                // PreviewView's own ViewPort, so ImageProxy.cropRect (consumed by
                // ImageProxyExt.toCroppedBitmap()) reflects what PreviewView actually displays
                // instead of the full sensor frame. Falls back to the plain vararg bind (today's
                // behavior) when previewView.viewPort is still null — same defensive shape as
                // requestFocusAndMetering's existing previewView.width == 0 guard, since viewPort
                // is only non-null once the view has been laid out.
                fun bindPreviewCaptureAnalysis(
                    preview: Preview,
                    capture: ImageCapture,
                    analysis: ImageAnalysis
                ): Camera {
                    val viewPort = previewView.viewPort
                    return if (viewPort != null) {
                        val group = UseCaseGroup.Builder()
                            .setViewPort(viewPort)
                            .addUseCase(preview)
                            .addUseCase(capture)
                            .addUseCase(analysis)
                            .build()
                        Log.i("ScannerFocus", "binding via UseCaseGroup with shared ViewPort")
                        provider.bindToLifecycle(lifecycleOwner, CameraSelector.DEFAULT_BACK_CAMERA, group)
                    } else {
                        Log.i("ScannerFocus", "previewView.viewPort null, binding via plain vararg (no shared FOV)")
                        provider.bindToLifecycle(
                            lifecycleOwner, CameraSelector.DEFAULT_BACK_CAMERA, preview, capture, analysis
                        )
                    }
                }

                // Physical-camera-id selection was removed (2026-09-29/30): a live pair (preview vs
                // saved JPEG) showed the still ~1.35x more zoomed than the preview despite the id
                // on all three use cases and a shared ViewPort — Camera2Interop.setPhysicalCameraId
                // is reported unreliable on Samsung multi-lens phones. Instead the min zoom ratio
                // is applied to the logical camera after bind (see zoomState observer below), which
                // switches every stream to the ultrawide together.
                val (preview, capture, analysis) = buildUseCases()
                onImageCaptureReady(capture)

                provider.unbindAll()
                camera = runCatching {
                    bindPreviewCaptureAnalysis(preview, capture, analysis)
                }.getOrElse { error ->
                    // Log and degrade to no camera rather than letting this throw escape the
                    // Runnable posted to the main Looper (an uncaught throwable there is a
                    // process crash).
                    Log.w("ScannerFocus", "default-back-camera bind threw", error)
                    null
                }
                bindCompletedAtMs = System.currentTimeMillis()
                camera?.let { cam ->
                    logCameraAfCapabilities(cam, "default-back-camera")
                    logUltrawideFeasibility(cam)

                    // Logical zoom below 1.0 makes the multi-camera HAL switch to the ultrawide
                    // for preview, still and analysis at once (this device: range 0.6-10). Applied
                    // once the first ZoomState arrives — its value is null until the camera opens.
                    val zoomLive = cam.cameraInfo.zoomState
                    zoomLive.observe(lifecycleOwner, object : androidx.lifecycle.Observer<ZoomState> {
                        override fun onChanged(value: ZoomState) {
                            zoomLive.removeObserver(this)
                            Log.i(
                                "ScannerFocus",
                                "zoomState min=${value.minZoomRatio} max=${value.maxZoomRatio} current=${value.zoomRatio}"
                            )
                            if (value.minZoomRatio < 1f) cam.cameraControl.setZoomRatio(value.minZoomRatio)
                        }
                    })
                }
                // Initial focus once binding completes; deferred via post{} so previewView.width/height
                // are populated (they may still be 0 at the exact moment bindToLifecycle returns).
                previewView.post {
                    triggerFocus(previewView.width / 2f, previewView.height / 2f)
                }
            }, ContextCompat.getMainExecutor(ctx))

            previewView.setOnTouchListener { view, event ->
                if (event.action == MotionEvent.ACTION_UP) {
                    triggerFocus(event.x, event.y)
                    view.performClick()
                }
                true
            }

            previewView
        },
        modifier = modifier,
        onRelease = { cameraProviderHolder[0]?.unbindAll() }
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
        // Reset auto-capture counters whenever we're not in an active idle state
        if (state !is ScannerState.Idle && state !is ScannerState.IdleWithGrace) {
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
                        message = "No API key set. Add a Gemini or Anthropic key in Settings to use the scanner.",
                        primaryLabel = "Go to Settings",
                        onPrimary = onNavigateToSettings,
                        secondaryLabel = "Search Manually",
                        onSecondary = onSearchManually
                    )
                }
                is ScannerState.IdleWithGrace -> {
                    // Post-rate-limit grace: show camera but block auto-capture for 5s
                    val graceEndsAt = s.graceEndsAt
                    LaunchedEffect(graceEndsAt) {
                        val remaining = graceEndsAt - System.currentTimeMillis()
                        if (remaining > 0) delay(remaining)
                        viewModel.clearGrace()
                    }
                    Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                        CameraPreview(
                            modifier = Modifier.fillMaxSize(),
                            onImageCaptureReady = { imageCapture = it },
                            onCardPresenceChanged = { /* blocked during grace */ }
                        )
                        CardFrameOverlay(cardDetected = false, modifier = Modifier.fillMaxSize())
                        Box(
                            Modifier.fillMaxSize().padding(bottom = 56.dp),
                            contentAlignment = Alignment.BottomCenter
                        ) {
                            Text(
                                "Getting ready…",
                                color = Color.White,
                                style = MaterialTheme.typography.bodyMedium
                            )
                        }
                    }
                    Button(
                        onClick = { /* no-op during grace */ },
                        enabled = false,
                        modifier = Modifier.align(Alignment.CenterHorizontally).padding(16.dp)
                    ) { Text("Capture") }
                }

                is ScannerState.RateLimited -> {
                    val isRepeated = s.consecutiveHits > 1
                    var secondsLeft by remember { mutableIntStateOf(60) }
                    LaunchedEffect(s.consecutiveHits) {
                        secondsLeft = 60
                        while (secondsLeft > 0) {
                            delay(1000)
                            secondsLeft--
                        }
                    }
                    Column(
                        modifier = Modifier.fillMaxSize().padding(24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center
                    ) {
                        Text(
                            text = rateLimitMessage(s.provider, isRepeated, secondsLeft),
                            style = MaterialTheme.typography.bodyLarge,
                            textAlign = TextAlign.Center
                        )
                        Spacer(Modifier.height(24.dp))
                        if (!isRepeated) {
                            Button(
                                onClick = { viewModel.reset() },
                                enabled = secondsLeft == 0,
                                modifier = Modifier.fillMaxWidth()
                            ) { Text("Try Again") }
                            Spacer(Modifier.height(8.dp))
                        }
                        OutlinedButton(
                            onClick = onSearchManually,
                            modifier = Modifier.fillMaxWidth()
                        ) { Text("Search Manually") }
                    }
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
                        onBack = { viewModel.reset() },
                        header = if (s.crossLanguage) {
                            "No card found in this language - closest English printings of ${s.cards.firstOrNull()?.name ?: "this card"}"
                        } else null
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
    onBack: () -> Unit,
    header: String? = null
) {
    Column(modifier = Modifier.fillMaxSize()) {
        if (header != null && cards.isNotEmpty()) {
            Text(header, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(16.dp))
        }
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

internal fun rateLimitMessage(provider: ScanProvider, isRepeated: Boolean, secondsLeft: Int): String {
    val (limit, console) = when (provider) {
        ScanProvider.GEMINI -> "daily Gemini quota" to "aistudio.google.com"
        ScanProvider.CLAUDE -> "Claude usage limit" to "console.anthropic.com"
    }
    return when {
        isRepeated ->
            "Still rate-limited after waiting — you may have hit your $limit.\n\n" +
                "Check your usage at $console or search manually for now."
        secondsLeft > 0 ->
            "Too many scans at once — ${provider.displayName} needs a moment.\n\nRetry in $secondsLeft seconds."
        else -> "Ready to scan again!"
    }
}
