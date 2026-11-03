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
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import okio.Buffer
import okio.BufferedSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.IOException
import java.io.InterruptedIOException
import java.net.SocketTimeoutException

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

    @Test
    fun `forScan sets the call timeout and leaves the base client unchanged`() {
        val base = OkHttpClient()
        val scan = base.forScan()

        assertEquals(10_000L, SCAN_CALL_TIMEOUT_MS)
        assertEquals(SCAN_CALL_TIMEOUT_MS, scan.callTimeoutMillis.toLong())
        assertEquals(0, base.callTimeoutMillis)
    }

    @Test
    fun `call timeout is retried then succeeds`() = runTest {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
            server.enqueue(MockResponse().setBody("ok"))
            val scan = OkHttpClient().forScan(200)

            val response = scan.executeWithRetry(Request.Builder().url(server.url("/")).build(), "Gemini")

            assertEquals(200, response.code)
            response.close()
            assertEquals(2, server.requestCount)
        }
    }

    @Test
    fun `call timeout after retries exhausted throws InterruptedIOException`() = runTest {
        MockWebServer().use { server ->
            repeat(4) { server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE)) }
            val scan = OkHttpClient().forScan(200)

            var caught: Throwable? = null
            try {
                scan.executeWithRetry(Request.Builder().url(server.url("/")).build(), "Gemini")
            } catch (e: IOException) {
                caught = e
            }

            assertTrue("got $caught", caught is InterruptedIOException)
            assertTrue(caught !is SocketTimeoutException)
            assertEquals(4, server.requestCount)
        }
    }
}
