package com.skyler.pokedexbinder.domain

import android.graphics.Bitmap
import com.skyler.pokedexbinder.repository.SettingsRepository
import kotlinx.coroutines.CancellationException
import java.io.IOException
import java.io.InterruptedIOException
import java.net.ConnectException
import java.net.UnknownHostException
import javax.inject.Inject
import javax.inject.Singleton

enum class ScanProvider(val displayName: String) { GEMINI("Gemini"), CLAUDE("Claude") }

private const val GEMINI_COOLDOWN_MS = 10L * 60 * 1000

/** Our scanners throw `IOException("<provider> error: <code>")` for non-retryable HTTP errors. */
private val HTTP_ERROR = Regex(".*error: \\d{3}.*")

private fun isNetworkFailure(t: Throwable): Boolean =
    t is UnknownHostException || t is ConnectException || t is InterruptedIOException ||
        (t is IOException && t !is RateLimitException && t !is InvalidApiKeyException &&
            t !is MalformedReplyException && !HTTP_ERROR.matches(t.message.orEmpty()))

/** Single-provider failure text; fixed strings only, never the exception message. */
private fun singleProviderMessage(provider: ScanProvider, e: Exception): String =
    if (isNetworkFailure(e)) "Can't reach ${provider.displayName} — check your internet connection"
    else "${provider.displayName} failed — try again"

/** Fixed strings only: never the exception message, which can carry hostnames. */
private fun failureMessage(gemini: Exception?, geminiCooling: Boolean, claude: Exception): String {
    val claudeNetwork = isNetworkFailure(claude)
    val geminiPart = when {
        geminiCooling || gemini is RateLimitException -> "Gemini unavailable (quota)"
        gemini == null -> null
        gemini is InvalidApiKeyException -> "Gemini key rejected"
        isNetworkFailure(gemini) -> "Gemini unreachable"
        else -> "Gemini failed"
    }
    val advice = if (claudeNetwork) "check your internet connection" else "try again"
    return if (geminiPart != null) {
        "$geminiPart and ${if (claudeNetwork) "Claude unreachable" else "Claude failed"} — $advice"
    } else {
        singleProviderMessage(ScanProvider.CLAUDE, claude)
    }
}

/** Gemini first, then Claude; a blank key means that provider is absent. */
internal fun selectProviders(geminiKey: String, claudeKey: String): List<ScanProvider> = buildList {
    if (geminiKey.isNotBlank()) add(ScanProvider.GEMINI)
    if (claudeKey.isNotBlank()) add(ScanProvider.CLAUDE)
}

/**
 * Tries Gemini first; on any non-cancellation failure, or an all-null reply, resends the bitmap to
 * Claude (when a Claude key is set). After a Gemini 429, Gemini is skipped for 10 min. Logs carry
 * provider and exception class names, per-attempt timings, and a short key-redacted snippet of a
 * 429 body — never keys, URLs, or exception messages.
 */
@Singleton
class CardScanProvider @Inject constructor(
    private val gemini: GeminiCardScanner,
    private val claude: ClaudeCardScanner,
    private val settings: SettingsRepository
) {
    /** Test seam: Log is unavailable in JVM unit tests. */
    internal var log: (String) -> Unit = { android.util.Log.i("ScannerMatch", it) }
    internal var nowMs: () -> Long = System::currentTimeMillis

    // ponytail: in-memory, per process; persist/back off by Retry-After only if quota resets turn out to matter
    @Volatile private var geminiSkipUntil = 0L

    suspend fun scan(bitmap: Bitmap): ParsedCardInfo {
        val geminiKey = settings.getGeminiApiKey().trim()
        val claudeKey = settings.getAnthropicApiKey().trim()
        val providers = selectProviders(geminiKey, claudeKey)
        if (providers.isEmpty()) throw NoApiKeyException()

        val hasGemini = ScanProvider.GEMINI in providers
        val hasClaude = ScanProvider.CLAUDE in providers
        var geminiError: Exception? = null
        var geminiEmpty: ParsedCardInfo? = null
        // The breaker only applies when Claude can take over; with Gemini alone keep trying.
        val geminiCooling = hasGemini && hasClaude && nowMs() < geminiSkipUntil
        if (hasGemini && !geminiCooling) {
            try {
                val result = gemini.scan(bitmap, geminiKey)
                if (!result.isEmpty || !hasClaude) return result.also { log("scan provider=gemini") }
                geminiEmpty = result
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (e is RateLimitException) geminiSkipUntil = nowMs() + GEMINI_COOLDOWN_MS
                if (!hasClaude) {
                    if (e is RateLimitException || e is InvalidApiKeyException) throw e
                    throw ScanFailedException(singleProviderMessage(ScanProvider.GEMINI, e), e)
                }
                geminiError = e
            }
        }

        val fallbackReason = when {
            geminiCooling -> "GeminiCooldown"
            geminiError != null -> geminiError::class.java.simpleName
            geminiEmpty != null -> "EmptyReply"
            else -> "none"
        }
        try {
            return claude.scan(bitmap, claudeKey).also {
                log("scan provider=claude fallbackReason=$fallbackReason")
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            geminiError?.let { e.addSuppressed(it) }
            val claudeError = e::class.java.simpleName
            if (e is RateLimitException || e is InvalidApiKeyException) {
                log("scan provider=none geminiError=$fallbackReason claudeError=$claudeError")
                throw e
            }
            geminiEmpty?.let {
                log("scan provider=gemini fallbackReason=$fallbackReason claudeError=$claudeError")
                return it
            }
            log("scan provider=none geminiError=$fallbackReason claudeError=$claudeError")
            throw ScanFailedException(failureMessage(geminiError, geminiCooling, e), e)
        }
    }
}
