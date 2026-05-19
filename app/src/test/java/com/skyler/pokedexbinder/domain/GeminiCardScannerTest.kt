package com.skyler.pokedexbinder.domain

import android.graphics.Bitmap
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

class GeminiCardScannerTest {

    private lateinit var server: MockWebServer
    private lateinit var scanner: GeminiCardScanner
    private val bitmap: Bitmap = mockk {
        every { compress(any(), any(), any()) } answers {
            // Write dummy bytes so Base64 encoding has something to work with
            thirdArg<java.io.OutputStream>().write(byteArrayOf(0, 1, 2, 3))
            true
        }
    }

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        val moshi = Moshi.Builder().addLast(KotlinJsonAdapterFactory()).build()
        scanner = GeminiCardScanner(OkHttpClient(), moshi, server.url("/").toString())
    }

    @After
    fun tearDown() = server.shutdown()

    @Test
    fun `scan returns ParsedCardInfo on success`() = runTest {
        server.enqueue(MockResponse().setBody("""
            {"candidates":[{"content":{"parts":[{"text":"{\"name\":\"Pikachu\",\"number\":\"25\",\"setTotal\":\"185\",\"hp\":\"60\",\"artist\":\"Atsuko Nishida\"}"}]}}]}
        """.trimIndent()))

        val result = scanner.scan(bitmap, "test-key")

        assertEquals("Pikachu", result.cardName)
        assertEquals("25", result.cardNumber)
        assertEquals("185", result.setTotal)
        assertEquals("60", result.hp)
        assertEquals("Atsuko Nishida", result.artist)
    }

    @Test
    fun `scan returns nulls for missing fields`() = runTest {
        server.enqueue(MockResponse().setBody("""
            {"candidates":[{"content":{"parts":[{"text":"{\"name\":\"Pikachu\",\"number\":null,\"setTotal\":null,\"hp\":null,\"artist\":null}"}]}}]}
        """.trimIndent()))

        val result = scanner.scan(bitmap, "test-key")

        assertEquals("Pikachu", result.cardName)
        assertNull(result.cardNumber)
        assertNull(result.setTotal)
        assertNull(result.hp)
        assertNull(result.artist)
    }

    @Test
    fun `scan throws RateLimitException on 429`() = runTest {
        server.enqueue(MockResponse().setResponseCode(429))
        var caught: Exception? = null
        try { scanner.scan(bitmap, "test-key") } catch (e: Exception) { caught = e }
        assert(caught is RateLimitException) { "Expected RateLimitException but got $caught" }
    }

    @Test
    fun `scan throws IOException on 500`() = runTest {
        server.enqueue(MockResponse().setResponseCode(500))
        var caught: Exception? = null
        try { scanner.scan(bitmap, "test-key") } catch (e: Exception) { caught = e }
        assert(caught is java.io.IOException) { "Expected IOException but got $caught" }
    }
}
