package com.skyler.pokedexbinder.ui.scanner

import android.graphics.Rect

// [spec Open Questions] android.graphics.Rect's 4-arg constructor runs under this project's
// non-Robolectric unit-test stub jar WITHOUT throwing, but silently leaves every field at its
// JVM-default 0 instead of the requested values — confirmed empirically this session: a real
// `Rect(0, 0, width, height)` produced a zero-width/zero-height rect, tripping
// guideFrameImageRect's own `coerceIn(0, rawWidth - 1)` guard with "maximum -1 is less than
// minimum 0" (rawWidth read back as 0). Field *assignment* (direct PUTFIELD, not a stubbable
// method call) works correctly regardless of the stub jar — this builds a blank real Rect via
// the also-silently-no-op no-arg constructor, then sets its public mutable fields directly.
// Shared by ImageProxyExtTest, P0_2_FreshVerificationTest, and
// ScannerFocusIndependentVerificationTest — all three need a real (non-mockk) Rect for
// ImageProxy.cropRect, since production code reads its fields via direct field access too.
internal fun testRect(left: Int, top: Int, right: Int, bottom: Int): Rect =
    Rect().apply {
        this.left = left
        this.top = top
        this.right = right
        this.bottom = bottom
    }
