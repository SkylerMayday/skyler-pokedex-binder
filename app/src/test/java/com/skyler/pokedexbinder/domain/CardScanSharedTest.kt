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
            client(429).executeWithRetry(request, ScanProvider.GEMINI, log = {})
            fail("expected RateLimitException")
        } catch (e: RateLimitException) {
        }
        assertTrue(body.closed)
    }

    @Test
    fun `a non-retryable error status closes the response and throws IOException`() = runTest {
        try {
            client(404).executeWithRetry(request, ScanProvider.GEMINI, log = {})
            fail("expected IOException")
        } catch (e: IOException) {
            assertFalse(e is RateLimitException)
        }
        assertTrue(body.closed)
    }

    @Test
    fun `an invalid-key status closes the response and throws InvalidApiKeyException`() = runTest {
        try {
            client(401).executeWithRetry(request, ScanProvider.CLAUDE, invalidKeyCodes = setOf(401), log = {})
            fail("expected InvalidApiKeyException")
        } catch (e: InvalidApiKeyException) {
        }
        assertTrue(body.closed)
    }

    @Test
    fun `a 200 is returned open for the caller to read`() = runTest {
        val response = client(200).executeWithRetry(request, ScanProvider.GEMINI, log = {})

        assertSame(body, response.body)
        assertFalse(body.closed)
    }

    // Mutation (attempt log): dropping the ok-path logAttempt makes this red.
    @Test
    fun `each attempt logs one line with provider, n, ms and outcome`() = runTest {
        val logs = mutableListOf<String>()
        client(200).executeWithRetry(request, ScanProvider.CLAUDE, log = { logs += it }).close()

        assertEquals(1, logs.size)
        assertTrue(logs[0], Regex("""attempt provider=claude n=1 ms=\d+ outcome=ok""").matches(logs[0]))
    }

    @Test
    fun `retried attempts are numbered and a non-ok outcome carries the status`() = runTest {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(503))
            server.enqueue(MockResponse().setBody("ok"))
            val logs = mutableListOf<String>()

            OkHttpClient().executeWithRetry(
                Request.Builder().url(server.url("/")).build(), ScanProvider.GEMINI, log = { logs += it }
            ).close()

            assertEquals(2, logs.size)
            assertTrue(logs[0], logs[0].contains("n=1") && logs[0].endsWith("outcome=http503"))
            assertTrue(logs[1], logs[1].contains("n=2") && logs[1].endsWith("outcome=ok"))
            assertTrue(logs.none { it.contains("localhost") || it.contains("127.0.0.1") })
        }
    }

    @Test
    fun `a timeout and an invalid key are logged as outcomes`() = runTest {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
            server.enqueue(MockResponse().setResponseCode(403))
            val logs = mutableListOf<String>()

            try {
                OkHttpClient().forScan(200).executeWithRetry(
                    Request.Builder().url(server.url("/")).build(), ScanProvider.GEMINI, setOf(403), { logs += it }
                )
                fail("expected InvalidApiKeyException")
            } catch (e: InvalidApiKeyException) {
            }

            assertTrue(logs[0], logs[0].endsWith("outcome=timeout"))
            assertTrue(logs[1], logs[1].endsWith("outcome=invalidKey403"))
        }
    }

    @Test
    fun `a non-timeout IOException from execute is logged by class and rethrown unchanged`() = runTest {
        val boom = java.net.UnknownHostException("secret.example.com")
        val call = mockk<Call> { every { execute() } throws boom }
        val client = mockk<OkHttpClient> { every { newCall(any()) } returns call }
        val logs = mutableListOf<String>()

        try {
            client.executeWithRetry(request, ScanProvider.GEMINI, log = { logs += it })
            fail("expected UnknownHostException")
        } catch (e: java.net.UnknownHostException) {
            assertSame(boom, e)
        }

        assertEquals(1, logs.size)
        assertTrue(logs[0], logs[0].endsWith("outcome=io:UnknownHostException"))
        assertFalse(logs[0].contains("secret.example.com"))
    }

    // Mutations (429 log): removing the redaction `replace` turns the key assert red; removing the
    // log call turns the "body present" assert red.
    @Test
    fun `429 logs a redacted capped body snippet and still throws RateLimitException`() = runTest {
        MockWebServer().use { server ->
            val filler = " filler".repeat(200)
            server.enqueue(
                MockResponse().setResponseCode(429).setBody(
                    "{\"error\":{\"status\":\"RESOURCE_EXHAUSTED\",\"message\":\"PerDay quota for key test-key\"}}$filler"
                )
            )
            val logs = mutableListOf<String>()
            val req = Request.Builder().url(server.url("/")).header("x-goog-api-key", "test-key").build()

            try {
                OkHttpClient().executeWithRetry(req, ScanProvider.GEMINI, log = { logs += it })
                fail("expected RateLimitException")
            } catch (e: RateLimitException) {
            }

            val line = logs.single { it.startsWith("rateLimit provider=gemini body=") }
            assertTrue(line, line.contains("RESOURCE_EXHAUSTED") && line.contains("PerDay"))
            assertFalse(line, line.contains("test-key"))
            assertTrue(line, line.contains("<redacted>"))
            assertTrue(line.length <= "rateLimit provider=gemini body=".length + 300)
        }
    }

    @Test
    fun `a body that cannot be peeked does not change the thrown RateLimitException`() = runTest {
        val exploding = object : ResponseBody() {
            override fun contentType(): MediaType? = null
            override fun contentLength() = -1L
            override fun source(): BufferedSource = throw IOException("no body")
            override fun close() {} // the default close() reads source() and would throw too
        }
        val response = Response.Builder()
            .request(request).protocol(Protocol.HTTP_1_1).code(429).message("m").body(exploding).build()
        val call = mockk<Call> { every { execute() } returns response }
        val client = mockk<OkHttpClient> { every { newCall(any()) } returns call }
        val logs = mutableListOf<String>()

        try {
            client.executeWithRetry(request, ScanProvider.CLAUDE, log = { logs += it })
            fail("expected RateLimitException")
        } catch (e: RateLimitException) {
            assertEquals(ScanProvider.CLAUDE, e.provider)
        }
        assertTrue(logs.none { it.startsWith("rateLimit") })
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

            val response = scan.executeWithRetry(Request.Builder().url(server.url("/")).build(), ScanProvider.GEMINI, log = {})

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
                scan.executeWithRetry(Request.Builder().url(server.url("/")).build(), ScanProvider.GEMINI, log = {})
            } catch (e: IOException) {
                caught = e
            }

            assertTrue("got $caught", caught is InterruptedIOException)
            assertTrue(caught !is SocketTimeoutException)
            assertEquals(4, server.requestCount)
        }
    }
}
