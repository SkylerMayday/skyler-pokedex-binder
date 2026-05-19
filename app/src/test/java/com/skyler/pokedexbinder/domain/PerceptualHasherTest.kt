package com.skyler.pokedexbinder.domain

import android.graphics.Bitmap
import com.skyler.pokedexbinder.data.model.TcgCard
import io.mockk.every
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
        hasher = PerceptualHasher(OkHttpClient(), server.url("/").toString())
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
}
