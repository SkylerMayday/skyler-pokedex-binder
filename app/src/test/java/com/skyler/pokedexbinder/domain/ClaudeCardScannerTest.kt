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
import okhttp3.mockwebserver.SocketPolicy
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.IOException
import java.util.concurrent.TimeUnit

private const val CLAUDE_CARD_JSON =
    """{\"name\":\"Pikachu\",\"number\":\"25\",\"setTotal\":\"185\",\"hp\":\"60\",\"artist\":\"Atsuko Nishida\"}"""

private const val CLAUDE_SUCCESS_BODY =
    """{"content":[{"type":"text","text":"$CLAUDE_CARD_JSON"}]}"""

class ClaudeCardScannerTest {

    private lateinit var server: MockWebServer
    private lateinit var scanner: ClaudeCardScanner
    private val moshi = Moshi.Builder().addLast(KotlinJsonAdapterFactory()).build()
    private val bitmap: Bitmap = mockk {
        every { width } returns 100
        every { height } returns 100
        every { compress(any(), any(), any()) } answers {
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
        // org.json is stubbed under plain JVM tests; this escape also covers newlines (the shared
        // prompt is multi-line) so the request body stays valid JSON and can be parsed with Moshi.
        mockkStatic(JSONObject::class)
        every { JSONObject.quote(any()) } answers {
            "\"" + firstArg<String>()
                .replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n") + "\""
        }
        server = MockWebServer()
        server.start()
        scanner = ClaudeCardScanner(OkHttpClient(), moshi).also { it.baseUrl = server.url("/").toString(); it.log = {} }
    }

    @After
    fun tearDown() = server.shutdown()

    private fun scannerWithShortTimeout() {
        val client = OkHttpClient.Builder().readTimeout(200, TimeUnit.MILLISECONDS).build()
        scanner = ClaudeCardScanner(client, moshi).also { it.baseUrl = server.url("/").toString(); it.log = {} }
    }

    private suspend fun scanCatching(): Exception? =
        try { scanner.scan(bitmap, "test-key"); null } catch (e: Exception) { e }

    @Test
    fun `scan returns ParsedCardInfo on success`() = runTest {
        server.enqueue(MockResponse().setBody(CLAUDE_SUCCESS_BODY))

        val result = scanner.scan(bitmap, "test-key")

        assertEquals("Pikachu", result.cardName)
        assertEquals("25", result.cardNumber)
        assertEquals("185", result.setTotal)
        assertEquals("60", result.hp)
        assertEquals("Atsuko Nishida", result.artist)
    }

    @Test
    fun `scan lowercases language and keeps printedName`() = runTest {
        server.enqueue(MockResponse().setBody(
            """{"content":[{"type":"text","text":"{\"name\":\"Malamar\",\"language\":\" JA \",\"printedName\":\"メガカラマネロex\"}"}]}"""
        ))

        val result = scanner.scan(bitmap, "test-key")

        assertEquals("ja", result.language)
        assertEquals("メガカラマネロex", result.printedName)
    }

    @Test
    fun `scan sends the Anthropic request shape`() = runTest {
        server.enqueue(MockResponse().setBody(CLAUDE_SUCCESS_BODY))

        scanner.scan(bitmap, "test-key")

        val request = server.takeRequest()
        assertEquals("test-key", request.getHeader("x-api-key"))
        assertEquals("2023-06-01", request.getHeader("anthropic-version"))
        assertTrue(request.getHeader("content-type")!!.startsWith("application/json"))
        assertNull(request.getHeader("Authorization"))
        assertFalse(request.path!!.contains("key="))

        val bodyText = request.body.readUtf8()
        for (banned in listOf("\"temperature\"", "\"top_p\"", "\"top_k\"")) {
            assertFalse("body must not contain $banned", bodyText.contains(banned))
        }
        @Suppress("UNCHECKED_CAST")
        val body = moshi.adapter(Any::class.java).fromJson(bodyText) as Map<String, Any?>
        assertEquals(CLAUDE_MODEL, body["model"])
        assertNotNull(body["max_tokens"])
        @Suppress("UNCHECKED_CAST")
        val messages = body["messages"] as List<Map<String, Any?>>
        @Suppress("UNCHECKED_CAST")
        val content = messages[0]["content"] as List<Map<String, Any?>>
        assertEquals("image", content[0]["type"])
        @Suppress("UNCHECKED_CAST")
        val source = content[0]["source"] as Map<String, Any?>
        assertEquals("base64", source["type"])
        assertEquals("image/jpeg", source["media_type"])
        assertEquals("text", content[1]["type"])
        assertEquals(CARD_SCAN_PROMPT, content[1]["text"])
    }

    @Test
    fun `scan parses JSON wrapped in a markdown fence`() = runTest {
        server.enqueue(MockResponse().setBody(
            """{"content":[{"type":"text","text":"```json\n$CLAUDE_CARD_JSON\n```"}]}"""
        ))

        assertEquals("Pikachu", scanner.scan(bitmap, "test-key").cardName)
    }

    @Test
    fun `scan parses JSON surrounded by prose`() = runTest {
        server.enqueue(MockResponse().setBody(
            """{"content":[{"type":"text","text":"Here you go: $CLAUDE_CARD_JSON Hope that helps."}]}"""
        ))

        assertEquals("Pikachu", scanner.scan(bitmap, "test-key").cardName)
    }

    @Test
    fun `extractJsonObject slices first brace to last brace`() {
        assertEquals("{\"a\":1}", extractJsonObject("```json\n{\"a\":1}\n```"))
        assertEquals("{\"a\":{\"b\":2}}", extractJsonObject("x {\"a\":{\"b\":2}} y"))
        assertEquals("no braces here", extractJsonObject("no braces here"))
        assertEquals("} {", extractJsonObject("} {"))
    }

    @Test
    fun `scan throws RateLimitException naming Claude on 429 without retry`() = runTest {
        server.enqueue(MockResponse().setResponseCode(429))

        val caught = scanCatching()

        assertTrue("Expected RateLimitException but got $caught", caught is RateLimitException)
        assertEquals(ScanProvider.CLAUDE, (caught as RateLimitException).provider)
        assertTrue(caught!!.message!!.contains("Claude"))
        assertEquals(1, server.requestCount)
    }

    @Test
    fun `scan throws InvalidApiKeyException on 401 and 403 without retry or key in message`() = runTest {
        for (code in listOf(401, 403)) {
            val before = server.requestCount
            server.enqueue(MockResponse().setResponseCode(code))

            val caught = scanCatching()

            assertTrue("Expected InvalidApiKeyException but got $caught", caught is InvalidApiKeyException)
            assertFalse(caught!!.message!!.contains("test-key"))
            assertTrue(caught.message!!.contains("$code"))
            assertEquals(1, server.requestCount - before)
        }
    }

    @Test
    fun `scan throws IOException after 500 retries exhausted`() = runTest {
        repeat(4) { server.enqueue(MockResponse().setResponseCode(500)) }

        val caught = scanCatching()

        assertTrue("Expected IOException but got $caught", caught is IOException)
        assertEquals("Claude error: 500", caught!!.message)
        assertEquals(4, server.requestCount)
    }

    @Test
    fun `scan retries on 529 then succeeds`() = runTest {
        server.enqueue(MockResponse().setResponseCode(529))
        server.enqueue(MockResponse().setBody(CLAUDE_SUCCESS_BODY))

        assertEquals("Pikachu", scanner.scan(bitmap, "test-key").cardName)
        assertEquals(2, server.requestCount)
    }

    @Test
    fun `scan throws immediately on 400 with no retry`() = runTest {
        server.enqueue(MockResponse().setResponseCode(400))

        val caught = scanCatching()

        assertTrue("Expected IOException but got $caught", caught is IOException)
        assertEquals(1, server.requestCount)
    }

    @Test
    fun `scan retries once on timeout then succeeds`() = runTest {
        scannerWithShortTimeout()
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
        server.enqueue(MockResponse().setBody(CLAUDE_SUCCESS_BODY))

        assertEquals("Pikachu", scanner.scan(bitmap, "test-key").cardName)
        assertEquals(2, server.requestCount)
    }

    @Test
    fun `scan rethrows original timeout after retries exhausted`() = runTest {
        scannerWithShortTimeout()
        repeat(4) { server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE)) }

        val caught = scanCatching()

        assertTrue("Expected SocketTimeoutException but got $caught", caught is java.net.SocketTimeoutException)
        assertEquals(4, server.requestCount)
    }

    @Test
    fun `scan throws MalformedReplyException when there is no text block`() = runTest {
        for (body in listOf("""{"content":[]}""", """{"content":[{"type":"tool_use"}]}""", """{}""", "")) {
            server.enqueue(MockResponse().setBody(body))
            val caught = scanCatching()
            assertTrue("body=[$body] got $caught", caught is MalformedReplyException)
        }
    }

    @Test
    fun `scan logs one attempt line per request and never the key`() = runTest {
        val logs = mutableListOf<String>()
        scanner.log = { logs += it }
        server.enqueue(MockResponse().setResponseCode(529))
        server.enqueue(MockResponse().setBody(CLAUDE_SUCCESS_BODY))

        scanner.scan(bitmap, "test-key")

        assertEquals(2, logs.size)
        assertTrue(logs[0], logs[0].startsWith("attempt provider=claude n=1 ms=") && logs[0].endsWith("outcome=http529"))
        assertTrue(logs[1], logs[1].startsWith("attempt provider=claude n=2 ms=") && logs[1].endsWith("outcome=ok"))
        assertTrue(logs.none { it.contains("test-key") })
    }

    @Test
    fun `scan 429 logs the redacted body snippet`() = runTest {
        val logs = mutableListOf<String>()
        scanner.log = { logs += it }
        server.enqueue(
            MockResponse().setResponseCode(429)
                .setBody("""{"type":"error","error":{"type":"rate_limit_error","message":"limit for key test-key"}}""")
        )

        assertTrue(scanCatching() is RateLimitException)

        val line = logs.single { it.startsWith("rateLimit provider=claude body=") }
        assertTrue(line, line.contains("rate_limit_error"))
        assertFalse(line, line.contains("test-key"))
    }

    @Test
    fun `scan returns all-null info for unparseable text`() = runTest {
        server.enqueue(MockResponse().setBody("""{"content":[{"type":"text","text":"not json at all"}]}"""))

        val result = scanner.scan(bitmap, "test-key")

        assertNull(result.cardName)
        assertNull(result.cardNumber)
        assertNull(result.language)
        assertNull(result.printedName)
    }
}
