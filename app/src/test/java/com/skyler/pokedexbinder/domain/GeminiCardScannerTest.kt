package com.skyler.pokedexbinder.domain

import android.graphics.Bitmap
import android.util.Base64
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

class GeminiCardScannerTest {

    private lateinit var server: MockWebServer
    private lateinit var scanner: GeminiCardScanner
    private val bitmap: Bitmap = mockk {
        // scaleBitmap() reads width/height before compress(); keep both under its
        // maxDim=1024 threshold so it returns this same mock rather than calling the
        // static Bitmap.createScaledBitmap (which would need its own mockkStatic setup).
        every { width } returns 100
        every { height } returns 100
        every { compress(any(), any(), any()) } answers {
            // Write dummy bytes so Base64 encoding has something to work with
            thirdArg<java.io.OutputStream>().write(byteArrayOf(0, 1, 2, 3))
            true
        }
    }

    @Before
    fun setUp() {
        mockkStatic(Base64::class)
        every { Base64.encodeToString(any(), any()) } answers {
            java.util.Base64.getEncoder().encodeToString(firstArg())
        }
        // org.json.JSONObject is also stubbed-not-mocked under plain JVM unit tests (the fake
        // android.jar shadows the real org.json impl). Request-body content isn't asserted by
        // any test here — MockWebServer returns canned responses regardless — so a
        // JSON-spec-compatible-enough escape is sufficient to unblock the call.
        mockkStatic(JSONObject::class)
        every { JSONObject.quote(any()) } answers {
            "\"" + firstArg<String>().replace("\\", "\\\\").replace("\"", "\\\"") + "\""
        }
        server = MockWebServer()
        server.start()
        val moshi = Moshi.Builder().addLast(KotlinJsonAdapterFactory()).build()
        scanner = GeminiCardScanner(OkHttpClient(), moshi).also { it.baseUrl = server.url("/").toString() }
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
