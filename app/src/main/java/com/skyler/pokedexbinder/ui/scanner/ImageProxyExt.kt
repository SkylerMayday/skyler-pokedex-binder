package com.skyler.pokedexbinder.ui.scanner

import android.graphics.Bitmap
import android.util.Log
import androidx.camera.core.ImageProxy

// [gaps.md, 2026-09-04] This file used to declare its own `ImageProxy.toBitmap()` extension
// that hand-decoded a 3-plane NV21 buffer. CameraX 1.6.1 added its own zero-arg default
// `ImageProxy.toBitmap(): Bitmap` member after this project's extension was first written —
// Kotlin always resolves a member over a same-named extension, so that hand-rolled decode went
// dead the moment the library added its own (silently — no compiler warning). A later diff
// passing a named `cropToGuideFrame` argument un-shadowed it (the named arg only matches the
// extension's signature), reviving the dead NV21 path — but every capture callback this project
// uses actually delivers single-plane JPEG (CameraX's own ImageCapture.OnImageCapturedCallback
// javadoc), so the revived decode threw ArrayIndexOutOfBoundsException on planes[2] on every
// real capture. Fixed by decoding via CameraX's own proven member (`ImageProxy.toBitmap()`,
// JPEG-safe) and applying the guide-frame crop as a separate step below, under a function name
// that can never again collide with a future CameraX member.
//
// [review-verdict.md, iteration 2] `cropToGuideFrame` used to default to `false` and was never
// called that way in production — the only real call site (ScannerViewModel.kt) always passed
// `true`. A boolean parameter nobody varies, defaulting to the value that silently contradicts
// the function's own name, is the same-shaped trap the header above just eliminated for the
// decode step. Dropped: this function always crops, matching its only real caller.
fun ImageProxy.toCroppedBitmap(): Bitmap {
    Log.i("ScannerFocus", "captured format=$format planes=${planes.size}")
    val decoded = toBitmap()

    val rect = guideFrameImageRect(width, height, imageInfo.rotationDegrees)
    if (rect.width <= 0 || rect.height <= 0) {
        Log.w(
            "ScannerFocus",
            "degenerate crop rect ($rect) for ${width}x$height rot=${imageInfo.rotationDegrees}, using uncropped"
        )
        return decoded
    }
    return runCatching {
        Bitmap.createBitmap(decoded, rect.left, rect.top, rect.width, rect.height)
    }.getOrElse {
        Log.w("ScannerFocus", "crop failed, using uncropped", it)
        decoded
    }
}
