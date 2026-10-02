package com.skyler.pokedexbinder.ui.scanner

import android.Manifest
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.MotionEvent
import androidx.camera.camera2.interop.Camera2CameraInfo
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

// Guide frame width as a fraction of display-space width. History: 0.32 (main-lens 18cm floor,
// stale once the ultrawide physical lens was bound) -> 0.5 (~15cm target, 3x the ultrawide's real
// 5cm floor, session 13's first live test). Bumped again 2026-09-10 (session 14) to 0.75 (~10cm
// target, 2x margin over the 5cm floor) after live-testing 0.5 produced 3 genuine (non-transient)
// failures in one sitting — Furfrou misread as a different species entirely, Furfrou and Castform
// both twice failed to OCR the printed card number (parsed.cardNumber came back null all 3 times).
// Root cause confirmed directly, not guessed: replayed a manually-taken CLOSE-UP photo of the same
// physical Castform card through this app's own exact Gemini call (same model, same prompt, same
// 1024px/JPEG-q80 pre-send downscale in GeminiCardScanner.kt) — Gemini read the number correctly
// (116/172) at that framing, proving Gemini's OCR is not the bottleneck; the app's own capture at
// 0.5's ~15cm distance was giving Gemini less usable card detail than a tight, confident shot does.
// A larger ratio means more of the frame (and more real card pixels post-crop) regardless of minor
// positioning slack. Still first-pass, not final — re-tune again from here if 0.75 makes the
// ultrawide's autofocus start hunting/failing to lock near its own close-focus floor, or if genuine
// (non-transient) mismatches keep recurring after this. Shared by detectCardInFrame's analysis-space
// sampling and CardFrameOverlay's drawn UI geometry so the two can never drift apart.
private const val GUIDE_FRAME_WIDTH_RATIO = 0.75f

// Standard Pokémon TCG card aspect ratio (88mm x 63mm), portrait. Single source for the value
// previously triplicated across detectCardInFrame, guideFrameImageRect, and CardFrameOverlay —
// this file's own header comment on GUIDE_FRAME_WIDTH_RATIO claimed that class of drift was
// eliminated; it was, for the width ratio, not for this one.
private const val CARD_ASPECT_RATIO = 88f / 63f

// Still uncalibrated — same convention as GUIDE_FRAME_WIDTH_RATIO's history. Grows the
// ACTUAL CAPTURE crop beyond the drawn guide box (same center) so a card judged "in frame"
// against the tight visual target doesn't get header/footer clipped. Does NOT affect
// CardFrameOverlay's drawn box or detectCardInFrame's presence heuristic — both must keep
// matching what's on screen exactly.
// 2026-09-23: bumped 1.10 -> 1.30. First real live-device data (session 17, S26 Ultra,
// scan_debug_1790101276039.jpg) showed 1.10 was clearly insufficient — the captured card was
// clipped on ALL FOUR edges (text cut mid-word left and right, no header/HP visible at top at
// all), not just header/footer as originally assumed. Still a guess, not measured against the
// physical card — needs another live scan to confirm 1.30 is enough, not just less wrong.
private const val CROP_MARGIN_FACTOR = 1.30f

// Guide-frame (width, height) in display-space pixels for a display of the given width. Single
// source for detectCardInFrame's sampling geometry and guideFrameImageRect's crop geometry.
private fun guideFrameSize(dispW: Int): Pair<Int, Int> {
    val guideW = (dispW * GUIDE_FRAME_WIDTH_RATIO).toInt()
    return guideW to (guideW * CARD_ASPECT_RATIO).toInt()
}

// Maps a display-space (portrait) point to raw image-space for a frame of rawWidth x rawHeight
// delivered at the given CameraX rotationDegrees. A point transform: pass real pixel coordinates,
// not exclusive bounds.
private fun displayToImage(dx: Int, dy: Int, rotationDegrees: Int, rawWidth: Int, rawHeight: Int): Pair<Int, Int> =
    when (rotationDegrees) {
        90 -> Pair(dy, rawHeight - 1 - dx)
        270 -> Pair(rawWidth - 1 - dy, dx)
        180 -> Pair(rawWidth - 1 - dx, rawHeight - 1 - dy)
        else -> Pair(dx, dy)
    }

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
    val (guideW, guideH) = guideFrameSize(dispW)
    if (guideH >= dispH) return false

    val dLeft  = (dispW - guideW) / 2
    val dTop   = (dispH - guideH) / 2
    val dRight = dLeft + guideW
    val dBot   = dTop  + guideH

    val plane     = imageProxy.planes[0]
    val buffer    = plane.buffer
    val rowStride = plane.rowStride
    val pixStride = plane.pixelStride

    fun luma(dx: Int, dy: Int): Int {
        val cx = dx.coerceIn(0, dispW - 1)
        val cy = dy.coerceIn(0, dispH - 1)
        val (ix, iy) = displayToImage(cx, cy, rotation, iw, ih)
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
// matching Bitmap.createBitmap's (left, top, width, height) convention. guideFrameImageRect
// converts to inclusive coordinates before rotating and back to exclusive after (see its own
// comment) specifically so this exclusive-bound convention holds for all 4 rotation cases, not
// just rotationDegrees == 0.
internal data class ImageRect(val left: Int, val top: Int, val right: Int, val bottom: Int) {
    val width: Int get() = right - left
    val height: Int get() = bottom - top
}

// Computes the guide-frame rect in raw image-space (pre-rotation) pixel coordinates, for an
// image of the given raw width/height and CameraX rotationDegrees. Shares guideFrameSize and
// displayToImage with detectCardInFrame's live sampling; ImageProxyExt's post-capture crop calls
// this directly — all must agree on the same guide geometry CardFrameOverlay draws, or a
// captured photo's crop won't match what the user aimed at. `internal` (not private) for JVM-unit-test access from ScannerScreenTest.kt
// and for use from ImageProxyExt.kt (same package).
internal fun guideFrameImageRect(rawWidth: Int, rawHeight: Int, rotationDegrees: Int): ImageRect {
    val isRotated = rotationDegrees == 90 || rotationDegrees == 270
    val dispW = if (isRotated) rawHeight else rawWidth
    val dispH = if (isRotated) rawWidth else rawHeight

    val (guideW, guideH) = guideFrameSize(dispW)
    val boxW = (guideW * CROP_MARGIN_FACTOR).toInt()
    val boxH = (guideH * CROP_MARGIN_FACTOR).toInt()
    val dLeft = (dispW - boxW) / 2
    val dTop = (dispH - boxH) / 2
    val dRight = dLeft + boxW
    val dBot = dTop + boxH

    fun toImage(dx: Int, dy: Int) = displayToImage(dx, dy, rotationDegrees, rawWidth, rawHeight)

    // Map all 4 corners, not just top-left/bottom-right — a 90/270 rotation swaps which raw-image
    // axis corresponds to display-width vs -height, so the mapped rect must be re-normalized
    // (min/max over all 4 mapped corners) rather than assuming corner order survives the rotation.
    //
    // dRight/dBot are EXCLUSIVE bounds (one past the last valid pixel) but toImage's rotation
    // formulas are point transforms meant for actual pixel coordinates — passing an exclusive
    // bound straight in silently mistranslates it as a real pixel, shifting the mapped rect by
    // 1px on any axis that gets flipped (90/180/270 all flip at least one axis; 0 flips none,
    // which is why this only ever showed up on 3 of the 4 cases). Fixed by converting the
    // exclusive corners to their last-inclusive-pixel equivalent (-1) before mapping, then
    // converting the mapped result back to exclusive (+1) once min/max is taken.
    val dRightIncl = dRight - 1
    val dBotIncl = dBot - 1
    val corners = listOf(
        toImage(dLeft, dTop), toImage(dRightIncl, dTop),
        toImage(dLeft, dBotIncl), toImage(dRightIncl, dBotIncl)
    )
    val left = corners.minOf { it.first }.coerceIn(0, rawWidth - 1)
    val top = corners.minOf { it.second }.coerceIn(0, rawHeight - 1)
    val right = (corners.maxOf { it.first } + 1).coerceIn(0, rawWidth)
    val bottom = (corners.maxOf { it.second } + 1).coerceIn(0, rawHeight)
    return ImageRect(left, top, right, bottom)
}

// Card rect inside a guideFrameImageRect-sized, upright crop bitmap. Assumes the physical card fills
// the guide frame. Width derives from the crop: cropW = guideW*CROP_MARGIN_FACTOR -> guideW = cropW/factor;
// height = guideW*CARD_ASPECT_RATIO (NOT cropH/factor: boxH is clamped to the frame on 4:3 sensors, so
// cropH is unreliable). Used by PerceptualHasher to locate the art window in a captured photo.
internal fun cardRectInCrop(cropWidth: Int, cropHeight: Int): ImageRect {
    val cardW = (cropWidth / CROP_MARGIN_FACTOR).toInt().coerceAtMost(cropWidth)
    val cardH = (cardW * CARD_ASPECT_RATIO).toInt().coerceAtMost(cropHeight)
    val left = (cropWidth - cardW) / 2
    val top = (cropHeight - cardH) / 2
    return ImageRect(left, top, left + cardW, top + cardH)
}

// Repositions an ImageRect computed relative to a crop region's own (0,0) origin into that
// region's actual position within a larger coordinate space (e.g. ImageProxy.cropRect's
// left/top within the full decoded-bitmap frame). Zero deltas are a no-op — used by
// ImageProxyExt.toCroppedBitmap() to keep today's full-frame behavior byte-identical when no
// ViewPort constrains the crop.
internal fun ImageRect.offsetBy(deltaLeft: Int, deltaTop: Int): ImageRect =
    ImageRect(
        left = left + deltaLeft,
        top = top + deltaTop,
        right = right + deltaLeft,
        bottom = bottom + deltaTop
    )

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
// interop API, opted into only for this narrow diagnostic use, not the whole file. Note this
// reads the LOGICAL camera's characteristics; when the ultrawide is active via min zoom, its own
// (closer) focus floor is not reflected here.
@androidx.annotation.OptIn(ExperimentalCamera2Interop::class)
private fun logCameraAfCapabilities(camera: Camera, boundVia: String) {
    runCatching {
        val info = Camera2CameraInfo.from(camera.cameraInfo)
        val chars = info.getCameraCharacteristic(
            android.hardware.camera2.CameraCharacteristics.LENS_INFO_MINIMUM_FOCUS_DISTANCE
        )
        val afModes = info.getCameraCharacteristic(
            android.hardware.camera2.CameraCharacteristics.CONTROL_AF_AVAILABLE_MODES
        )
        Log.i(
            "ScannerFocus",
            "camera id=${info.cameraId} boundVia=$boundVia minFocusDistance=$chars (0 or null = " +
                "fixed-focus lens, cannot focus at all) afAvailableModes=${afModes?.toList()}"
        )
    }.onFailure { Log.w("ScannerFocus", "could not read camera AF characteristics", it) }
}

// Threshold matching Task 0 spike's own filter: a physical camera whose focal length is
// under this value (mm) is treated as an ultrawide candidate for close-range focus.
private const val ULTRAWIDE_FOCAL_LENGTH_THRESHOLD_MM = 20f

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
                val isUltrawideCandidate =
                    focalLengths?.any { it < ULTRAWIDE_FOCAL_LENGTH_THRESHOLD_MM } == true
                Log.i(
                    "ScannerFocus",
                    "feasibility spike: physical camera id=${camera2Info.cameraId} " +
                        "focalLengths=${focalLengths?.toList()} minFocusDistance=$minFocusDistance " +
                        "(<${ULTRAWIDE_FOCAL_LENGTH_THRESHOLD_MM}mm focal length = ultrawide candidate: " +
                        "$isUltrawideCandidate)"
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
