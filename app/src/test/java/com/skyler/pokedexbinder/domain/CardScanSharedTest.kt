package com.skyler.pokedexbinder.domain

import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import okhttp3.Call
import okhttp3.MediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody
import okio.Buffer
import okio.BufferedSource
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.IOException

class CardScanSharedTest {

    private class TrackingBody : ResponseBody() {
        var closed = false
        override fun contentType(): MediaType? = null
        override fun contentLength() = 0L
        override fun source(): BufferedSource = Buffer()
        override fun close() {
            closed = true
        }
    }

    private val request = Request.Builder().url("https://example.com/").build()
    private val body = TrackingBody()

    private fun client(code: Int): OkHttpClient {
        val response = Response.Builder()
            .request(request).protocol(Protocol.HTTP_1_1).code(code).message("m").body(body).build()
        val call = mockk<Call> { every { execute() } returns response }
        return mockk { every { newCall(any()) } returns call }
    }

    @Test
    fun `429 closes the response and throws RateLimitException`() = runTest {
        try {
            client(429).executeWithRetry(request, "Gemini")
            fail("expected RateLimitException")
        } catch (e: RateLimitException) {
        }
        assertTrue(body.closed)
    }

    @Test
    fun `a non-retryable error status closes the response and throws IOException`() = runTest {
        try {
            client(404).executeWithRetry(request, "Gemini")
            fail("expected IOException")
        } catch (e: IOException) {
            assertFalse(e is RateLimitException)
        }
        assertTrue(body.closed)
    }

    @Test
    fun `an invalid-key status closes the response and throws InvalidApiKeyException`() = runTest {
        try {
            client(401).executeWithRetry(request, "Claude", invalidKeyCodes = setOf(401))
            fail("expected InvalidApiKeyException")
        } catch (e: InvalidApiKeyException) {
        }
        assertTrue(body.closed)
    }

    @Test
    fun `a 200 is returned open for the caller to read`() = runTest {
        val response = client(200).executeWithRetry(request, "Gemini")

        assertSame(body, response.body)
        assertFalse(body.closed)
    }
}
