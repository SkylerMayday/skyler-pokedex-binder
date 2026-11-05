package com.skyler.pokedexbinder.domain

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import com.skyler.pokedexbinder.data.model.TcgCard
import com.skyler.pokedexbinder.ui.scanner.cardRectInCrop
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

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

    /** Bitmap mock whose getPixels follows Bitmap.getPixels(pixels, offset, stride, x, y, width, height); f returns grey 0..255. */
    private fun fakeBitmap(w: Int, h: Int, f: (Int, Int) -> Int): Bitmap {
        val b = mockk<Bitmap>()
        every { b.width } returns w
        every { b.height } returns h
        every { b.getPixels(any(), any(), any(), any(), any(), any(), any()) } answers {
            val pixels = firstArg<IntArray>()
            val offset = secondArg<Int>()
            val stride = thirdArg<Int>()
            val x = arg<Int>(3)
            val y = arg<Int>(4)
            val width = arg<Int>(5)
            val height = arg<Int>(6)
            for (yy in 0 until height) for (xx in 0 until width) {
                val g = f(x + xx, y + yy).coerceIn(0, 255)
                pixels[offset + yy * stride + xx] = (0xFF shl 24) or (g shl 16) or (g shl 8) or g
            }
        }
        return b
    }

    // Smooth, distinct low-frequency patterns (box-averaged, so pixel-level detail doesn't matter).
    private fun patternA(x: Int, y: Int) = (128 + 100 * Math.sin(x / 37.0) * Math.cos(y / 53.0)).toInt()
    private fun patternB(x: Int, y: Int) = (128 + 100 * Math.sin(x / 11.0 + y / 7.0)).toInt()

    @Test
    fun `hammingDistance of identical hashes is zero`() {
        assertEquals(0, hasher.hammingDistance(0xFFL, 0xFFL))
    }

    // Raw Long op; the hash itself only ever uses 63 bits (see ArtHash).
    @Test
    fun `hammingDistance of all-zeros vs all-ones is 64`() {
        assertEquals(64, hasher.hammingDistance(0L, -1L))
    }

    @Test
    fun `computeHash returns same value for identical bitmaps`() {
        val a = fakeBitmap(200, 280, { x, y -> patternA(x, y) })
        val b = fakeBitmap(200, 280, { x, y -> patternA(x, y) })
        assertEquals(hasher.computeHash(a), hasher.computeHash(b))
    }

    @Test
    fun `computeHash differs for two clearly different bitmaps`() {
        val leftRight = fakeBitmap(200, 280) { x, _ -> if (x < 100) 255 else 0 }
        val topBottom = fakeBitmap(200, 280) { _, y -> if (y < 140) 255 else 0 }
        assertNotEquals(hasher.computeHash(leftRight), hasher.computeHash(topBottom))
    }

    @Test
    fun `computeHash with a card rect ignores pixels outside the art box`() {
        val card = ArtRect(0, 0, 100, 140)
        val art = ArtHash.artRect(card)
        fun inArt(x: Int, y: Int) = x >= art.left && x < art.right && y >= art.top && y < art.bottom
        val darkBorder = fakeBitmap(100, 140) { x, y -> if (inArt(x, y)) patternA(x, y) else 0 }
        val lightBorder = fakeBitmap(100, 140) { x, y -> if (inArt(x, y)) patternA(x, y) else 255 }

        assertEquals(hasher.computeHash(darkBorder, card), hasher.computeHash(lightBorder, card))
        // null card = whole bitmap is the card = same rect as the full-bitmap ArtRect
        assertEquals(hasher.computeHash(darkBorder, card), hasher.computeHash(darkBorder))
    }

    @Test
    fun `computeHash returns 0 for a degenerate bitmap instead of throwing`() {
        assertEquals(0L, hasher.computeHash(fakeBitmap(1, 1) { _, _ -> 200 }))
    }

    @Test
    fun `findBestMatch returns null for empty list`() = runTest {
        val bitmap = mockk<Bitmap>()
        assertNull(hasher.findBestMatch(emptyList(), bitmap))
    }

    @Test
    fun `findBestMatch returns only card with MAX_VALUE margin when list has one element`() = runTest {
        mockkStatic(BitmapFactory::class)
        try {
            val captured = fakeBitmap(974, 1361, { x, y -> patternA(x, y) })
            val reference = fakeBitmap(749, 1046, { x, y -> patternA(x, y) })
            every { BitmapFactory.decodeByteArray(any(), any(), any()) } returns reference
            val card = TcgCard("id1", "Pikachu", "25", "Base Set", "card1", listOf("Pikachu"))

            server.enqueue(MockResponse().setBody("x"))
            val result = hasher.findBestMatch(listOf(card), captured)
            assertEquals(card, result?.card)
            assertEquals(Int.MAX_VALUE, result?.margin)
            assertEquals(listOf("id1" to result?.distance), result?.ranked)
        } finally {
            unmockkStatic(BitmapFactory::class)
        }
    }

    @Test
    fun `findBestMatch picks card whose hash is closest to captured bitmap and reports margin`() = runTest {
        mockkStatic(BitmapFactory::class)
        try {
            val capW = 974
            val capH = 1361
            val crop = cardRectInCrop(capW, capH)
            // Captured = pattern A laid over the crop; card 1's reference image IS that card region,
            // so its art window is pixel-identical -> distance 0. Card 2 is a different pattern.
            val captured = fakeBitmap(capW, capH) { x, y -> patternA(x, y) }
            val card1Bitmap = fakeBitmap(crop.width, crop.height) { x, y -> patternA(x + crop.left, y + crop.top) }
            val card2Bitmap = fakeBitmap(crop.width, crop.height, { x, y -> patternB(x, y) })

            val expectedD2 = hasher.hammingDistance(
                hasher.computeHash(captured, ArtRect(crop.left, crop.top, crop.right, crop.bottom)),
                hasher.computeHash(card2Bitmap)
            )

            // Downloads run in parallel, so responses/decodes are keyed by path/body, not by order.
            server.dispatcher = echoPathDispatcher()
            every { BitmapFactory.decodeByteArray(any(), any(), any()) } answers {
                when (String(firstArg<ByteArray>())) {
                    "card1" -> card1Bitmap
                    else -> card2Bitmap
                }
            }

            val card1 = TcgCard("id1", "Pikachu", "25", "Base Set", "card1", listOf("Pikachu"))
            val card2 = TcgCard("id2", "Charizard", "4", "Base Set", "card2", listOf("Charizard"))

            val result = hasher.findBestMatch(listOf(card1, card2), captured)
            assertEquals(card1, result?.card)
            assertEquals(0, result?.distance)
            assertEquals(expectedD2, result?.margin)
            assertEquals(listOf("id1" to 0, "id2" to expectedD2), result?.ranked)
        } finally {
            unmockkStatic(BitmapFactory::class)
        }
    }

    private fun echoPathDispatcher() = object : Dispatcher() {
        override fun dispatch(request: RecordedRequest) =
            MockResponse().setBody(request.path!!.trimStart('/'))
    }

    private fun cards(n: Int) = (1..n).map { TcgCard("id$it", "N$it", "$it", "Set", "card$it", listOf("N$it")) }

    @Test
    fun `ties resolve by candidate order under parallel download`() = runTest {
        mockkStatic(BitmapFactory::class)
        try {
            val captured = fakeBitmap(974, 1361) { x, y -> patternA(x, y) }
            val same = fakeBitmap(749, 1046) { x, y -> patternB(x, y) }
            every { BitmapFactory.decodeByteArray(any(), any(), any()) } returns same
            // Earlier candidates answer slower, so completion order is the reverse of list order.
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    val n = request.path!!.trimStart('/').removePrefix("card").toInt()
                    return MockResponse().setBody("x").setBodyDelay((7 - n) * 80L, TimeUnit.MILLISECONDS)
                }
            }
            val list = cards(6)

            val result = hasher.findBestMatch(list, captured)

            assertEquals("id1", result?.card?.id)
            assertEquals(list.take(5).map { it.id }, result?.ranked?.map { it.first })
        } finally {
            unmockkStatic(BitmapFactory::class)
        }
    }

    @Test
    fun `parallelism is bounded to 4`() = runTest {
        mockkStatic(BitmapFactory::class)
        try {
            val captured = fakeBitmap(974, 1361) { x, y -> patternA(x, y) }
            every { BitmapFactory.decodeByteArray(any(), any(), any()) } returns
                fakeBitmap(749, 1046) { x, y -> patternB(x, y) }
            val inFlight = AtomicInteger()
            val maxSeen = AtomicInteger()
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    maxSeen.accumulateAndGet(inFlight.incrementAndGet()) { a, b -> maxOf(a, b) }
                    Thread.sleep(100)
                    inFlight.decrementAndGet()
                    return MockResponse().setBody("x")
                }
            }

            hasher.findBestMatch(cards(12), captured)

            assertTrue("max=${maxSeen.get()}", maxSeen.get() <= 4)
            assertTrue("max=${maxSeen.get()}", maxSeen.get() >= 2)
        } finally {
            unmockkStatic(BitmapFactory::class)
        }
    }

    @Test
    fun `cancellation propagates`() = runBlocking {
        mockkStatic(BitmapFactory::class)
        try {
            val captured = fakeBitmap(974, 1361) { x, y -> patternA(x, y) }
            every { BitmapFactory.decodeByteArray(any(), any(), any()) } returns
                fakeBitmap(749, 1046) { x, y -> patternB(x, y) }
            val started = CompletableDeferred<Unit>()
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    started.complete(Unit)
                    // Blocking OkHttp calls already in flight can't be interrupted, so they finish (~1.2s);
                    // the second wave of 4 (another ~1.2s) must never start once cancelled.
                    return MockResponse().setBody("x").setBodyDelay(1200, TimeUnit.MILLISECONDS)
                }
            }
            val job = launch { hasher.findBestMatch(cards(8), captured) }
            started.await()

            job.cancel()
            withTimeout(2_000) { job.join() }
            // Real time: had the second wave been allowed to start, it would begin right after the
            // first finishes (~1.2s) and show up here. Mutation: `async` -> `GlobalScope.async` in
            // findBestMatch leaves the children running -> requestCount 8, not 4.
            delay(1_800)

            assertEquals(4, server.requestCount)
        } finally {
            unmockkStatic(BitmapFactory::class)
        }
    }
}
