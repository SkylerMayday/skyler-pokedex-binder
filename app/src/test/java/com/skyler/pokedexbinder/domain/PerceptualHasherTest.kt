package com.skyler.pokedexbinder.domain

import android.graphics.Bitmap
import com.skyler.pokedexbinder.data.model.TcgCard
import android.graphics.BitmapFactory
import io.mockk.Runs
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

class PerceptualHasherTest {

    private lateinit var server: MockWebServer
    private lateinit var hasher: PerceptualHasher

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        hasher = PerceptualHasher(OkHttpClient()).also { it.baseUrl = server.url("/").toString() }
    }

    @After
    fun tearDown() = server.shutdown()

    @Test
    fun `hammingDistance of identical hashes is zero`() {
        assertEquals(0, hasher.hammingDistance(0xFFL, 0xFFL))
    }

    @Test
    fun `hammingDistance of all-zeros vs all-ones is 64`() {
        assertEquals(64, hasher.hammingDistance(0L, -1L))
    }

    @Test
    fun `computeHash returns same value for identical bitmaps`() {
        mockkStatic(Bitmap::class)
        try {
            val bitmap = mockk<Bitmap>()
            // createScaledBitmap returns the same mock so we can control getPixels
            every { Bitmap.createScaledBitmap(bitmap, 8, 8, false) } returns bitmap
            every { bitmap.recycle() } just Runs
            every { bitmap.getPixels(any(), any(), any(), any(), any(), any(), any()) } answers {
                val arr = firstArg<IntArray>()
                arr.fill(0xFF_80_80_80.toInt()) // uniform gray
            }
            assertEquals(hasher.computeHash(bitmap), hasher.computeHash(bitmap))
        } finally {
            unmockkStatic(Bitmap::class)
        }
    }

    // Regression test for the pre-fix `1L shl i` overflow: a 16x16 (256-pixel) resize wrapped
    // pixel index into the low 6 bits of a Long shift, silently OR-ing pixels i and i+64/128/192
    // onto the same bit. With the 8x8 (64-pixel) resize, two genuinely different images must
    // never collide onto the same hash.
    @Test
    fun `computeHash never collides for two distinguishably-different images`() {
        mockkStatic(Bitmap::class)
        try {
            fun makePixelBitmap(pixels: IntArray): Bitmap {
                val b = mockk<Bitmap>()
                every { Bitmap.createScaledBitmap(b, 8, 8, false) } returns b
                every { b.recycle() } just Runs
                every { b.getPixels(any(), any(), any(), any(), any(), any(), any()) } answers {
                    pixels.copyInto(firstArg())
                }
                return b
            }

            // Checkerboard pattern vs. its inverse — distinguishably different, would have
            // collided to -1L under the old 256-pixel/64-bit-shift bug for many index patterns.
            val checkerboard = IntArray(64) { i ->
                if (i % 2 == 0) 0xFF_FF_FF_FF.toInt() else 0xFF_00_00_00.toInt()
            }
            val topHalfWhite = IntArray(64) { i ->
                if (i < 32) 0xFF_FF_FF_FF.toInt() else 0xFF_00_00_00.toInt()
            }

            val hashA = hasher.computeHash(makePixelBitmap(checkerboard))
            val hashB = hasher.computeHash(makePixelBitmap(topHalfWhite))

            assertNotEquals(hashA, hashB)
        } finally {
            unmockkStatic(Bitmap::class)
        }
    }

    @Test
    fun `findBestMatch returns null for empty list`() = runTest {
        val bitmap = mockk<Bitmap>()
        assertNull(hasher.findBestMatch(emptyList(), bitmap))
    }

    @Test
    fun `findBestMatch returns only card with MAX_VALUE margin when list has one element`() = runTest {
        mockkStatic(Bitmap::class, BitmapFactory::class)
        try {
            val bitmap = mockk<Bitmap>()
            every { Bitmap.createScaledBitmap(bitmap, 8, 8, false) } returns bitmap
            every { bitmap.recycle() } just Runs
            every { bitmap.getPixels(any(), any(), any(), any(), any(), any(), any()) } answers {
                firstArg<IntArray>().fill(0xFF_80_80_80.toInt())
            }
            every { BitmapFactory.decodeByteArray(any(), any(), any()) } returns bitmap
            val card = TcgCard("id1", "Pikachu", "25", "Base Set", "http://example.com/img.jpg", listOf("Pikachu"))

            server.enqueue(MockResponse().setBody("x"))
            val result = hasher.findBestMatch(listOf(card), bitmap)
            assertEquals(card, result?.card)
            assertEquals(Int.MAX_VALUE, result?.margin)
        } finally {
            unmockkStatic(Bitmap::class, BitmapFactory::class)
        }
    }

    @Test
    fun `findBestMatch picks card whose hash is closest to captured bitmap and reports margin`() = runTest {
        mockkStatic(Bitmap::class, BitmapFactory::class)
        try {
            fun makePixelBitmap(pixels: IntArray): Bitmap {
                val b = mockk<Bitmap>()
                every { Bitmap.createScaledBitmap(b, 8, 8, false) } returns b
                every { b.recycle() } just Runs
                every { b.getPixels(any(), any(), any(), any(), any(), any(), any()) } answers {
                    pixels.copyInto(firstArg())
                }
                return b
            }

            // First 32 pixels white (gray=255), last 32 black (gray=0) → mean=127.5, only the
            // first 32 bits clear the mean → hash = 0x00000000FFFFFFFFL (32 bits set, not -1L).
            val whiteBlack = IntArray(64) { i ->
                if (i < 32) 0xFF_FF_FF_FF.toInt() else 0xFF_00_00_00.toInt()
            }
            // All gray (128,128,128) → gray=128 not > mean=128.0 → hash = 0L
            val allGray = IntArray(64) { 0xFF_80_80_80.toInt() }

            val capturedBitmap = makePixelBitmap(whiteBlack)
            val card1Bitmap = makePixelBitmap(whiteBlack) // same hash → distance 0
            val card2Bitmap = makePixelBitmap(allGray)    // hash 0L → distance = bitCount(32 set bits) = 32

            server.enqueue(MockResponse().setBody("x"))
            server.enqueue(MockResponse().setBody("x"))
            every { BitmapFactory.decodeByteArray(any(), any(), any()) } returnsMany
                listOf(card1Bitmap, card2Bitmap)

            val card1 = TcgCard("id1", "Pikachu", "25", "Base Set", "card1", listOf("Pikachu"))
            val card2 = TcgCard("id2", "Charizard", "4", "Base Set", "card2", listOf("Charizard"))

            val result = hasher.findBestMatch(listOf(card1, card2), capturedBitmap)
            assertEquals(card1, result?.card)
            assertEquals(0, result?.distance)
            assertEquals(32, result?.margin)
        } finally {
            unmockkStatic(Bitmap::class, BitmapFactory::class)
        }
    }
}
