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
import java.net.SocketTimeoutException

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

    private fun provider(geminiKey: String = GEMINI_SECRET, claudeKey: String = CLAUDE_SECRET): CardScanProvider {
        coEvery { settings.getGeminiApiKey() } returns geminiKey
        coEvery { settings.getAnthropicApiKey() } returns claudeKey
        return CardScanProvider(gemini, claude, settings).also { it.log = { line -> logs += line } }
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
    fun `claude cancellation is rethrown`() = runTest {
        coEvery { gemini.scan(any(), any()) } throws IOException("fail")
        coEvery { claude.scan(any(), any()) } throws CancellationException("cancelled")

        try {
            provider().scan(bitmap)
            fail("expected CancellationException")
        } catch (e: CancellationException) {
        }
    }
}
