package com.skyler.pokedexbinder.ui.scanner

import androidx.camera.core.ImageProxy

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
internal const val GUIDE_FRAME_WIDTH_RATIO = 0.75f

// Standard Pokémon TCG card aspect ratio (88mm x 63mm), portrait. Single source for the value
// previously triplicated across detectCardInFrame, guideFrameImageRect, and CardFrameOverlay —
// this file's own header comment on GUIDE_FRAME_WIDTH_RATIO claimed that class of drift was
// eliminated; it was, for the width ratio, not for this one.
internal const val CARD_ASPECT_RATIO = 88f / 63f

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

internal fun detectCardInFrame(imageProxy: ImageProxy): Boolean {
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
