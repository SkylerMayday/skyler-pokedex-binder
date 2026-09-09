package com.skyler.pokedexbinder.ui.scanner

import android.graphics.Bitmap
import android.graphics.ImageFormat
import android.graphics.Rect
import android.util.Log
import androidx.camera.core.ImageInfo
import androidx.camera.core.ImageProxy
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
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

    // width/height/rotation chosen to match ScannerScreenTest's hand-computed rotation-0 case:
    // guideFrameImageRect(1000, 2000, 0) == left=340 top=777 width=320 height=446.
    @Test
    fun `valid rect crops the decoded bitmap via Bitmap createBitmap`() {
        val cropped = mockk<Bitmap>()
        every { Bitmap.createBitmap(decodedBitmap, 340, 777, 320, 446) } returns cropped

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

    // ViewPort-constrained case: cropRect is smaller than and offset within the full frame
    // (1200x2400 full frame, 1000x2000 crop at origin (100,200)). Expected rect is
    // guideFrameImageRect(1000, 2000, 0) — left=340 top=777 width=320 height=446, per
    // ScannerScreenTest's own hand-computed rotation-0 case — shifted by (100,200):
    // left=440 top=977 width=320 height=446 (offsetBy leaves width/height unchanged).
    @Test
    fun `nonzero cropRect offset shifts guideFrameImageRect by cropRect origin`() {
        val cropped = mockk<Bitmap>()
        every { Bitmap.createBitmap(decodedBitmap, 440, 977, 320, 446) } returns cropped

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
}
