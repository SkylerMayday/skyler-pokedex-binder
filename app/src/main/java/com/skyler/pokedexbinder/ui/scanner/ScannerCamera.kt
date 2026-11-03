package com.skyler.pokedexbinder.ui.scanner

import android.os.Handler
import android.util.Log
import androidx.camera.camera2.interop.Camera2CameraInfo
import androidx.camera.camera2.interop.ExperimentalCamera2Interop
import androidx.camera.core.Camera
import androidx.camera.core.FocusMeteringAction
import androidx.camera.view.PreviewView
import java.util.concurrent.Executor
import java.util.concurrent.TimeUnit

// ------- Camera focus ----------------------------------------------------------

// Minimum elapsed time after camera bind completes before ANY card presence can be
// reported to ScannerScreen — gives the user physical time to lift a card into frame
// before auto-capture logic can fire at all, independent of how fast the false-positive
// contrast heuristic + focus-lock gate settle against an empty/background scene.
// Additive to ScannerScreen's own holdDurationMs (1500ms) continuous-detection hold —
// together they set the true floor for auto-capture at bind-completion + this value +
// holdDurationMs (~3.5s total from camera bind to earliest possible auto-capture).
internal const val POST_BIND_DETECTION_DELAY_MS = 2000L

// Diagnostic only — logs the bound (logical) camera's real AF hardware capability, so "focus
// never locks" (isFocusSuccessful=false in requestFocusAndMetering's logs) can be told apart from
// "this lens has no usable close-range AF at all." Camera2CameraInfo is an experimental CameraX
// interop API, opted into only for this narrow diagnostic use, not the whole file. Note this
// reads the LOGICAL camera's characteristics; when the ultrawide is active via min zoom, its own
// (closer) focus floor is not reflected here.
@androidx.annotation.OptIn(ExperimentalCamera2Interop::class)
internal fun logCameraAfCapabilities(camera: Camera, boundVia: String) {
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
internal fun logUltrawideFeasibility(camera: Camera) {
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

internal fun requestFocusAndMetering(
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
