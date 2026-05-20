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
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
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
            every { Bitmap.createScaledBitmap(bitmap, 16, 16, false) } returns bitmap
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

    @Test
    fun `findBestMatch returns null for empty list`() = runTest {
        val bitmap = mockk<Bitmap>()
        assertNull(hasher.findBestMatch(emptyList(), bitmap))
    }

    @Test
    fun `findBestMatch returns only card when list has one element`() = runTest {
        val bitmap = mockk<Bitmap>()
        val card = TcgCard("id1", "Pikachu", "25", "Base Set", "http://example.com/img.jpg", listOf("Pikachu"))
        val result = hasher.findBestMatch(listOf(card), bitmap)
        assertEquals(card, result)
    }

    @Test
    fun `findBestMatch picks card whose hash is closest to captured bitmap`() = runTest {
        mockkStatic(Bitmap::class, BitmapFactory::class)
        try {
            fun makePixelBitmap(pixels: IntArray): Bitmap {
                val b = mockk<Bitmap>()
                every { Bitmap.createScaledBitmap(b, 16, 16, false) } returns b
                every { b.recycle() } just Runs
                every { b.getPixels(any(), any(), any(), any(), any(), any(), any()) } answers {
                    pixels.copyInto(firstArg())
                }
                return b
            }

            // First 128 pixels white, last 128 black → hash = -1L (all bits set)
            val whiteBlack = IntArray(256) { i ->
                if (i < 128) 0xFF_FF_FF_FF.toInt() else 0xFF_00_00_00.toInt()
            }
            // All gray (128,128,128) → gray=128 not > mean=128.0 → hash = 0L
            val allGray = IntArray(256) { 0xFF_80_80_80.toInt() }

            val capturedBitmap = makePixelBitmap(whiteBlack)
            val card1Bitmap = makePixelBitmap(whiteBlack) // same hash → distance 0
            val card2Bitmap = makePixelBitmap(allGray)    // different hash → distance > 0

            server.enqueue(MockResponse().setBody("x"))
            server.enqueue(MockResponse().setBody("x"))
            every { BitmapFactory.decodeByteArray(any(), any(), any()) } returnsMany
                listOf(card1Bitmap, card2Bitmap)

            val card1 = TcgCard("id1", "Pikachu", "25", "Base Set", "card1", listOf("Pikachu"))
            val card2 = TcgCard("id2", "Charizard", "4", "Base Set", "card2", listOf("Charizard"))

            val result = hasher.findBestMatch(listOf(card1, card2), capturedBitmap)
            assertEquals(card1, result)
        } finally {
            unmockkStatic(Bitmap::class, BitmapFactory::class)
        }
    }
}
