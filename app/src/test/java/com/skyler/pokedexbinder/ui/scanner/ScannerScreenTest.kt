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

    // android.hardware.camera2.CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS is a real
    // Android SDK static field that the Android-unit-test stub jar leaves null (a genuine,
    // well-known Android-testing limitation — it is never actually null on a real device, only
    // in this JVM test environment). Production code (selectUltrawidePhysicalCameraId) passes it
    // directly into Camera2CameraInfo.getCameraCharacteristic(key: CameraCharacteristics.Key<T>),
    // a Kotlin-declared non-null parameter — Kotlin's compiler inserts a null-check at that call
    // site regardless of mocking, so no every{}/matcher configuration on the (mocked)
    // Camera2CameraInfo receiver can prevent the NPE by itself. Reflectively overwriting the
    // static field with a mock Key for the test's duration is what actually makes the "happy
    // path" (a qualifying candidate) reachable at all. Field.setAccessible alone isn't enough to
    // write a `static final` field on JDK 12+ (the old "strip the modifiers field" trick no
    // longer works — Field no longer exposes its own modifiers reflectively), so this goes
    // through sun.misc.Unsafe's direct static-field write instead, which isn't gated by the
    // FINAL modifier at all. Looked up purely by name (Class.forName/reflection), not a direct
    // `sun.misc.Unsafe` source reference, since that internal package isn't on this module's
    // Kotlin compile classpath (Android projects deliberately don't expose it — it doesn't exist
    // on-device either) even though it's present at JVM unit-test runtime.
    private val focalLengthsKeyField = CameraCharacteristics::class.java
        .getField("LENS_INFO_AVAILABLE_FOCAL_LENGTHS")
        .apply { isAccessible = true }
    private val unsafeClass = Class.forName("sun.misc.Unsafe")
    private val unsafe: Any = requireNotNull(
        unsafeClass.getDeclaredField("theUnsafe").apply { isAccessible = true }.get(null)
    )
    private val staticFieldBase = unsafeClass.getMethod("staticFieldBase", java.lang.reflect.Field::class.java)
    private val staticFieldOffset = unsafeClass.getMethod("staticFieldOffset", java.lang.reflect.Field::class.java)
    private val putObject = unsafeClass.getMethod(
        "putObject", Any::class.java, Long::class.javaPrimitiveType, Any::class.java
    )

    private lateinit var focalLengthsKey: CameraCharacteristics.Key<FloatArray>

    private fun setFocalLengthsKeyField(value: CameraCharacteristics.Key<FloatArray>?) {
        val base = staticFieldBase.invoke(unsafe, focalLengthsKeyField)
        val offset = staticFieldOffset.invoke(unsafe, focalLengthsKeyField) as Long
        putObject.invoke(unsafe, base, offset, value)
    }

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
        setFocalLengthsKeyField(focalLengthsKey)
    }

    @After
    fun tearDown() {
        setFocalLengthsKeyField(null)
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
        assertEquals(679, rect.top)
        assertEquals(946, rect.right)
        assertEquals(1319, rect.bottom)
    }

    @Test
    fun `guideFrameImageRect rotation 270 axis-swaps the opposite direction from 90`() {
        val rect = guideFrameImageRect(rawWidth = 1000, rawHeight = 2000, rotationDegrees = 270)
        assertEquals(53, rect.left)
        assertEquals(680, rect.top)
        assertEquals(946, rect.right)
        assertEquals(1320, rect.bottom)
    }

    @Test
    fun `guideFrameImageRect rotation 180 mirrors both axes`() {
        val rect = guideFrameImageRect(rawWidth = 1000, rawHeight = 2000, rotationDegrees = 180)
        assertEquals(339, rect.left)
        assertEquals(776, rect.top)
        assertEquals(659, rect.right)
        assertEquals(1222, rect.bottom)
    }

    @Test
    fun `guideFrameImageRect degenerate extreme aspect ratio stays coerced within bounds`() {
        // guideH computed from guideW's fixed card aspect ratio vastly exceeds this raw height,
        // forcing dTop negative pre-coercion — confirms coerceIn clamps rather than returning
        // out-of-range coordinates (the zero/negative-size *fallback* itself is ImageProxyExt's
        // job, tested separately in ImageProxyExtTest).
        val rect = guideFrameImageRect(rawWidth = 2000, rawHeight = 50, rotationDegrees = 0)
        assertTrue(rect.left in 0..1999)
        assertTrue(rect.right in 0..1999)
        assertTrue(rect.top in 0..49)
        assertTrue(rect.bottom in 0..49)
        assertEquals(0, rect.top)
        assertEquals(49, rect.bottom)
    }
}
