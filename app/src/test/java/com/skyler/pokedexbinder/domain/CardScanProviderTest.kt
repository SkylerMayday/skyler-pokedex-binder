package com.skyler.pokedexbinder.domain

import android.graphics.Bitmap
import com.skyler.pokedexbinder.repository.SettingsRepository
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.IOException
import java.io.InterruptedIOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException

private const val GEMINI_SECRET = "SECRET-G"
private const val CLAUDE_SECRET = "SECRET-C"

class CardScanProviderTest {

    private fun parsed(name: String) = ParsedCardInfo(name, null, null, null, null)

    private val gemini = mockk<GeminiCardScanner>()
    private val claude = mockk<ClaudeCardScanner>()
    private val settings = mockk<SettingsRepository>()
    private val bitmap = mockk<Bitmap>()
    private val logs = mutableListOf<String>()
    private val geminiResult = parsed("FromGemini")
    private val claudeResult = parsed("FromClaude")
    private val emptyResult = ParsedCardInfo(null, null, null, null, null)
    private var now = 1_000_000L
    private val minute = 60_000L

    private fun provider(geminiKey: String = GEMINI_SECRET, claudeKey: String = CLAUDE_SECRET): CardScanProvider {
        coEvery { settings.getGeminiApiKey() } returns geminiKey
        coEvery { settings.getAnthropicApiKey() } returns claudeKey
        return CardScanProvider(gemini, claude, settings).also { it.log = { line -> logs += line }; it.nowMs = { now } }
    }

    private fun assertNoKeysLogged() {
        for (line in logs) {
            assertFalse(line, line.contains(GEMINI_SECRET))
            assertFalse(line, line.contains(CLAUDE_SECRET))
        }
    }

    @Test
    fun `selectProviders orders gemini then claude and treats blank as absent`() {
        assertEquals(listOf(ScanProvider.GEMINI, ScanProvider.CLAUDE), selectProviders("g", "c"))
        assertEquals(listOf(ScanProvider.GEMINI), selectProviders("g", ""))
        assertEquals(listOf(ScanProvider.CLAUDE), selectProviders("", "c"))
        assertEquals(emptyList<ScanProvider>(), selectProviders("", ""))
        assertEquals(emptyList<ScanProvider>(), selectProviders("  ", "\t"))
    }

    @Test
    fun `gemini success never calls claude`() = runTest {
        coEvery { gemini.scan(bitmap, GEMINI_SECRET) } returns geminiResult

        val result = provider().scan(bitmap)

        assertSame(geminiResult, result)
        coVerify(exactly = 0) { claude.scan(any(), any()) }
        assertTrue(logs.any { it.contains("provider=gemini") })
        assertNoKeysLogged()
    }

    @Test
    fun `gemini rate limit falls back to claude`() = runTest {
        coEvery { gemini.scan(any(), any()) } throws RateLimitException()
        coEvery { claude.scan(bitmap, CLAUDE_SECRET) } returns claudeResult

        val result = provider().scan(bitmap)

        assertSame(claudeResult, result)
        assertTrue(logs.any { it.contains("provider=claude fallbackReason=RateLimitException") })
        assertNoKeysLogged()
    }

    @Test
    fun `any non-cancellation gemini failure falls back to claude`() = runTest {
        val failures = listOf(
            IOException("boom $GEMINI_SECRET"),
            SocketTimeoutException(),
            InvalidApiKeyException("Gemini", 403),
            RuntimeException("generic")
        )
        for (failure in failures) {
            logs.clear()
            coEvery { gemini.scan(any(), any()) } throws failure
            coEvery { claude.scan(any(), any()) } returns claudeResult

            assertSame(claudeResult, provider().scan(bitmap))
            assertTrue(logs.any { it.contains("fallbackReason=${failure::class.java.simpleName}") })
            assertNoKeysLogged()
        }
    }

    @Test
    fun `gemini failure without claude key rethrows the same exception`() = runTest {
        val failure = RateLimitException()
        coEvery { gemini.scan(any(), any()) } throws failure

        try {
            provider(claudeKey = "").scan(bitmap)
            fail("expected exception")
        } catch (e: RateLimitException) {
            assertSame(failure, e)
        }
        coVerify(exactly = 0) { claude.scan(any(), any()) }
    }

    @Test
    fun `claude-only key never calls gemini`() = runTest {
        coEvery { claude.scan(bitmap, CLAUDE_SECRET) } returns claudeResult

        val result = provider(geminiKey = "").scan(bitmap)

        assertSame(claudeResult, result)
        coVerify(exactly = 0) { gemini.scan(any(), any()) }
        assertTrue(logs.any { it.contains("provider=claude fallbackReason=none") })
        assertNoKeysLogged()
    }

    @Test
    fun `no keys throws NoApiKeyException`() = runTest {
        try {
            provider(geminiKey = " ", claudeKey = "").scan(bitmap)
            fail("expected NoApiKeyException")
        } catch (e: NoApiKeyException) {
        }
        coVerify(exactly = 0) { gemini.scan(any(), any()) }
        coVerify(exactly = 0) { claude.scan(any(), any()) }
    }

    @Test
    fun `both failing throws claude exception with gemini suppressed`() = runTest {
        val geminiFailure = IOException("gemini down")
        val claudeFailure = InvalidApiKeyException("Claude", 401)
        coEvery { gemini.scan(any(), any()) } throws geminiFailure
        coEvery { claude.scan(any(), any()) } throws claudeFailure

        try {
            provider().scan(bitmap)
            fail("expected exception")
        } catch (e: InvalidApiKeyException) {
            assertSame(claudeFailure, e)
            assertTrue(e.suppressed.contains(geminiFailure))
        }
        assertTrue(logs.any { it.contains("provider=none geminiError=IOException claudeError=InvalidApiKeyException") })
        assertNoKeysLogged()
    }

    @Test
    fun `both rate limited surfaces RateLimitException`() = runTest {
        coEvery { gemini.scan(any(), any()) } throws RateLimitException("Gemini")
        coEvery { claude.scan(any(), any()) } throws RateLimitException("Claude")

        try {
            provider().scan(bitmap)
            fail("expected RateLimitException")
        } catch (e: RateLimitException) {
            assertTrue(e.message!!.contains("Claude"))
        }
    }

    @Test
    fun `gemini cancellation is rethrown without calling claude`() = runTest {
        coEvery { gemini.scan(any(), any()) } throws CancellationException("cancelled")

        try {
            provider().scan(bitmap)
            fail("expected CancellationException")
        } catch (e: CancellationException) {
        }
        coVerify(exactly = 0) { claude.scan(any(), any()) }
    }

    @Test
    fun `all-null gemini reply falls back to claude`() = runTest {
        coEvery { gemini.scan(any(), any()) } returns emptyResult
        coEvery { claude.scan(any(), any()) } returns claudeResult

        assertSame(claudeResult, provider().scan(bitmap))
        assertTrue(logs.any { it.contains("provider=claude fallbackReason=EmptyScanException") })
        assertNoKeysLogged()
    }

    @Test
    fun `all-null gemini reply without claude key is returned as is`() = runTest {
        coEvery { gemini.scan(any(), any()) } returns emptyResult

        assertSame(emptyResult, provider(claudeKey = "").scan(bitmap))
        coVerify(exactly = 0) { claude.scan(any(), any()) }
    }

    @Test
    fun `a gemini reply with only a card number is not empty`() = runTest {
        val partial = ParsedCardInfo(null, "12", null, null, null)
        coEvery { gemini.scan(any(), any()) } returns partial

        assertSame(partial, provider().scan(bitmap))
        coVerify(exactly = 0) { claude.scan(any(), any()) }
    }

    @Test
    fun `gemini 429 skips gemini for 10 minutes`() = runTest {
        coEvery { gemini.scan(any(), any()) } throws RateLimitException()
        coEvery { claude.scan(any(), any()) } returns claudeResult
        val p = provider()

        p.scan(bitmap)
        now += 9 * minute
        assertSame(claudeResult, p.scan(bitmap))

        coVerify(exactly = 1) { gemini.scan(any(), any()) }
        coVerify(exactly = 2) { claude.scan(any(), any()) }
        assertTrue(logs.any { it.contains("provider=claude fallbackReason=GeminiCooldown") })
        assertNoKeysLogged()
    }

    @Test
    fun `gemini is tried again after the cooldown`() = runTest {
        coEvery { gemini.scan(any(), any()) } throws RateLimitException()
        coEvery { claude.scan(any(), any()) } returns claudeResult
        val p = provider()

        p.scan(bitmap)
        now += 11 * minute
        p.scan(bitmap)

        coVerify(exactly = 2) { gemini.scan(any(), any()) }
    }

    @Test
    fun `gemini 429 without claude key rethrows and does not skip the next scan`() = runTest {
        coEvery { gemini.scan(any(), any()) } throws RateLimitException()
        val p = provider(claudeKey = "")

        repeat(2) {
            try {
                p.scan(bitmap)
                fail("expected RateLimitException")
            } catch (e: RateLimitException) {
            }
        }

        coVerify(exactly = 2) { gemini.scan(any(), any()) }
    }

    @Test
    fun `a non-429 gemini failure does not start the cooldown`() = runTest {
        coEvery { gemini.scan(any(), any()) } throws IOException("boom")
        coEvery { claude.scan(any(), any()) } returns claudeResult
        val p = provider()

        p.scan(bitmap)
        p.scan(bitmap)

        coVerify(exactly = 2) { gemini.scan(any(), any()) }
    }

    @Test
    fun `claude cancellation is rethrown`() = runTest {
        coEvery { gemini.scan(any(), any()) } throws IOException("fail")
        coEvery { claude.scan(any(), any()) } throws CancellationException("cancelled")

        try {
            provider().scan(bitmap)
            fail("expected CancellationException")
        } catch (e: CancellationException) {
        }
    }

    private suspend fun scanFailure(p: CardScanProvider): ScanFailedException {
        try {
            p.scan(bitmap)
        } catch (e: ScanFailedException) {
            return e
        }
        fail("expected ScanFailedException")
        throw AssertionError()
    }

    @Test
    fun `empty gemini reply plus claude network failure returns the empty result`() = runTest {
        coEvery { gemini.scan(any(), any()) } returns emptyResult
        coEvery { claude.scan(any(), any()) } throws UnknownHostException("api.anthropic.com")

        assertSame(emptyResult, provider().scan(bitmap))
        assertNoKeysLogged()
    }

    @Test
    fun `gemini 429 plus claude DNS failure throws a friendly ScanFailedException`() = runTest {
        val rate = RateLimitException()
        val dns = UnknownHostException("api.anthropic.com")
        coEvery { gemini.scan(any(), any()) } throws rate
        coEvery { claude.scan(any(), any()) } throws dns

        val e = scanFailure(provider())

        assertEquals("Gemini unavailable (quota) and Claude unreachable — check your internet connection", e.message)
        assertFalse(e.message!!.contains("api.anthropic.com"))
        assertFalse(e.message!!.contains(GEMINI_SECRET))
        assertFalse(e.message!!.contains(CLAUDE_SECRET))
        assertSame(dns, e.cause)
        assertTrue(dns.suppressed.contains(rate))
        assertNoKeysLogged()
    }

    @Test
    fun `gemini cooling plus claude DNS failure names gemini quota`() = runTest {
        coEvery { gemini.scan(any(), any()) } throws RateLimitException()
        coEvery { claude.scan(any(), any()) } returns claudeResult andThenThrows UnknownHostException("x")
        val p = provider()
        p.scan(bitmap)

        val e = scanFailure(p)

        assertTrue(e.message!!, e.message!!.contains("Gemini unavailable (quota)") && e.message!!.contains("Claude"))
        coVerify(exactly = 1) { gemini.scan(any(), any()) }
    }

    @Test
    fun `claude-only network failure uses the can't reach message`() = runTest {
        coEvery { claude.scan(any(), any()) } throws UnknownHostException("h")

        assertEquals(
            "Can't reach Claude — check your internet connection",
            scanFailure(provider(geminiKey = "")).message
        )
    }

    @Test
    fun `claude failure kinds map to network or failed wording`() = runTest {
        val network = listOf(SocketTimeoutException(), ConnectException(), IOException("x"), IOException("Claude scan failed after retries"))
        for (f in network) {
            coEvery { claude.scan(any(), any()) } throws f
            val m = scanFailure(provider(geminiKey = "")).message!!
            assertTrue(m, m.contains("internet"))
        }
        coEvery { claude.scan(any(), any()) } throws IOException("Claude error: 500")
        val m = scanFailure(provider(geminiKey = "")).message!!
        assertEquals("Claude failed — try again", m)
    }

    @Test
    fun `call timeouts on both providers read as unreachable`() = runTest {
        coEvery { gemini.scan(any(), any()) } throws InterruptedIOException("timeout")
        coEvery { claude.scan(any(), any()) } throws InterruptedIOException("timeout")

        val m = scanFailure(provider()).message!!

        assertTrue(m, m.contains("Gemini unreachable"))
        assertTrue(m, m.contains("Claude unreachable"))
    }

    @Test
    fun `gemini failure kinds are labelled in the combined message`() = runTest {
        coEvery { claude.scan(any(), any()) } throws IOException("Claude error: 500")
        coEvery { gemini.scan(any(), any()) } throws UnknownHostException("h")
        assertEquals("Gemini unreachable and Claude failed — try again", scanFailure(provider()).message)

        coEvery { gemini.scan(any(), any()) } throws IOException("Gemini error: 500")
        assertEquals("Gemini failed and Claude failed — try again", scanFailure(provider()).message)
    }

    @Test
    fun `empty gemini reply plus claude rate limit or bad key propagates unwrapped`() = runTest {
        coEvery { gemini.scan(any(), any()) } returns emptyResult
        for (f in listOf<Exception>(RateLimitException("Claude"), InvalidApiKeyException("Claude", 401))) {
            coEvery { claude.scan(any(), any()) } throws f
            try {
                provider().scan(bitmap)
                fail("expected ${f::class.java.simpleName}")
            } catch (e: IOException) {
                assertSame(f, e)
            }
        }
    }

    @Test
    fun `gemini 429 plus claude rate limit still throws RateLimitException`() = runTest {
        coEvery { gemini.scan(any(), any()) } throws RateLimitException()
        coEvery { claude.scan(any(), any()) } throws RateLimitException("Claude")

        try {
            provider().scan(bitmap)
            fail("expected RateLimitException")
        } catch (e: RateLimitException) {
        }
    }
}
