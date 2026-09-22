package com.skyler.pokedexbinder.ui.scanner

import android.graphics.Bitmap
import android.graphics.ImageFormat
import android.graphics.Matrix
import android.graphics.Rect
import android.util.Log
import androidx.camera.core.ImageInfo
import androidx.camera.core.ImageProxy
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkConstructor
import io.mockk.mockkStatic
import io.mockk.unmockkConstructor
import io.mockk.unmockkStatic
import io.mockk.verify
import org.junit.After
import org.junit.Assert.assertSame
import org.junit.Before
import org.junit.Test

class ImageProxyExtTest {

    private lateinit var decodedBitmap: Bitmap

    @Before
    fun setUp() {
        mockkStatic(Bitmap::class, Log::class)
        decodedBitmap = mockk()
        every { Log.i(any(), any()) } returns 0
        every { Log.w(any(), any<String>()) } returns 0
        every { Log.w(any(), any<String>(), any()) } returns 0
    }

    @After
    fun tearDown() {
        unmockkStatic(Bitmap::class, Log::class)
    }

    // Realistic single-plane JPEG-shaped ImageProxy — CameraX's own
    // ImageCapture.OnImageCapturedCallback javadoc guarantees this exact shape (single-plane
    // JPEG, never 3-plane YUV) for every capture callback this project uses. Deliberately NOT
    // the 3-plane YUV mock this file used before the P0-2 fix — that shape never occurs in
    // production and is exactly what let the previous ImageProxyExt.toBitmap regression (reading
    // planes[2] on a 1-element planes array, ArrayIndexOutOfBoundsException on every real
    // capture — see ImageProxyExt.kt's header comment and changes.md) ship undetected. Confirmed
    // by re-running this exact scenario against the pre-fix code during this pass: it threw
    // ArrayIndexOutOfBoundsException before reaching Bitmap decode at all.
    //
    // toCroppedBitmap decodes via CameraX's own real `ImageProxy.toBitmap()` member (not
    // something this project's code owns or needs to re-verify), so that call is stubbed
    // directly — width/height/format/planes are still set to a realistic single-plane shape so
    // this fixture documents and exercises the actual production shape, not just the crop math.
    // cropRect defaults to the full frame (Rect(0,0,width,height)) — matches ImageProxy's own
    // documented default when no ViewPort constrains the bind, and keeps every pre-existing test
    // below exercising the byte-identical-to-before no-op case (offsetBy(0,0)).
    private fun jpegImageProxy(
        width: Int = 1000,
        height: Int = 2000,
        rotationDegrees: Int = 0,
        cropRect: Rect = testRect(0, 0, width, height)
    ): ImageProxy {
        val proxy = mockk<ImageProxy>()
        val info = mockk<ImageInfo>()
        val plane = mockk<ImageProxy.PlaneProxy>()
        every { proxy.planes } returns arrayOf(plane)
        every { proxy.width } returns width
        every { proxy.height } returns height
        every { proxy.format } returns ImageFormat.JPEG
        every { proxy.imageInfo } returns info
        every { info.rotationDegrees } returns rotationDegrees
        every { proxy.toBitmap() } returns decodedBitmap
        every { proxy.cropRect } returns cropRect
        return proxy
    }

    // width/height/rotation match guideFrameImageRect(1000, 2000, 0) at
    // GUIDE_FRAME_WIDTH_RATIO=0.75 with CROP_MARGIN_FACTOR=1.30 applied (ScannerScreen.kt,
    // bumped from 1.10 2026-09-23): left=13 top=319 width=974 height=1361. (1.30f isn't exactly
    // representable in binary float — boxW/boxH truncate 1 lower than exact-decimal math
    // suggests; verified against a real `testDebugUnitTest` run, not just hand-derived.)
    @Test
    fun `valid rect crops the decoded bitmap via Bitmap createBitmap`() {
        val cropped = mockk<Bitmap>()
        every { Bitmap.createBitmap(decodedBitmap, 13, 319, 974, 1361) } returns cropped

        val result = jpegImageProxy(width = 1000, height = 2000, rotationDegrees = 0)
            .toCroppedBitmap()

        assertSame(cropped, result)
    }

    @Test
    fun `degenerate rect falls back to uncropped decoded bitmap`() {
        // width=1 collapses guideFrameImageRect to a zero-size rect (see ScannerScreenTest).
        val result = jpegImageProxy(width = 1, height = 2000, rotationDegrees = 0)
            .toCroppedBitmap()

        assertSame(decodedBitmap, result)
    }

    // [gaps.md P2, 2026-09-09] A zero-width cropRect used to reach guideFrameImageRect's own
    // coerceIn(0, rawWidth - 1) — rawWidth - 1 becomes -1, an empty range, throwing
    // IllegalArgumentException before this function's own uncropped-fallback guard ever ran.
    // Distinct from the case above: here the INPUT cropRect is degenerate, not just the computed
    // guide rect.
    @Test
    fun `degenerate cropRect falls back to uncropped decoded bitmap without throwing`() {
        val result = jpegImageProxy(
            width = 1000,
            height = 2000,
            rotationDegrees = 0,
            cropRect = testRect(100, 200, 100, 2200) // right == left -> cropWidth = 0
        ).toCroppedBitmap()

        assertSame(decodedBitmap, result)
    }

    // ViewPort-constrained case: cropRect is smaller than and offset within the full frame
    // (1200x2400 full frame, 1000x2000 crop at origin (100,200)). Expected rect is
    // guideFrameImageRect(1000, 2000, 0) — left=13 top=319 width=974 height=1361 at
    // GUIDE_FRAME_WIDTH_RATIO=0.75 with CROP_MARGIN_FACTOR=1.30 applied (bumped from 1.10
    // 2026-09-23) — shifted by (100,200): left=113 top=519 width=974 height=1361 (offsetBy
    // leaves width/height unchanged).
    @Test
    fun `nonzero cropRect offset shifts guideFrameImageRect by cropRect origin`() {
        val cropped = mockk<Bitmap>()
        every { Bitmap.createBitmap(decodedBitmap, 113, 519, 974, 1361) } returns cropped

        val result = jpegImageProxy(
            width = 1200,
            height = 2400,
            rotationDegrees = 0,
            cropRect = testRect(100, 200, 1100, 2200)
        ).toCroppedBitmap()

        assertSame(cropped, result)
    }

    @Test
    fun `Bitmap createBitmap throwing falls back to uncropped decoded bitmap`() {
        every {
            Bitmap.createBitmap(decodedBitmap, any(), any(), any(), any())
        } throws IllegalArgumentException("out of bounds")

        val result = jpegImageProxy(width = 1000, height = 2000, rotationDegrees = 0)
            .toCroppedBitmap()

        assertSame(decodedBitmap, result)
    }

    // rotation == 0 is the existing no-op path (all 5 tests above use it) — this test additionally
    // confirms no Matrix is ever constructed on that path, per the spec's explicit acceptance
    // criterion, not just that the returned bitmap matches.
    @Test
    fun `rotation 0 constructs no Matrix`() {
        mockkConstructor(Matrix::class)
        try {
            val cropped = mockk<Bitmap>()
            every { Bitmap.createBitmap(decodedBitmap, 13, 319, 974, 1361) } returns cropped

            val result = jpegImageProxy(width = 1000, height = 2000, rotationDegrees = 0)
                .toCroppedBitmap()

            assertSame(cropped, result)
            verify(exactly = 0) { anyConstructed<Matrix>().postRotate(any()) }
        } finally {
            unmockkConstructor(Matrix::class)
        }
    }

    // rotationDegrees=90: guideFrameImageRect(1000, 2000, 90) -> left=0 top=26 width=1000
    // height=1949 (display space swaps for a rotated raw buffer; CROP_MARGIN_FACTOR=1.30 as of
    // 2026-09-23, was 1.10/height=1650). Non-Robolectric stub jar throws "not mocked" on real
    // Matrix/Bitmap method calls, so both are fully intercepted: the constructor via
    // mockkConstructor, and the 6-arg Bitmap.createBitmap(bitmap, x, y, w, h, Matrix, filter)
    // overload via its own stub returning a distinct mock, asserted via assertSame so a wrong
    // overload (e.g. accidentally returning the pre-rotation `cropped`) fails loudly.
    @Test
    fun `nonzero rotation rotates the cropped bitmap via Matrix postRotate`() {
        mockkConstructor(Matrix::class)
        try {
            val cropped = mockk<Bitmap>()
            val rotated = mockk<Bitmap>()
            every { Bitmap.createBitmap(decodedBitmap, 0, 26, 1000, 1949) } returns cropped
            every { cropped.width } returns 1000
            every { cropped.height } returns 1949
            every { anyConstructed<Matrix>().postRotate(90f) } returns true
            every {
                Bitmap.createBitmap(cropped, 0, 0, 1000, 1949, any<Matrix>(), true)
            } returns rotated

            val result = jpegImageProxy(width = 1000, height = 2000, rotationDegrees = 90)
                .toCroppedBitmap()

            assertSame(rotated, result)
        } finally {
            unmockkConstructor(Matrix::class)
        }
    }
}
