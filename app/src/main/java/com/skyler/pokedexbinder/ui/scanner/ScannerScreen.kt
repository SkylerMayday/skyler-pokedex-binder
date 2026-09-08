package com.skyler.pokedexbinder.ui.scanner

import android.Manifest
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.MotionEvent
import androidx.camera.camera2.interop.Camera2CameraInfo
import androidx.camera.camera2.interop.Camera2Interop
import androidx.camera.camera2.interop.ExperimentalCamera2Interop
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
import kotlinx.coroutines.delay
import java.util.concurrent.Executor
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

// ------- Card detection -------------------------------------------------------

// Guide frame width as a fraction of display-space width. Sized so that "card fills the
// frame" corresponds to holding the card ~23cm (~9in) back — safely past the S26 Ultra's
// documented 18cm minimum focus distance and Skyler's observed ~15-20cm real-device focus
// failure threshold. Derived from a calibration photo (card ≈ 40-45% of frame width at
// ~15-20cm) scaled to the 23cm target: 0.425 × (17.5 / 23) ≈ 0.32. Shared by
// detectCardInFrame's analysis-space sampling and CardFrameOverlay's drawn UI geometry so
// the two can never drift apart.
private const val GUIDE_FRAME_WIDTH_RATIO = 0.32f

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
    val guideW = (dispW * GUIDE_FRAME_WIDTH_RATIO).toInt()
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

// Plain rect in raw image-space (pre-rotation) pixel coordinates — right/bottom exclusive,
// matching Bitmap.createBitmap's (left, top, width, height) convention.
internal data class ImageRect(val left: Int, val top: Int, val right: Int, val bottom: Int) {
    val width: Int get() = right - left
    val height: Int get() = bottom - top
}

// Computes the guide-frame rect in raw image-space (pre-rotation) pixel coordinates, for an
// image of the given raw width/height and CameraX rotationDegrees. Shared by
// detectCardInFrame's live analysis-space sampling geometry (duplicated above, not refactored
// to call this — see Task 4's note) and ImageProxyExt's post-capture crop — both must agree on
// the same guide geometry CardFrameOverlay draws, or a captured photo's crop won't match what
// the user aimed at. `internal` (not private) for JVM-unit-test access from ScannerScreenTest.kt
// and for use from ImageProxyExt.kt (same package).
internal fun guideFrameImageRect(rawWidth: Int, rawHeight: Int, rotationDegrees: Int): ImageRect {
    val isRotated = rotationDegrees == 90 || rotationDegrees == 270
    val dispW = if (isRotated) rawHeight else rawWidth
    val dispH = if (isRotated) rawWidth else rawHeight

    val guideW = (dispW * GUIDE_FRAME_WIDTH_RATIO).toInt()
    val guideH = (guideW * 88f / 63f).toInt()
    val dLeft = (dispW - guideW) / 2
    val dTop = (dispH - guideH) / 2
    val dRight = dLeft + guideW
    val dBot = dTop + guideH

    fun toImage(dx: Int, dy: Int): Pair<Int, Int> = when (rotationDegrees) {
        90 -> Pair(dy, rawHeight - 1 - dx)
        270 -> Pair(rawWidth - 1 - dy, dx)
        180 -> Pair(rawWidth - 1 - dx, rawHeight - 1 - dy)
        else -> Pair(dx, dy)
    }

    // Map all 4 corners, not just top-left/bottom-right — a 90/270 rotation swaps which raw-image
    // axis corresponds to display-width vs -height, so the mapped rect must be re-normalized
    // (min/max over all 4 mapped corners) rather than assuming corner order survives the rotation.
    val corners = listOf(
        toImage(dLeft, dTop), toImage(dRight, dTop), toImage(dLeft, dBot), toImage(dRight, dBot)
    )
    val left = corners.minOf { it.first }.coerceIn(0, rawWidth - 1)
    val top = corners.minOf { it.second }.coerceIn(0, rawHeight - 1)
    val right = corners.maxOf { it.first }.coerceIn(0, rawWidth - 1)
    val bottom = corners.maxOf { it.second }.coerceIn(0, rawHeight - 1)
    return ImageRect(left, top, right, bottom)
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
            val guideW = size.width * GUIDE_FRAME_WIDTH_RATIO
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
                        text = "Hold card ~9 in (23 cm) back",
                        color = Color.White,
                        style = MaterialTheme.typography.bodySmall,
                        textAlign = TextAlign.Center
                    )
                }
            }
        }
    }
}

// ------- Camera focus ----------------------------------------------------------

// Minimum elapsed time after camera bind completes before ANY card presence can be
// reported to ScannerScreen — gives the user physical time to lift a card into frame
// before auto-capture logic can fire at all, independent of how fast the false-positive
// contrast heuristic + focus-lock gate settle against an empty/background scene.
// Additive to ScannerScreen's own holdDurationMs (1500ms) continuous-detection hold —
// together they set the true floor for auto-capture at bind-completion + this value +
// holdDurationMs (~3.5s total from camera bind to earliest possible auto-capture).
private const val POST_BIND_DETECTION_DELAY_MS = 2000L

// Diagnostic only — logs the bound (logical) camera's real AF hardware capability, so "focus
// never locks" (isFocusSuccessful=false in requestFocusAndMetering's logs) can be told apart from
// "this lens has no usable close-range AF at all." Camera2CameraInfo is an experimental CameraX
// interop API, opted into only for this narrow diagnostic use, not the whole file.
//
// minFocusDistance above is always the LOGICAL camera's own
// floor — a session-level physical-camera-id option (see selectUltrawidePhysicalCameraId's doc
// comment) never changes which CameraInfo the bound Camera exposes, so this alone would report
// the main lens's ~18-20cm floor even when the ultrawide is genuinely bound. When a physical
// camera id was requested, also read that physical camera's OWN characteristics — off its own
// CameraInfo, not the logical one — so the actual close-focus floor is visible next to it.
@androidx.annotation.OptIn(ExperimentalCamera2Interop::class)
private fun logCameraAfCapabilities(camera: Camera, boundVia: String, requestedPhysicalId: String?) {
    runCatching {
        val info = Camera2CameraInfo.from(camera.cameraInfo)
        val chars = info.getCameraCharacteristic(
            android.hardware.camera2.CameraCharacteristics.LENS_INFO_MINIMUM_FOCUS_DISTANCE
        )
        val afModes = info.getCameraCharacteristic(
            android.hardware.camera2.CameraCharacteristics.CONTROL_AF_AVAILABLE_MODES
        )
        val requestedPhysicalMinFocusDistance = requestedPhysicalId?.let { reqId ->
            camera.cameraInfo.getPhysicalCameraInfos()
                .firstOrNull { physicalInfo ->
                    runCatching { Camera2CameraInfo.from(physicalInfo).cameraId == reqId }.getOrDefault(false)
                }
                ?.let { physicalInfo ->
                    runCatching {
                        Camera2CameraInfo.from(physicalInfo).getCameraCharacteristic(
                            android.hardware.camera2.CameraCharacteristics.LENS_INFO_MINIMUM_FOCUS_DISTANCE
                        )
                    }.getOrNull()
                }
        }
        Log.i(
            "ScannerFocus",
            "camera id=${info.cameraId} boundVia=$boundVia minFocusDistance=$chars (0 or null = " +
                "fixed-focus lens, cannot focus at all) afAvailableModes=${afModes?.toList()}" +
                (if (requestedPhysicalId != null) {
                    " requestedPhysicalId=$requestedPhysicalId requestedPhysicalMinFocusDistance=$requestedPhysicalMinFocusDistance"
                } else {
                    ""
                })
        )
    }.onFailure { Log.w("ScannerFocus", "could not read camera AF characteristics", it) }
}

// Reads back the LOGICAL camera id CameraX bound — this is NOT the physical camera id a caller
// may have requested via Camera2Interop.Extender.setPhysicalCameraId. A session-level physical
// camera id selects which physical stream is opened per output config; it never changes which
// (logical) CameraDevice is opened, so Camera2CameraInfo.from(camera.cameraInfo).cameraId always
// reads back the logical id (see CameraGraphConfigProvider.kt / CameraInfoAdapter.kt — the two id
// namespaces are structurally disjoint). Comparing this against a requested physical id can never
// be true; it exists purely for informational logging, not as a pass/fail verdict. Never throws;
// null just means "couldn't determine."
@androidx.annotation.OptIn(ExperimentalCamera2Interop::class)
private fun actualBoundCameraId(camera: Camera): String? =
    runCatching { Camera2CameraInfo.from(camera.cameraInfo).cameraId }.getOrNull()

// Threshold matching Task 0 spike's own filter: a physical camera whose focal length is
// under this value (mm) is treated as an ultrawide candidate for close-range focus.
private const val ULTRAWIDE_FOCAL_LENGTH_THRESHOLD_MM = 20f

// Finds the S26 Ultra's (or any device's) physical ultrawide camera id on the logical back
// camera, per docs/specs/2026-09-02-scanner-macro-focus.md's approved Decision — ultrawide
// physical-lens selection, reusing the same mechanism Samsung's own stock camera app uses
// ("Focus Enhancer") for close-range focus below the main lens's minimum focus distance.
//
// Returns the physical camera id String, NOT a CameraSelector — CameraSelector.setPhysicalCameraId
// is only honored by CameraX's concurrent-dual-camera bind overload, which this project doesn't
// use; the bindToLifecycle overload actually called (CameraPreview below) never reads a selector's
// physical id at all, so a selector carrying it would bind successfully onto the *logical* camera
// while silently ignoring the id (verified against camera-camera2-1.6.1's real source — see
// LifecycleCameraProviderImpl.kt). The only mechanism that works with the overload this project
// calls is applying Camera2Interop.Extender(builder).setPhysicalCameraId(id) to each use-case
// builder before build() (also RequiresApi(28) — see CameraPreview's SDK_INT guard), then binding
// with the plain CameraSelector.DEFAULT_BACK_CAMERA.
//
// Returns null (never throws) on any device/state that doesn't cleanly support this, so the
// caller's fallback to plain default-back-camera behavior is always safe. `internal` (not
// private) solely for JVM-unit-test access from ScannerScreenTest.kt — no external caller
// outside this package needs it.
//
// Pre-bind query behavior is unverified against Task 0's proven post-bind case (see spec's
// Open Questions) — the one-line log below lets a real-device run confirm whether
// getPhysicalCameraInfos() returns the same data pre-bind as Task 0's spike found post-bind.
@androidx.annotation.OptIn(ExperimentalCamera2Interop::class)
internal fun selectUltrawidePhysicalCameraId(provider: ProcessCameraProvider): String? =
    runCatching {
        val backCameraInfos = CameraSelector.DEFAULT_BACK_CAMERA.filter(provider.availableCameraInfos)
        val logicalInfo = backCameraInfos.firstOrNull() ?: return@runCatching null
        if (!logicalInfo.isLogicalMultiCameraSupported()) return@runCatching null
        val physicalInfos = logicalInfo.getPhysicalCameraInfos()
        Log.i(
            "ScannerFocus",
            "pre-bind physicalCameraInfos count=${physicalInfos.size} (compare against Task 0's " +
                "post-bind logUltrawideFeasibility log — see spec's Open Questions)"
        )
        physicalInfos.firstNotNullOfOrNull { physicalInfo ->
            runCatching {
                val camera2Info = Camera2CameraInfo.from(physicalInfo)
                val focalLengths = camera2Info.getCameraCharacteristic(
                    android.hardware.camera2.CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS
                )
                if (focalLengths?.any { it < ULTRAWIDE_FOCAL_LENGTH_THRESHOLD_MM } == true) {
                    // getCameraId() (method), not the .cameraId JvmField, deliberately — only the
                    // method is interceptable by mockk in ScannerScreenTest.kt; both return the
                    // identical value (Camera2CameraInfo.kt: getCameraId() = cameraId).
                    camera2Info.getCameraId()
                } else {
                    null
                }
            }.getOrNull()
        }
    }.onFailure {
        Log.w("ScannerFocus", "selectUltrawidePhysicalCameraId failed, falling back to default back camera", it)
    }.getOrNull()

// Diagnostic only (Task 0 feasibility spike, docs/specs/2026-09-02-scanner-macro-focus.md) —
// before building any of that spec's real ultrawide-lens-selection behavior, confirms whether
// this device actually exposes the ultrawide as a distinct physical camera under CameraX's
// logical-multi-camera API. Samsung's Camera2 LIMITED hardware level may restrict third-party
// physical-camera access on some devices — genuinely unconfirmed for the S26 Ultra, contradictory
// even in the general-pattern research this spec cites. API confirmed against
// camera-core-1.6.1/camera-camera2-1.6.1's own -sources.jar (not guessed):
// CameraInfo.isLogicalMultiCameraSupported()/getPhysicalCameraInfos() are default interface
// methods, and Camera2CameraInfo.from() accepts any CameraInfo including a physical one — no
// Camera2CameraFilter needed just to answer this feasibility question.
// Run via `adb logcat -s ScannerFocus:*` on Skyler's real S26 Ultra — the emulator's virtual
// camera reports zero physical cameras, so this spike is meaningless there.
@androidx.annotation.OptIn(ExperimentalCamera2Interop::class)
private fun logUltrawideFeasibility(camera: Camera) {
    runCatching {
        val info = camera.cameraInfo
        val supportsMultiCam = info.isLogicalMultiCameraSupported()
        val physicalInfos = info.getPhysicalCameraInfos()
        Log.i(
            "ScannerFocus",
            "feasibility spike: isLogicalMultiCameraSupported=$supportsMultiCam " +
                "physicalCameraCount=${physicalInfos.size}"
        )
        if (physicalInfos.isEmpty()) {
            Log.i(
                "ScannerFocus",
                "feasibility spike: no physical cameras reported — ultrawide physical-lens " +
                    "selection is NOT usable on this device via this API"
            )
            return@runCatching
        }
        physicalInfos.forEach { physicalInfo ->
            runCatching {
                val camera2Info = Camera2CameraInfo.from(physicalInfo)
                val focalLengths = camera2Info.getCameraCharacteristic(
                    android.hardware.camera2.CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS
                )
                val minFocusDistance = camera2Info.getCameraCharacteristic(
                    android.hardware.camera2.CameraCharacteristics.LENS_INFO_MINIMUM_FOCUS_DISTANCE
                )
                val isUltrawideCandidate = focalLengths?.any { it < 20f } == true
                Log.i(
                    "ScannerFocus",
                    "feasibility spike: physical camera id=${camera2Info.cameraId} " +
                        "focalLengths=${focalLengths?.toList()} minFocusDistance=$minFocusDistance " +
                        "(<20mm focal length = ultrawide candidate: $isUltrawideCandidate)"
                )
            }.onFailure {
                Log.w(
                    "ScannerFocus",
                    "feasibility spike: could not read physical camera characteristics",
                    it
                )
            }
        }
    }.onFailure { Log.w("ScannerFocus", "feasibility spike: failed entirely", it) }
}

// Bounded retry for a metering request whose future THROWS (as opposed to completing with
// isFocusSuccessful=false) — confirmed live on Skyler's real S26 Ultra (2026-09-04, see gaps.md):
// the first post-bind startFocusAndMetering() call reliably throws
// "IllegalArgumentException: None of the specified AF/AE/AWB MeteringPoints is supported on this
// camera", resolving in ~10-20ms, while a second attempt at the identical point succeeds normally
// ~430ms later. A thrown future means metering never actually ran at all — categorically different
// from a completed-but-unsuccessful result, which legitimately should unblock capture per this
// function's original design. Without this retry, onSettled() fired near-instantly on the failed
// future, setting focusLocked=true within milliseconds of bind and defeating the entire
// gate-capture-on-real-focus-convergence mechanism for the rest of that request's lifetime.
private const val MAX_METERING_RETRIES = 2
private const val METERING_RETRY_DELAY_MS = 150L

private fun requestFocusAndMetering(
    camera: Camera,
    previewView: PreviewView,
    x: Float,
    y: Float,
    mainExecutor: Executor,
    mainHandler: Handler,
    attempt: Int = 0,
    onSettled: () -> Unit
) {
    if (previewView.width == 0 || previewView.height == 0) {
        onSettled()
        return
    }
    val point = previewView.meteringPointFactory.createPoint(x, y)
    val action = FocusMeteringAction.Builder(
        point,
        FocusMeteringAction.FLAG_AF
    )
        .setAutoCancelDuration(4, TimeUnit.SECONDS)
        .build()
    // FocusMeteringResult.isFocusSuccessful() is deliberately never inspected for gating (a
    // completed-but-unsuccessful result must still unblock capture, see spec) — but IS logged
    // below, purely diagnostic, to tell "focus is slow" apart from "focus never locks."
    val startedAt = System.currentTimeMillis()
    val future = runCatching { camera.cameraControl.startFocusAndMetering(action) }
        .onFailure { Log.w("ScannerFocus", "startFocusAndMetering threw at ($x, $y)", it) }
        .getOrNull()
    if (future == null) {
        onSettled()
        return
    }
    future.addListener({
        val elapsedMs = System.currentTimeMillis() - startedAt
        val outcome = runCatching { future.get() }
        outcome.onSuccess { result ->
            Log.i(
                "ScannerFocus",
                "settled in ${elapsedMs}ms at ($x, $y) — isFocusSuccessful=${result.isFocusSuccessful}"
            )
        }
        val exception = outcome.exceptionOrNull()
        if (exception != null && attempt < MAX_METERING_RETRIES) {
            Log.w(
                "ScannerFocus",
                "settled in ${elapsedMs}ms at ($x, $y) — future failed, retrying " +
                    "(attempt ${attempt + 1}/$MAX_METERING_RETRIES)",
                exception
            )
            mainHandler.postDelayed({
                requestFocusAndMetering(
                    camera, previewView, x, y, mainExecutor, mainHandler, attempt + 1, onSettled
                )
            }, METERING_RETRY_DELAY_MS)
            return@addListener
        }
        if (exception != null) {
            Log.w(
                "ScannerFocus",
                "settled in ${elapsedMs}ms at ($x, $y) — future failed, giving up after " +
                    "$MAX_METERING_RETRIES retries",
                exception
            )
        }
        onSettled()
    }, mainExecutor)
}

// ------- Camera preview -------------------------------------------------------

// ExperimentalCamera2Interop: Camera2Interop.Extender is how a physical camera id is actually
// applied to a use-case builder (see selectUltrawidePhysicalCameraId's comment for why the
// CameraSelector-level API this file used before doesn't work with bindToLifecycle's non-
// concurrent overload).
@androidx.annotation.OptIn(ExperimentalCamera2Interop::class)
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
            // 0L until camera bind actually completes; used to gate presence-reporting below.
            var bindCompletedAtMs = 0L

            fun triggerFocus(x: Float, y: Float) {
                val cam = camera ?: return
                focusRequestId++
                val myRequestId = focusRequestId
                focusLocked = false
                requestFocusAndMetering(cam, previewView, x, y, mainExecutor, mainHandler) {
                    if (myRequestId == focusRequestId) focusLocked = true
                }
            }

            // Builds a fresh Preview/ImageCapture/ImageAnalysis trio, optionally carrying a
            // physical-camera id via Camera2Interop.Extender (applied to ALL three builders —
            // CameraX throws IllegalArgumentException if use cases bound together in one
            // bindToLifecycle call carry different physical camera ids). This is the only
            // mechanism the bindToLifecycle overload this project calls actually honors — see
            // selectUltrawidePhysicalCameraId's comment. A separate, freshly-built trio (built
            // with physicalCameraId=null) is needed for a genuine fallback bind, since the
            // physical-camera-id option is baked into a use case's config at build() time and
            // can't be cleared from an already-built instance.
            fun buildUseCases(physicalCameraId: String?): Triple<Preview, ImageCapture, ImageAnalysis> {
                val previewBuilder = Preview.Builder()
                val captureBuilder = ImageCapture.Builder()
                val analysisBuilder = ImageAnalysis.Builder()
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                // Build.VERSION.SDK_INT check kept here too (not just at the caller that computes
                // ultrawideId) so this @RequiresApi(28) call is guarded in a form Android Lint's
                // static analysis can actually verify at this call site.
                if (physicalCameraId != null && Build.VERSION.SDK_INT >= 28) {
                    Camera2Interop.Extender(previewBuilder).setPhysicalCameraId(physicalCameraId)
                    Camera2Interop.Extender(captureBuilder).setPhysicalCameraId(physicalCameraId)
                    Camera2Interop.Extender(analysisBuilder).setPhysicalCameraId(physicalCameraId)
                }
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
                        if (justTransitioned) {
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

                // Camera2Interop.setPhysicalCameraId is @RequiresApi(28) — below that level the
                // whole ultrawide-selection path is skipped and this behaves exactly like "no
                // ultrawide found" always has (plain DEFAULT_BACK_CAMERA bind).
                val ultrawideId = if (Build.VERSION.SDK_INT >= 28) {
                    selectUltrawidePhysicalCameraId(provider)
                } else {
                    null
                }

                var (preview, capture, analysis) = buildUseCases(ultrawideId)
                onImageCaptureReady(capture)

                // Tracks which physical camera id is actually baked into the use cases CURRENTLY
                // bound — starts as the requested ultrawide id, nulled the moment the fallback
                // path (no physical id) engages. Distinct from ultrawideId, which stays the
                // original request even after falling back: boundVia/logCameraAfCapabilities must
                // report what's actually bound, not what was merely attempted (specs.md's own
                // requirement — a diagnostic that reports the attempt regardless of outcome is the
                // same class of bug this file's boundVia fix already had to correct once).
                var boundPhysicalId = ultrawideId

                provider.unbindAll()
                camera = runCatching {
                    provider.bindToLifecycle(
                        lifecycleOwner, CameraSelector.DEFAULT_BACK_CAMERA, preview, capture, analysis
                    )
                }.getOrElse { firstError ->
                    if (ultrawideId == null) {
                        // Nothing left to retry differently — log and degrade to no camera rather
                        // than letting this throw escape the Runnable posted to the main Looper
                        // (an uncaught throwable there is a process crash).
                        Log.w("ScannerFocus", "default-back-camera bind threw", firstError)
                        null
                    } else {
                        Log.w(
                            "ScannerFocus",
                            "ultrawide physical-camera bind threw, falling back to plain default back camera",
                            firstError
                        )
                        // Fresh use cases WITHOUT the physical camera id — the ones above already
                        // have it baked into their config from build(), so they can't be reused
                        // for a clean fallback. unbindAll() again immediately before this retry:
                        // LifecycleCamera's internal bound-session-config state is assigned before
                        // a throwing bind call with no rollback, so a stale partial record could
                        // otherwise merge into this retry's use cases.
                        val fallback = buildUseCases(null)
                        preview = fallback.first; capture = fallback.second; analysis = fallback.third
                        boundPhysicalId = null
                        onImageCaptureReady(capture)
                        provider.unbindAll()
                        runCatching {
                            provider.bindToLifecycle(
                                lifecycleOwner, CameraSelector.DEFAULT_BACK_CAMERA, preview, capture, analysis
                            )
                        }.getOrElse { secondError ->
                            Log.w("ScannerFocus", "fallback default-back-camera bind also threw", secondError)
                            null
                        }
                    }
                }
                bindCompletedAtMs = System.currentTimeMillis()
                camera?.let { cam ->
                    // NOT outcome-based, deliberately: actualId (the bound LOGICAL camera id) and
                    // boundPhysicalId (the REQUESTED PHYSICAL camera id, null if the fallback
                    // engaged) live in structurally disjoint id namespaces — see
                    // actualBoundCameraId's doc comment above. actualId == boundPhysicalId can
                    // never be true, on any device, regardless of whether the physical-camera-id
                    // bind actually took effect, so this can only log what was requested and what
                    // the logical id is — it cannot assert a match/mismatch verdict from that
                    // comparison. (Asserting a verdict the code can't actually observe is exactly
                    // what made this diagnostic lie before this fix — first by always claiming
                    // success, then by always claiming failure.) requestedPhysicalMinFocusDistance,
                    // logged alongside this in logCameraAfCapabilities, is the physical camera's own
                    // hardware capability (a fixed constant) — a useful data point, but it does NOT
                    // confirm the physical stream actually engaged; nothing in this log does.
                    val actualId = actualBoundCameraId(cam)
                    val boundVia = if (boundPhysicalId == null) {
                        "default-back-camera"
                    } else {
                        "ultrawide-physical-requested (requested physical id=$boundPhysicalId; bound " +
                            "logical camera id=$actualId — expected to differ, a physical id does " +
                            "not change the logical id, this is not a failure signal)"
                    }
                    logCameraAfCapabilities(cam, boundVia, boundPhysicalId)
                    logUltrawideFeasibility(cam)
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
                        message = "No Gemini API key set. Add your free key in Settings to use the scanner.",
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
                            text = when {
                                isRepeated ->
                                    "Still rate-limited after waiting — you may have hit your daily Gemini quota.\n\n" +
                                    "Check your usage at aistudio.google.com or search manually for now."
                                secondsLeft > 0 ->
                                    "Too many scans at once — Gemini needs a moment.\n\nRetry in $secondsLeft seconds."
                                else ->
                                    "Ready to scan again!"
                            },
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
