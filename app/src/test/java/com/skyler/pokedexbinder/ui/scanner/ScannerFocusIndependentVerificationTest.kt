package com.skyler.pokedexbinder.ui.scanner

import android.graphics.Bitmap
import android.graphics.ImageFormat
import android.hardware.camera2.CameraCharacteristics
import android.util.Log
import androidx.camera.camera2.interop.Camera2CameraInfo
import androidx.camera.core.CameraInfo
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageInfo
import androidx.camera.core.ImageProxy
import androidx.camera.core.impl.CameraInfoInternal
import androidx.camera.lifecycle.ProcessCameraProvider
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.mockkStatic
import io.mockk.unmockkObject
import io.mockk.unmockkStatic
import org.junit.After
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Before
import org.junit.Test

/**
 * Independent Tester-stage verification (dev-team-pipeline, 2026-09-04) of
 * `selectUltrawidePhysicalCameraId`'s fallback branches and `ImageProxyExt.toCroppedBitmap`'s
 * degenerate/throw-safety paths. Deliberately written fresh (own mock setups, own data,
 * own boundary cases) rather than re-running the Coder's committed ScannerScreenTest.kt /
 * ImageProxyExtTest.kt as-is, per the test-brief's requirement that the implementation and
 * a re-used test not be allowed to share the same latent bug undetected.
 */
class ScannerFocusIndependentVerificationTest {

    // ---- selectUltrawidePhysicalCameraId fixtures (same reflective-Unsafe technique as
    // ScannerScreenTest.kt is required here too -- CameraCharacteristics.Key statics are null
    // under the Android unit-test stub jar, and Kotlin inserts a non-null check at the call site
    // regardless of mocking -- see FocalLengthsKeyTestFixture) ----

    private lateinit var focalLengthsKey: CameraCharacteristics.Key<FloatArray>

    private lateinit var provider: ProcessCameraProvider

    @Before
    fun setUp() {
        provider = mockk()
        mockkObject(Camera2CameraInfo.Companion)
        mockkStatic(Log::class)
        every { Log.i(any(), any()) } returns 0
        every { Log.w(any(), any<String>()) } returns 0
        every { Log.w(any(), any<String>(), any()) } returns 0
        focalLengthsKey = mockk()
        FocalLengthsKeyTestFixture.set(focalLengthsKey)
    }

    @After
    fun tearDown() {
        FocalLengthsKeyTestFixture.set(null)
        unmockkObject(Camera2CameraInfo.Companion)
        unmockkStatic(Log::class)
    }

    private fun frontOnlyCameraInfo(): CameraInfoInternal {
        // A camera that exists but is FRONT-facing -- exercises the real, unmocked
        // CameraSelector.DEFAULT_BACK_CAMERA.filter() actually excluding it, rather than the
        // trivial "empty list" case already covered elsewhere.
        val info = mockk<CameraInfoInternal>()
        every { info.lensFacing } returns CameraSelector.LENS_FACING_FRONT
        return info
    }

    private fun backCameraInfo(
        multiCameraSupported: Boolean,
        physicalInfos: Set<CameraInfo> = emptySet()
    ): CameraInfoInternal {
        val info = mockk<CameraInfoInternal>()
        every { info.lensFacing } returns CameraSelector.LENS_FACING_BACK
        every { info.isLogicalMultiCameraSupported() } returns multiCameraSupported
        every { info.physicalCameraInfos } returns physicalInfos
        return info
    }

    private fun physicalCamera(
        id: String,
        focalLengths: FloatArray?,
        throwsOnRead: Boolean = false
    ): CameraInfo {
        val physicalInfo = mockk<CameraInfo>()
        val camera2Info = mockk<Camera2CameraInfo>()
        every { Camera2CameraInfo.from(physicalInfo) } returns camera2Info
        if (throwsOnRead) {
            every {
                camera2Info.getCameraCharacteristic(focalLengthsKey)
            } throws RuntimeException("independent-verification: characteristic read failed")
        } else {
            every { camera2Info.getCameraCharacteristic(focalLengthsKey) } returns focalLengths
            every { camera2Info.getCameraId() } returns id
        }
        return physicalInfo
    }

    // ---- 1. No back camera: a device that only reports a FRONT camera ----

    @Test
    fun `device with only a front camera -- filter excludes it, selector returns null`() {
        every { provider.availableCameraInfos } returns listOf(frontOnlyCameraInfo())
        assertNull(selectUltrawidePhysicalCameraId(provider))
    }

    // ---- 2. Multi-camera unsupported ----

    @Test
    fun `logical back camera present but isLogicalMultiCameraSupported is false -- returns null`() {
        val logical = backCameraInfo(multiCameraSupported = false)
        every { provider.availableCameraInfos } returns listOf(logical)
        assertNull(selectUltrawidePhysicalCameraId(provider))
    }

    // ---- 3. No qualifying focal length, including the exact threshold boundary (20mm itself
    // must NOT qualify -- the production check is strictly "<", not "<="). ----

    @Test
    fun `no physical camera under focal-length threshold -- boundary value exactly at threshold does not qualify`() {
        val exactlyAtThreshold = physicalCamera("9", floatArrayOf(20.0f))
        val aboveThreshold = physicalCamera("10", floatArrayOf(35f))
        val logical = backCameraInfo(
            multiCameraSupported = true,
            physicalInfos = setOf(exactlyAtThreshold, aboveThreshold)
        )
        every { provider.availableCameraInfos } returns listOf(logical)
        assertNull(selectUltrawidePhysicalCameraId(provider))
    }

    // ---- 4. A throwing candidate that is the ONLY candidate (no later good candidate to fall
    // through to) -- must degrade to null, not propagate/crash. ----

    @Test
    fun `sole physical candidate throws on characteristic read -- degrades to null, does not crash`() {
        val onlyCandidate = physicalCamera("4", focalLengths = null, throwsOnRead = true)
        val logical = backCameraInfo(
            multiCameraSupported = true,
            physicalInfos = setOf(onlyCandidate)
        )
        every { provider.availableCameraInfos } returns listOf(logical)
        assertNull(selectUltrawidePhysicalCameraId(provider))
    }

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
