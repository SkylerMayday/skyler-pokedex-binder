package com.skyler.pokedexbinder.ui.scanner

import android.graphics.Bitmap
import android.graphics.ImageFormat
import android.util.Log
import androidx.camera.core.ImageInfo
import androidx.camera.core.ImageProxy
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import org.junit.Assert.assertSame
import org.junit.Test

/**
 * Independent Tester-stage verification (dev-team-pipeline, 2026-09-04) of
 * `ImageProxyExt.toCroppedBitmap`'s degenerate/throw-safety paths. Deliberately written fresh
 * (own mock setups, own data, own boundary cases) rather than re-running ImageProxyExtTest.kt
 * as-is, so the implementation and a re-used test cannot share the same latent bug undetected.
 */
class ScannerFocusIndependentVerificationTest {

    // ================================================================================
    // ImageProxyExt.toCroppedBitmap -- fresh degenerate-rect and throw-safety scenarios,
    // different mechanism/rotation/exception type from ImageProxyExtTest.kt's committed cases.
    // Single-plane JPEG-shaped proxy, same as ImageProxyExtTest.kt post-P0-2 -- the 3-plane YUV
    // shape this file used pre-fix never occurs in production for this capture path.
    // ================================================================================

    private lateinit var decodedBitmap: Bitmap

    private fun jpegImageProxy(width: Int, height: Int, rotationDegrees: Int): ImageProxy {
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
        every { proxy.cropRect } returns testRect(0, 0, width, height)
        return proxy
    }

    private fun setUpBitmapMocks() {
        mockkStatic(Bitmap::class, Log::class)
        decodedBitmap = mockk()
        every { Log.i(any(), any()) } returns 0
        every { Log.w(any(), any<String>()) } returns 0
        every { Log.w(any(), any<String>(), any()) } returns 0
    }

    private fun tearDownBitmapMocks() {
        unmockkStatic(Bitmap::class, Log::class)
    }

    // ---- degenerate rect via a near-zero HEIGHT (not width, per the committed test) combined
    // with a 90-degree rotation (not the committed test's rotation-0 case) -- exercises a
    // different branch of guideFrameImageRect's axis-swap before hitting the degenerate guard. ----

    @Test
    fun `degenerate rect from near-zero raw height at rotation 90 falls back to uncropped bitmap, no crash`() {
        setUpBitmapMocks()
        try {
            // isRotated=true for 90 degrees, so dispW=rawHeight. rawHeight=1 makes dispW=1,
            // guideW=(1*0.32).toInt()=0 -> zero-width rect -> degenerate guard must trigger.
            val result = jpegImageProxy(width = 500, height = 1, rotationDegrees = 90)
                .toCroppedBitmap()
            assertSame(decodedBitmap, result)
        } finally {
            tearDownBitmapMocks()
        }
    }

    // ---- throw-safety with OutOfMemoryError (an Error, not an Exception) -- confirms
    // runCatching's Throwable-wide catch genuinely covers this documented risk (spec explicitly
    // calls out OOM as a possible Bitmap.createBitmap failure mode on large sensor images) and
    // that it still degrades to the uncropped bitmap rather than crashing capture. ----

    @Test
    fun `Bitmap createBitmap throwing OutOfMemoryError still falls back to uncropped bitmap, no crash`() {
        setUpBitmapMocks()
        try {
            every {
                Bitmap.createBitmap(decodedBitmap, any(), any(), any(), any())
            } throws OutOfMemoryError("independent-verification: simulated OOM on large sensor image")

            val result = jpegImageProxy(width = 1000, height = 2000, rotationDegrees = 0)
                .toCroppedBitmap()

            assertSame(decodedBitmap, result)
        } finally {
            tearDownBitmapMocks()
        }
    }
}
