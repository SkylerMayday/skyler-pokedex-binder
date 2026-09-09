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

    @Test
    fun `guideFrameImageRect rotation 0 centers rect in raw coordinates`() {
        val rect = guideFrameImageRect(rawWidth = 1000, rawHeight = 2000, rotationDegrees = 0)
        assertEquals(340, rect.left)
        assertEquals(777, rect.top)
        assertEquals(660, rect.right)
        assertEquals(1223, rect.bottom)
        assertEquals(320, rect.width)
        assertEquals(446, rect.height)
    }

    @Test
    fun `guideFrameImageRect rotation 90 axis-swaps via 4-corner remap, not a naive swap`() {
        val rect = guideFrameImageRect(rawWidth = 1000, rawHeight = 2000, rotationDegrees = 90)
        assertEquals(53, rect.left)
        assertEquals(680, rect.top)
        assertEquals(946, rect.right)
        assertEquals(1320, rect.bottom)
    }

    @Test
    fun `guideFrameImageRect rotation 270 axis-swaps the opposite direction from 90`() {
        val rect = guideFrameImageRect(rawWidth = 1000, rawHeight = 2000, rotationDegrees = 270)
        assertEquals(54, rect.left)
        assertEquals(680, rect.top)
        assertEquals(947, rect.right)
        assertEquals(1320, rect.bottom)
    }

    @Test
    fun `guideFrameImageRect rotation 180 mirrors both axes back onto the centered rect exactly`() {
        // The guide box is centered, so a true (bug-free) 180-degree rotation must map it onto
        // itself exactly — same left/top/right/bottom as rotation 0. Before the exclusive-bound
        // fix, this came out 1px off on every axis (339/776/659/1222) because the rotation math
        // treated the exclusive right/bottom bounds as literal pixel coordinates.
        val rect = guideFrameImageRect(rawWidth = 1000, rawHeight = 2000, rotationDegrees = 180)
        assertEquals(340, rect.left)
        assertEquals(777, rect.top)
        assertEquals(660, rect.right)
        assertEquals(1223, rect.bottom)
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
