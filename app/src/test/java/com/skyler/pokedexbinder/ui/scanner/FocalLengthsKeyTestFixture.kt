package com.skyler.pokedexbinder.ui.scanner

import android.hardware.camera2.CameraCharacteristics

// Shared by ScannerScreenTest and ScannerFocusIndependentVerificationTest (previously duplicated
// verbatim in both — [gaps.md, 2026-09-09]).
//
// android.hardware.camera2.CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS is a real
// Android SDK static field that the Android-unit-test stub jar leaves null (a genuine, well-known
// Android-testing limitation — it is never actually null on a real device, only in this JVM test
// environment). Production code (selectUltrawidePhysicalCameraId) passes it directly into
// Camera2CameraInfo.getCameraCharacteristic(key: CameraCharacteristics.Key<T>), a Kotlin-declared
// non-null parameter — Kotlin's compiler inserts a null-check at that call site regardless of
// mocking, so no every{}/matcher configuration on the (mocked) Camera2CameraInfo receiver can
// prevent the NPE by itself. Reflectively overwriting the static field with a mock Key for the
// test's duration is what actually makes the "happy path" (a qualifying candidate) reachable at
// all. Field.setAccessible alone isn't enough to write a `static final` field on JDK 12+ (the old
// "strip the modifiers field" trick no longer works — Field no longer exposes its own modifiers
// reflectively), so this goes through sun.misc.Unsafe's direct static-field write instead, which
// isn't gated by the FINAL modifier at all. Looked up purely by name (Class.forName/reflection),
// not a direct `sun.misc.Unsafe` source reference, since that internal package isn't on this
// module's Kotlin compile classpath (Android projects deliberately don't expose it — it doesn't
// exist on-device either) even though it's present at JVM unit-test runtime.
internal object FocalLengthsKeyTestFixture {
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

    fun set(value: CameraCharacteristics.Key<FloatArray>?) {
        val base = staticFieldBase.invoke(unsafe, focalLengthsKeyField)
        val offset = staticFieldOffset.invoke(unsafe, focalLengthsKeyField) as Long
        putObject.invoke(unsafe, base, offset, value)
    }
}
