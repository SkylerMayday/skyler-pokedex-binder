package com.skyler.pokedexbinder.ui.scanner

import com.skyler.pokedexbinder.domain.ScanProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ScannerScreenTest {

    // ---- guideFrameImageRect (pure function, hand-computed expected values) ----

    // 4000x3000 (a real landscape camera-sensor buffer shape, width > height — matches this
    // file's own "Camera delivers landscape frames even in portrait mode" comment on
    // detectCardInFrame) rather than 1000x2000. At GUIDE_FRAME_WIDTH_RATIO=0.75, guideH already
    // exceeds this raw HEIGHT (3000) at rotation 0/180's un-rotated 4:3 orientation, so those two
    // saturate into the same clamped path the "extreme aspect ratio" test below also covers.
    // 90/270 (the orientation every real capture actually uses) stay unclamped. CROP_MARGIN_FACTOR
    // (1.30x as of 2026-09-23, applied inside this function only) inflates the box these corners
    // are derived from, which is why 90 and 270 no longer land on identical numbers below — the
    // extra margin's own odd-vs-even split differs by rotation, not a bug (see
    // CROP_MARGIN_FACTOR's own doc comment). Values below hand-recomputed for 1.30 (was 1.10).

    @Test
    fun `guideFrameImageRect rotation 0 saturates the clamp path at this ratio (real captures never use rotation 0)`() {
        val rect = guideFrameImageRect(rawWidth = 4000, rawHeight = 3000, rotationDegrees = 0)
        assertEquals(50, rect.left)
        assertEquals(0, rect.top)
        assertEquals(3949, rect.right)
        assertEquals(3000, rect.bottom)
        assertEquals(3899, rect.width)
        assertEquals(3000, rect.height)
    }

    @Test
    fun `guideFrameImageRect rotation 90 axis-swaps via 4-corner remap, not a naive swap`() {
        val rect = guideFrameImageRect(rawWidth = 4000, rawHeight = 3000, rotationDegrees = 90)
        assertEquals(0, rect.left)
        assertEquals(38, rect.top)
        assertEquals(4000, rect.right)
        assertEquals(2963, rect.bottom)
    }

    @Test
    fun `guideFrameImageRect rotation 270 lands 1px off rotation 90 once CROP_MARGIN_FACTOR applies (arithmetic, not a bug)`() {
        val rect = guideFrameImageRect(rawWidth = 4000, rawHeight = 3000, rotationDegrees = 270)
        assertEquals(0, rect.left)
        assertEquals(37, rect.top)
        assertEquals(4000, rect.right)
        assertEquals(2962, rect.bottom)
    }

    @Test
    fun `guideFrameImageRect rotation 180 saturates the clamp path identically to rotation 0 at this ratio`() {
        // NOT a bug (this file already fixed the real version of this class of bug once — see
        // ImageRect's own doc comment on the exclusive/inclusive conversion). At
        // GUIDE_FRAME_WIDTH_RATIO=0.75, rotation 0 and 180 both fully saturate coerceIn's clamp
        // on these dims (see header comment above) — the mapped rect is identical either way, not
        // an off-by-1 mirror like it was at 0.5. Still correct, just no longer demonstrating a
        // near-symmetric mirror since there's no unclamped margin left to be asymmetric about.
        val rect = guideFrameImageRect(rawWidth = 4000, rawHeight = 3000, rotationDegrees = 180)
        assertEquals(51, rect.left)
        assertEquals(0, rect.top)
        assertEquals(3950, rect.right)
        assertEquals(3000, rect.bottom)
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

    // ---- cardRectInCrop ----

    // 974x1361 is the guideFrameImageRect(1000, 2000, 0) crop. cardW = (974/1.30f).toInt() = 749,
    // cardH = (749*88f/63f).toInt() = 1046, left = (974-749)/2 = 112, top = (1361-1046)/2 = 157.
    @Test
    fun `cardRectInCrop centres a guide-width card inside the crop`() {
        assertEquals(ImageRect(112, 157, 861, 1203), cardRectInCrop(974, 1361))
    }

    @Test
    fun `cardRectInCrop clamps card height to a crop shorter than the card aspect implies`() {
        val rect = cardRectInCrop(974, 900)
        assertEquals(ImageRect(112, 0, 861, 900), rect)
        assertTrue(rect.bottom <= 900)
    }

    // ---- rateLimitMessage ----

    @Test
    fun `rateLimitMessage Gemini strings are unchanged`() {
        assertEquals(
            "Still rate-limited after waiting — you may have hit your daily Gemini quota.\n\n" +
                "Check your usage at aistudio.google.com or search manually for now.",
            rateLimitMessage(ScanProvider.GEMINI, isRepeated = true, secondsLeft = 10)
        )
        assertEquals(
            "Too many scans at once — Gemini needs a moment.\n\nRetry in 42 seconds.",
            rateLimitMessage(ScanProvider.GEMINI, isRepeated = false, secondsLeft = 42)
        )
        assertEquals("Ready to scan again!", rateLimitMessage(ScanProvider.GEMINI, isRepeated = false, secondsLeft = 0))
    }

    // Mutation: hardcoding console.anthropic.com for Gemini turns this red.
    @Test
    fun `rateLimitMessage Gemini repeated points at aistudio not anthropic`() {
        val msg = rateLimitMessage(ScanProvider.GEMINI, isRepeated = true, secondsLeft = 10)
        assertTrue(msg.contains("aistudio.google.com"))
        assertTrue(!msg.contains("anthropic"))
    }

    @Test
    fun `rateLimitMessage Claude repeated names Claude not Gemini`() {
        val msg = rateLimitMessage(ScanProvider.CLAUDE, isRepeated = true, secondsLeft = 10)
        assertTrue(msg.contains("Claude"))
        assertTrue(!msg.contains("Gemini") && !msg.contains("aistudio"))
        assertTrue(msg.contains("console.anthropic.com"))
    }

    @Test
    fun `rateLimitMessage Claude countdown names Claude`() {
        val msg = rateLimitMessage(ScanProvider.CLAUDE, isRepeated = false, secondsLeft = 42)
        assertTrue(msg.contains("Claude needs a moment"))
        assertTrue(msg.contains("Retry in 42 seconds"))
        assertEquals("Ready to scan again!", rateLimitMessage(ScanProvider.CLAUDE, isRepeated = false, secondsLeft = 0))
    }
}
