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
import okhttp3.ResponseBody.Companion.asResponseBody
import okio.buffer
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.TimeUnit

private const val SUCCESS_BODY = """
    {"candidates":[{"content":{"parts":[{"text":"{\"name\":\"Pikachu\",\"number\":\"25\",\"setTotal\":\"185\",\"hp\":\"60\",\"artist\":\"Atsuko Nishida\"}"}]}}]}
"""

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
        scanner = GeminiCardScanner(OkHttpClient(), moshi).also { it.baseUrl = server.url("/").toString(); it.log = {} }
    }

    @After
    fun tearDown() = server.shutdown()

    @Test
    fun `scan returns ParsedCardInfo on success`() = runTest {
        server.enqueue(MockResponse().setBody(SUCCESS_BODY.trimIndent()))

        val result = scanner.scan(bitmap, "test-key")

        assertEquals("Pikachu", result.cardName)
        assertEquals("25", result.cardNumber)
        assertEquals("185", result.setTotal)
        assertEquals("60", result.hp)
        assertEquals("Atsuko Nishida", result.artist)
    }

    @Test
    fun `scan sends api key as header and never in the URL`() = runTest {
        server.enqueue(MockResponse().setBody(SUCCESS_BODY.trimIndent()))

        scanner.scan(bitmap, "test-key")

        val request = server.takeRequest()
        assertEquals("test-key", request.getHeader("x-goog-api-key"))
        assertTrue(!request.path!!.contains("key="))
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
        assertNull(result.language)
        assertNull(result.printedName)
    }

    @Test
    fun `scan lowercases language and keeps printedName`() = runTest {
        server.enqueue(MockResponse().setBody("""
            {"candidates":[{"content":{"parts":[{"text":"{\"name\":\"Malamar\",\"language\":\" JA \",\"printedName\":\"メガカラマネロex\"}"}]}}]}
        """.trimIndent()))

        val result = scanner.scan(bitmap, "test-key")

        assertEquals("ja", result.language)
        assertEquals("メガカラマネロex", result.printedName)
    }

    @Test
    fun `scan lowercases a zh-CN language to zh-cn`() = runTest {
        server.enqueue(MockResponse().setBody("""
            {"candidates":[{"content":{"parts":[{"text":"{\"name\":\"Palafin\",\"language\":\"zh-CN\"}"}]}}]}
        """.trimIndent()))

        val result = scanner.scan(bitmap, "test-key")

        assertEquals("zh-cn", result.language)
    }

    @Test
    fun `scan throws RateLimitException on 429`() = runTest {
        server.enqueue(MockResponse().setResponseCode(429))
        var caught: Exception? = null
        try { scanner.scan(bitmap, "test-key") } catch (e: Exception) { caught = e }
        assert(caught is RateLimitException) { "Expected RateLimitException but got $caught" }
        assertEquals(ScanProvider.GEMINI, (caught as RateLimitException).provider)
    }

    @Test
    fun `scan throws IOException on 500`() = runTest {
        // MAX_SCAN_RETRIES + 1 = 4 total attempts before the retry budget is exhausted.
        repeat(4) { server.enqueue(MockResponse().setResponseCode(500)) }
        var caught: Exception? = null
        try { scanner.scan(bitmap, "test-key") } catch (e: Exception) { caught = e }
        assert(caught is java.io.IOException) { "Expected IOException but got $caught" }
        assertEquals(4, server.requestCount)
    }

    @Test
    fun `scan throws immediately on 404 with no retry`() = runTest {
        server.enqueue(MockResponse().setResponseCode(404))
        var caught: Exception? = null
        try { scanner.scan(bitmap, "test-key") } catch (e: Exception) { caught = e }
        assert(caught is java.io.IOException) { "Expected IOException but got $caught" }
        assertEquals(1, server.requestCount)
    }

    @Test
    fun `scan retries once on 503 then succeeds`() = runTest {
        server.enqueue(MockResponse().setResponseCode(503))
        server.enqueue(MockResponse().setBody(SUCCESS_BODY.trimIndent()))

        val result = scanner.scan(bitmap, "test-key")

        assertEquals("Pikachu", result.cardName)
        assertEquals(2, server.requestCount)
    }

    @Test
    fun `scan retries once on timeout then succeeds`() = runTest {
        val shortTimeoutClient = OkHttpClient.Builder()
            .readTimeout(200, TimeUnit.MILLISECONDS)
            .build()
        scanner = GeminiCardScanner(shortTimeoutClient, Moshi.Builder().addLast(KotlinJsonAdapterFactory()).build())
            .also { it.baseUrl = server.url("/").toString(); it.log = {} }
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
        server.enqueue(MockResponse().setBody(SUCCESS_BODY.trimIndent()))

        val result = scanner.scan(bitmap, "test-key")

        assertEquals("Pikachu", result.cardName)
        assertEquals(2, server.requestCount)
    }

    @Test
    fun `scan rethrows original timeout after retries exhausted`() = runTest {
        val shortTimeoutClient = OkHttpClient.Builder()
            .readTimeout(200, TimeUnit.MILLISECONDS)
            .build()
        scanner = GeminiCardScanner(shortTimeoutClient, Moshi.Builder().addLast(KotlinJsonAdapterFactory()).build())
            .also { it.baseUrl = server.url("/").toString(); it.log = {} }
        repeat(4) { server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE)) }

        var caught: Exception? = null
        try { scanner.scan(bitmap, "test-key") } catch (e: Exception) { caught = e }

        assertTrue(
            "Expected SocketTimeoutException but got $caught",
            caught is java.net.SocketTimeoutException
        )
        assertEquals(4, server.requestCount)
    }

    @Test
    fun `scan throws InvalidApiKeyException on 401 and 403 after exactly one request`() = runTest {
        for (code in listOf(401, 403)) {
            val before = server.requestCount
            server.enqueue(MockResponse().setResponseCode(code))
            var caught: Exception? = null
            try { scanner.scan(bitmap, "test-key") } catch (e: Exception) { caught = e }
            assertTrue("Expected InvalidApiKeyException but got $caught", caught is InvalidApiKeyException)
            assertTrue(caught!!.message!!.contains("$code") && !caught.message!!.contains("test-key"))
            assertEquals(1, server.requestCount - before)
        }
    }

    @Test
    fun `scan throws MalformedReplyException for a 200 without usable structure`() = runTest {
        for (body in listOf("", "{}", """{"candidates":[]}""", """{"candidates":[{"content":{"parts":[]}}]}""")) {
            server.enqueue(MockResponse().setBody(body))
            var caught: Exception? = null
            try { scanner.scan(bitmap, "test-key") } catch (e: Exception) { caught = e }
            assertTrue("body=[$body] got $caught", caught is MalformedReplyException)
        }
    }

    @Test
    fun `scan logs one attempt line per request and never the key`() = runTest {
        val logs = mutableListOf<String>()
        scanner.log = { logs += it }
        server.enqueue(MockResponse().setResponseCode(503))
        server.enqueue(MockResponse().setBody(SUCCESS_BODY.trimIndent()))

        scanner.scan(bitmap, "test-key")

        assertEquals(2, logs.size)
        assertTrue(logs[0], logs[0].startsWith("attempt provider=gemini n=1 ms=") && logs[0].endsWith("outcome=http503"))
        assertTrue(logs[1], logs[1].startsWith("attempt provider=gemini n=2 ms=") && logs[1].endsWith("outcome=ok"))
        assertTrue(logs.none { it.contains("test-key") })
    }

    @Test
    fun `scan 429 logs the redacted body snippet`() = runTest {
        val logs = mutableListOf<String>()
        scanner.log = { logs += it }
        server.enqueue(
            MockResponse().setResponseCode(429)
                .setBody("""{"error":{"status":"RESOURCE_EXHAUSTED","message":"PerDay limit, key test-key"}}""")
        )

        try { scanner.scan(bitmap, "test-key") } catch (e: RateLimitException) { }

        val line = logs.single { it.startsWith("rateLimit provider=gemini body=") }
        assertTrue(line, line.contains("RESOURCE_EXHAUSTED") && line.contains("PerDay"))
        assertTrue(line, !line.contains("test-key"))
    }

    // Guards the response body being read through a closing path. Caveat: ResponseBody.string() itself
    // closes the source, so deleting the `.use` in GeminiCardScanner alone stays green (it is redundant
    // belt-and-braces). Mutation: replacing the read with `body?.byteStream()?.readBytes()?.decodeToString()`
    // (no close) turns `closed` false -> red.
    @Test
    fun `scan closes the response body source after a successful read`() = runTest {
        var closed = false
        val tracking = OkHttpClient.Builder().addInterceptor { chain ->
            val response = chain.proceed(chain.request())
            val original = response.body!!
            val forwarding = object : okio.ForwardingSource(original.source()) {
                override fun close() {
                    closed = true
                    super.close()
                }
            }
            response.newBuilder()
                .body(forwarding.buffer().asResponseBody(original.contentType(), original.contentLength()))
                .build()
        }.build()
        scanner = GeminiCardScanner(tracking, Moshi.Builder().addLast(KotlinJsonAdapterFactory()).build())
            .also { it.baseUrl = server.url("/").toString(); it.log = {} }
        server.enqueue(MockResponse().setBody(SUCCESS_BODY.trimIndent()))

        scanner.scan(bitmap, "test-key")

        assertTrue("response body source was not closed", closed)
    }
}
