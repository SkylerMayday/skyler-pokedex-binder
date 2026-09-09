package com.skyler.pokedexbinder.ui.scanner

import android.hardware.camera2.CameraCharacteristics
import android.util.Log
import androidx.camera.camera2.interop.Camera2CameraInfo
import androidx.camera.core.CameraInfo
import androidx.camera.core.CameraSelector
import androidx.camera.core.impl.CameraInfoInternal
import androidx.camera.lifecycle.ProcessCameraProvider
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.mockkStatic
import io.mockk.unmockkObject
import io.mockk.unmockkStatic
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class ScannerScreenTest {

    private lateinit var provider: ProcessCameraProvider

    // Reflective static-field override needed to make selectUltrawidePhysicalCameraId's happy
    // path reachable under the Android unit-test stub jar — see FocalLengthsKeyTestFixture's own
    // doc comment for why.
    private lateinit var focalLengthsKey: CameraCharacteristics.Key<FloatArray>

    @Before
    fun setUp() {
        provider = mockk()
        // Camera2CameraInfo.from(...) is a @JvmStatic companion function — Kotlin-to-Kotlin
        // calls (both here and in ScannerScreen.kt's production code) dispatch through the
        // companion object itself, not the Java-interop static bridge, so mockkObject on the
        // Companion (not mockkStatic on the class) is what actually intercepts it.
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

    // CameraSelector.DEFAULT_BACK_CAMERA.filter(...) — the real, unmocked CameraX implementation
    // selectUltrawidePhysicalCameraId calls — requires each CameraInfo to be an instance of
    // androidx.camera.core.impl.CameraInfoInternal (it downcasts internally); a plain
    // mockk<CameraInfo>() fails that check. Only the LOGICAL (back-camera) CameraInfo passes
    // through .filter(), so only it needs to be a CameraInfoInternal mock.
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

    // Physical CameraInfos are only ever passed to the (fully mocked, via mockkStatic)
    // Camera2CameraInfo.from(...), never to CameraSelector.filter(), so a plain CameraInfo mock
    // is sufficient here — no CameraInfoInternal requirement.
    private fun physicalCamera(
        id: String,
        focalLengths: FloatArray?,
        throwsOnRead: Boolean = false
    ): CameraInfo {
        val physicalInfo = mockk<CameraInfo>()
        val camera2Info = mockk<Camera2CameraInfo>()
        every { Camera2CameraInfo.from(physicalInfo) } returns camera2Info
        // Matched against the reflectively-injected focalLengthsKey (see setUp) — production
        // code will read that same (now non-null) static field value at runtime.
        if (throwsOnRead) {
            every {
                camera2Info.getCameraCharacteristic(focalLengthsKey)
            } throws IllegalStateException("characteristic read failed")
        } else {
            every {
                camera2Info.getCameraCharacteristic(focalLengthsKey)
            } returns focalLengths
            every { camera2Info.getCameraId() } returns id
        }
        return physicalInfo
    }

    // ---- selectUltrawidePhysicalCameraId ----

    @Test
    fun `no back camera in availableCameraInfos returns null`() {
        every { provider.availableCameraInfos } returns emptyList()
        assertNull(selectUltrawidePhysicalCameraId(provider))
    }

    @Test
    fun `back camera found but multi-camera not supported returns null`() {
        val logical = backCameraInfo(multiCameraSupported = false)
        every { provider.availableCameraInfos } returns listOf(logical)
        assertNull(selectUltrawidePhysicalCameraId(provider))
    }

    @Test
    fun `multi-camera supported but no physical camera under focal length threshold returns null`() {
        val mainLens = physicalCamera("1", floatArrayOf(24f))
        val telephoto = physicalCamera("6", floatArrayOf(70f))
        val logical = backCameraInfo(
            multiCameraSupported = true,
            physicalInfos = setOf(mainLens, telephoto)
        )
        every { provider.availableCameraInfos } returns listOf(logical)
        assertNull(selectUltrawidePhysicalCameraId(provider))
    }

    @Test
    fun `exactly one qualifying physical camera is selected`() {
        // Mirrors the real S26 Ultra's actual 4-camera data (spec, Task 0 spike): id=2 at 2.2mm
        // qualifies, id=5/6/7 at 6.5/7.0/18.6mm don't.
        val ultrawide = physicalCamera("2", floatArrayOf(2.2f))
        val wide = physicalCamera("5", floatArrayOf(6.5f))
        val main = physicalCamera("6", floatArrayOf(7.0f))
        val tele = physicalCamera("7", floatArrayOf(18.6f))
        val logical = backCameraInfo(
            multiCameraSupported = true,
            physicalInfos = setOf(ultrawide, wide, main, tele)
        )
        every { provider.availableCameraInfos } returns listOf(logical)

        val result = selectUltrawidePhysicalCameraId(provider)

        assertEquals("2", result)
    }

    @Test
    fun `a candidate whose characteristic read throws is skipped, later candidate still found`() {
        val broken = physicalCamera("3", focalLengths = null, throwsOnRead = true)
        val ultrawide = physicalCamera("2", floatArrayOf(2.2f))
        val logical = backCameraInfo(
            multiCameraSupported = true,
            physicalInfos = linkedSetOf(broken, ultrawide)
        )
        every { provider.availableCameraInfos } returns listOf(logical)

        val result = selectUltrawidePhysicalCameraId(provider)

        assertEquals("2", result)
    }

    @Test
    fun `provider availableCameraInfos throwing returns null instead of propagating`() {
        every { provider.availableCameraInfos } throws IllegalStateException("camera service dead")
        assertNull(selectUltrawidePhysicalCameraId(provider))
    }

    // ---- guideFrameImageRect (pure function, hand-computed expected values) ----

    // 4000x3000 (a real landscape camera-sensor buffer shape, width > height — matches this
    // file's own "Camera delivers landscape frames even in portrait mode" comment on
    // detectCardInFrame) rather than the old 1000x2000. At GUIDE_FRAME_WIDTH_RATIO=0.5 (bumped
    // 2026-09-10), the old dims' rotation-90/270 cases clamp into the degenerate path (already
    // covered separately below) instead of demonstrating a clean axis-swap — these dims don't.

    @Test
    fun `guideFrameImageRect rotation 0 centers rect in raw coordinates`() {
        val rect = guideFrameImageRect(rawWidth = 4000, rawHeight = 3000, rotationDegrees = 0)
        assertEquals(1000, rect.left)
        assertEquals(103, rect.top)
        assertEquals(3000, rect.right)
        assertEquals(2896, rect.bottom)
        assertEquals(2000, rect.width)
        assertEquals(2793, rect.height)
    }

    @Test
    fun `guideFrameImageRect rotation 90 axis-swaps via 4-corner remap, not a naive swap`() {
        val rect = guideFrameImageRect(rawWidth = 4000, rawHeight = 3000, rotationDegrees = 90)
        assertEquals(952, rect.left)
        assertEquals(750, rect.top)
        assertEquals(3047, rect.right)
        assertEquals(2250, rect.bottom)
    }

    @Test
    fun `guideFrameImageRect rotation 270 axis-swaps the opposite direction from 90`() {
        val rect = guideFrameImageRect(rawWidth = 4000, rawHeight = 3000, rotationDegrees = 270)
        assertEquals(953, rect.left)
        assertEquals(750, rect.top)
        assertEquals(3048, rect.right)
        assertEquals(2250, rect.bottom)
    }

    @Test
    fun `guideFrameImageRect rotation 180 mirrors the centered rect, off by the same 1px an odd gap already put between rotation 0's own top and bottom margins`() {
        // NOT a bug (this file already fixed the real version of this class of bug once — see
        // ImageRect's own doc comment on the exclusive/inclusive conversion). With these
        // dimensions, (dispH - guideH) is ODD (207), so rotation 0's own dTop/dBot split is
        // already asymmetric by construction (103 above the box, 104 below it) before any
        // rotation math runs — Int division truncates 103.5 to 103. Rotating 180 degrees swaps
        // which margin becomes "top", so the mapped top/bottom shift by that same pre-existing
        // 1px the un-rotated centering already had, not an error introduced by the rotation
        // transform. (The original 1000x2000 test dims exactly mirrored because that gap
        // happened to be even — a property of those specific numbers, not a general guarantee.)
        val rect = guideFrameImageRect(rawWidth = 4000, rawHeight = 3000, rotationDegrees = 180)
        assertEquals(1000, rect.left)
        assertEquals(104, rect.top)
        assertEquals(3000, rect.right)
        assertEquals(2897, rect.bottom)
    }

    @Test
    fun `guideFrameImageRect degenerate extreme aspect ratio stays coerced within bounds`() {
        // guideH computed from guideW's fixed card aspect ratio vastly exceeds this raw height,
        // forcing dTop negative pre-coercion — confirms coerceIn clamps rather than returning
        // out-of-range coordinates (the zero/negative-size *fallback* itself is ImageProxyExt's
        // job, tested separately in ImageProxyExtTest).
        // top/left clamp to [0, dim-1] (inclusive pixel index); right/bottom clamp to [0, dim]
        // (exclusive bound, matching Bitmap.createBitmap's (left, top, width, height) contract —
        // see ImageRect's own doc comment). bottom == rawHeight here is correct and maximal, not
        // off-by-one: it means the crop legitimately reaches the image's last valid row.
        val rect = guideFrameImageRect(rawWidth = 2000, rawHeight = 50, rotationDegrees = 0)
        assertTrue(rect.left in 0..1999)
        assertTrue(rect.right in 0..2000)
        assertTrue(rect.top in 0..49)
        assertTrue(rect.bottom in 0..50)
        assertEquals(0, rect.top)
        assertEquals(50, rect.bottom)
    }

    // ---- ImageRect.offsetBy ----

    @Test
    fun `offsetBy zero deltas returns an equal ImageRect (no-op backward-compat case)`() {
        val rect = guideFrameImageRect(rawWidth = 1000, rawHeight = 2000, rotationDegrees = 0)
        assertEquals(rect, rect.offsetBy(deltaLeft = 0, deltaTop = 0))
    }

    @Test
    fun `offsetBy nonzero deltas shifts all four bounds, leaves width and height unchanged`() {
        val rect = guideFrameImageRect(rawWidth = 1000, rawHeight = 2000, rotationDegrees = 0)

        val shifted = rect.offsetBy(deltaLeft = 100, deltaTop = 200)

        assertEquals(rect.left + 100, shifted.left)
        assertEquals(rect.top + 200, shifted.top)
        assertEquals(rect.right + 100, shifted.right)
        assertEquals(rect.bottom + 200, shifted.bottom)
        assertEquals(rect.width, shifted.width)
        assertEquals(rect.height, shifted.height)
    }
}
