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
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * Independent Tester-stage re-verification of the review-verdict.md P0-2 finding, written fresh
 * for this re-test pass — NOT a copy of the Coder's ImageProxyExtTest.kt /
 * ScannerFocusIndependentVerificationTest.kt cases, and NOT trusting the Coder's own claim (in
 * changes.md) to have reproduced-then-deleted a scratch test.
 *
 * Two independent claims to prove:
 *  1. The OLD (pre-fix-pass) decode logic — a hand-rolled 3-plane NV21 reader that unconditionally
 *     indexes `planes[2]` — genuinely throws when handed a realistic single-plane JPEG-shaped
 *     ImageProxy (the actual shape CameraX's ImageCapture.OnImageCapturedCallback javadoc
 *     documents for every capture this project performs).
 *  2. The NEW shipped code (`ImageProxy.toCroppedBitmap`, ImageProxyExt.kt) never INDEXES into
 *     `planes` for decode — it delegates to CameraX's own proven `ImageProxy.toBitmap()` member —
 *     so the same single-plane JPEG shape does not throw. (It does read `planes.size` for a
 *     diagnostic log — a bounds-safe call, distinct from the indexed `planes[2]` access this test
 *     targets.)
 */
class P0_2_FreshVerificationTest {

    private fun singlePlaneJpegProxy(): ImageProxy {
        val proxy = mockk<ImageProxy>()
        val info = mockk<ImageInfo>()
        val onlyPlane = mockk<ImageProxy.PlaneProxy>()
        // Exactly one plane -- the real shape ImageCapture.OnImageCapturedCallback delivers
        // (single-plane JPEG), confirmed this pass by reading camera-core-1.6.1's own
        // ImageProxy.java: toBitmap()'s javadoc lists JPEG as a supported format alongside
        // YUV_420_888 and RGBA_8888.
        every { proxy.planes } returns arrayOf(onlyPlane)
        // planes[0].buffer is genuinely read by the old code before it ever reaches the
        // planes[2] line -- stub it so mockk's strict mode doesn't throw on the *wrong* line
        // first (an unstubbed call) and mask the actual ArrayIndexOutOfBoundsException this test
        // targets.
        every { onlyPlane.buffer } returns java.nio.ByteBuffer.allocate(4)
        every { proxy.width } returns 1000
        every { proxy.height } returns 2000
        every { proxy.format } returns ImageFormat.JPEG
        every { proxy.imageInfo } returns info
        every { info.rotationDegrees } returns 0
        every { proxy.cropRect } returns testRect(0, 0, 1000, 2000)
        return proxy
    }

    /**
     * Reconstruction of the pre-fix-pass decode logic (was `ImageProxyExt.kt`'s `toBitmap()`
     * before the P0-2 fix, per `git diff HEAD` against the last commit — `planes[0]` for luma,
     * `planes[2]` for chroma, i.e. an NV21 3-plane read). Deliberately stops before the
     * `YuvImage`/`BitmapFactory` calls, since the array-index failure this test targets happens
     * at the `planes[2]` line, strictly before any real Android graphics API is touched — so this
     * reconstruction doesn't need Robolectric or any Android-stub mocking to prove the point.
     */
    private fun oldNv21DecodeReconstruction(imageProxy: ImageProxy) {
        val yBuffer = imageProxy.planes[0].buffer
        @Suppress("UNUSED_VARIABLE")
        val vuBuffer = imageProxy.planes[2].buffer // <-- the line that throws
    }

    @Test
    fun `old NV21 decode reconstruction throws ArrayIndexOutOfBoundsException on a realistic single-plane JPEG proxy`() {
        val proxy = singlePlaneJpegProxy()
        assertEquals(1, proxy.planes.size)

        try {
            oldNv21DecodeReconstruction(proxy)
            fail("expected ArrayIndexOutOfBoundsException reading planes[2] on a 1-element planes array")
        } catch (e: ArrayIndexOutOfBoundsException) {
            // Expected -- this is exactly review-verdict.md's P0-2 finding, reproduced fresh.
        }
    }

    @Test
    fun `new toCroppedBitmap never indexes planes and does not throw on the same realistic single-plane JPEG proxy`() {
        mockkStatic(Bitmap::class, Log::class)
        try {
            every { Log.i(any(), any()) } returns 0
            every { Log.w(any(), any<String>()) } returns 0
            every { Log.w(any(), any<String>(), any()) } returns 0

            val proxy = singlePlaneJpegProxy()
            val decoded = mockk<Bitmap>()
            val cropped = mockk<Bitmap>()
            // Stub CameraX's own member decode (already verified, separately, against the real
            // ImageProxy.java javadoc to be JPEG-safe -- not this project's code, not re-tested
            // here) and the (mocked) crop step, so this test can call the real, unparameterized,
            // always-crops toCroppedBitmap() -- a later fix pass dropped the cropToGuideFrame arg
            // this test used to force resolution with.
            every { proxy.toBitmap() } returns decoded
            every { Bitmap.createBitmap(decoded, any(), any(), any(), any()) } returns cropped

            val result = proxy.toCroppedBitmap()

            assertTrue("expected the cropped CameraX-decoded bitmap back", result === cropped)
            // proxy.planes[1]/[2] are never stubbed, and mockk's default relaxed-off proxy throws
            // on any un-stubbed call the production code actually made -- the test passing is
            // proof toCroppedBitmap's decode path never INDEXES planes (the P0-2 bug: a hand-rolled
            // 3-plane reader hitting planes[2] on a real single-plane capture). This is narrower
            // than "never touches planes at all": a later fix pass restored a one-line diagnostic
            // log that reads planes.size (stubbed via singlePlaneJpegProxy()'s `proxy.planes`
            // return, not a fixed value) -- that's a bounds-safe .size call, not an index, so it
            // doesn't reintroduce what this test guards against.
        } finally {
            unmockkStatic(Bitmap::class, Log::class)
        }
    }
}
