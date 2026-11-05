package com.skyler.pokedexbinder.domain

import android.graphics.Bitmap
import android.util.Base64
import com.squareup.moshi.JsonAdapter
import com.squareup.moshi.JsonDataException
import kotlinx.coroutines.delay
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InterruptedIOException
import java.util.concurrent.TimeUnit

/** Messages here must never contain an API key or request URL/headers. */
class RateLimitException(val provider: ScanProvider = ScanProvider.GEMINI) :
    IOException("Rate limited by ${provider.displayName} API")

class InvalidApiKeyException(provider: String, code: Int) :
    IOException("$provider rejected the API key (HTTP $code)")

/** The provider answered 2xx but the body had no usable structure; not a connectivity problem. */
class MalformedReplyException(provider: ScanProvider) :
    IOException("${provider.displayName} returned an unreadable reply")

/** Every scan provider failed; [message] is user-safe (fixed strings only), the real error is [cause]. */
class ScanFailedException(message: String, cause: Throwable) : IOException(message, cause)

class NoApiKeyException : IllegalStateException("No scanner API key configured")

/** Parses a provider reply envelope; unparseable or empty JSON is a [MalformedReplyException], not a network error. */
internal fun <T : Any> JsonAdapter<T>.fromReply(raw: String, provider: ScanProvider): T =
    try {
        fromJson(raw)
    } catch (e: IOException) {
        null
    } catch (e: JsonDataException) {
        null
    } ?: throw MalformedReplyException(provider)

// Bumped from 2 (500ms fixed) after live sessions saw 11/12 requests 503 in one sitting (2026-09-22)
// and it recurred 2026-09-24 — a fixed 500ms/1.5s total budget doesn't outlast a real overload
// burst. 3 extra attempts (4 total) with exponential backoff gives ~3.5s worst-case extra wait,
// acceptable for a manual scan action. Retune like GUIDE_FRAME_WIDTH_RATIO if 503s persist.
private const val MAX_SCAN_RETRIES = 3
private const val SCAN_RETRY_BASE_DELAY_MS = 500L

private const val JPEG_QUALITY = 80

private const val RATE_LIMIT_BODY_LOG_CHARS = 300
private const val RATE_LIMIT_BODY_PEEK_BYTES = 2048L
private const val NANOS_PER_MS = 1_000_000L
private val WHITESPACE_RUN = Regex("\\s+")

/** Per-attempt cap on a scan request (connect + headers + body); a hung provider costs 10s, not 30s. */
internal const val SCAN_CALL_TIMEOUT_MS = 10_000L

/** Shares pool and dispatcher with the receiver; only adds the call timeout. */
internal fun OkHttpClient.forScan(timeoutMs: Long = SCAN_CALL_TIMEOUT_MS): OkHttpClient =
    newBuilder().callTimeout(timeoutMs, TimeUnit.MILLISECONDS).build()

internal const val CARD_SCAN_PROMPT = "Analyze this Pokémon TCG card. The card may be in any language. " +
        "Return ONLY a JSON object with no markdown:\n" +
        "{\"name\":\"Pokémon name in English regardless of card language, or null\"," +
        "\"number\":\"card number without leading zeros or null\"," +
        "\"setTotal\":\"total cards in set or null\"," +
        "\"hp\":\"HP value as digits only or null\"," +
        "\"artist\":\"illustrator name or null\"," +
        "\"dexNumber\":\"National Pokédex number as integer or null\"," +
        "\"language\":\"ISO 639-1 code of the language the card is printed in (e.g. en, ja, ko, th, id, fr); for Chinese cards return zh-CN for Simplified characters or zh-TW for Traditional characters, or null\"," +
        "\"printedName\":\"card name exactly as printed, in its printed language and script, or null\"}"

internal fun ScannedCardJson.toParsedCardInfo(): ParsedCardInfo = ParsedCardInfo(
    cardName = name,
    cardNumber = number,
    setTotal = setTotal,
    hp = hp,
    artist = artist,
    dexNumber = dexNumber,
    language = language?.trim()?.lowercase()?.takeIf { it.isNotEmpty() },
    printedName = printedName?.takeIf { it.isNotBlank() }
)

internal fun bitmapToJpegBase64(bitmap: Bitmap, maxDim: Int = 1024): String {
    val scaled = scaleBitmap(bitmap, maxDim)
    val out = ByteArrayOutputStream()
    scaled.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, out)
    if (scaled !== bitmap) scaled.recycle()
    return Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP)
}

private fun scaleBitmap(bitmap: Bitmap, maxDim: Int): Bitmap {
    val w = bitmap.width
    val h = bitmap.height
    if (w <= maxDim && h <= maxDim) return bitmap
    val scale = maxDim.toFloat() / maxOf(w, h)
    return Bitmap.createScaledBitmap(bitmap, (w * scale).toInt(), (h * scale).toInt(), true)
}

/**
 * Retries only on a 5xx response or a client-side timeout ([InterruptedIOException]: read timeout or
 * OkHttp call timeout); 429, [invalidKeyCodes] and other failures fail immediately. Emits one
 * `attempt ...` line per try via [log]; never the URL, headers or exception messages.
 */
internal suspend fun OkHttpClient.executeWithRetry(
    request: Request,
    provider: ScanProvider,
    invalidKeyCodes: Set<Int> = emptySet(),
    log: (String) -> Unit
): Response {
    val tag = provider.name.lowercase()
    var lastTimeout: InterruptedIOException? = null
    for (attempt in 0..MAX_SCAN_RETRIES) {
        val startNs = System.nanoTime()
        fun logAttempt(outcome: String) = log(
            "attempt provider=$tag n=${attempt + 1} ms=${(System.nanoTime() - startNs) / NANOS_PER_MS} outcome=$outcome"
        )
        val response = try {
            newCall(request).execute()
        } catch (e: InterruptedIOException) {
            logAttempt("timeout")
            lastTimeout = e
            if (attempt < MAX_SCAN_RETRIES) {
                delay(backoffDelayMs(attempt))
                continue
            }
            throw e
        } catch (e: IOException) {
            logAttempt("io:${e::class.java.simpleName}")
            throw e
        }
        if (response.code == 429) {
            logAttempt("http429")
            logRateLimitBody(response, request, tag, log)
            response.close()
            throw RateLimitException(provider)
        }
        if (response.code in invalidKeyCodes) {
            logAttempt("invalidKey${response.code}")
            response.close()
            throw InvalidApiKeyException(provider.displayName, response.code)
        }
        if (response.code in 500..599) {
            logAttempt("http${response.code}")
            response.close() // must close before retrying — avoid leaking the connection
            if (attempt < MAX_SCAN_RETRIES) {
                delay(backoffDelayMs(attempt))
                continue
            }
            throw IOException("${provider.displayName} error: ${response.code}")
        }
        if (!response.isSuccessful) {
            logAttempt("http${response.code}")
            response.close()
            throw IOException("${provider.displayName} error: ${response.code}")
        }
        logAttempt("ok")
        return response
    }
    throw lastTimeout ?: IOException("${provider.displayName} scan failed after retries")
}

/** Logs why a 429 happened (quota id, retryDelay); the request's key is masked in case a body echoes it. */
private fun logRateLimitBody(response: Response, request: Request, providerTag: String, log: (String) -> Unit) {
    var body = runCatching { response.peekBody(RATE_LIMIT_BODY_PEEK_BYTES).string() }.getOrNull() ?: return
    for (header in listOf("x-goog-api-key", "x-api-key")) {
        request.header(header)?.takeIf { it.isNotBlank() }?.let { body = body.replace(it, "<redacted>") }
    }
    log("rateLimit provider=$providerTag body=${body.replace(WHITESPACE_RUN, " ").take(RATE_LIMIT_BODY_LOG_CHARS)}")
}

/** Exponential backoff: 500ms, 1000ms, 2000ms for attempts 0/1/2. */
private fun backoffDelayMs(attempt: Int): Long = SCAN_RETRY_BASE_DELAY_MS * (1L shl attempt)
