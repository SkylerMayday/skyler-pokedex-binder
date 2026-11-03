package com.skyler.pokedexbinder.domain

import android.graphics.Bitmap
import android.util.Base64
import kotlinx.coroutines.delay
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InterruptedIOException
import java.util.concurrent.TimeUnit

/** Messages here must never contain an API key or request URL/headers. */
class RateLimitException(val provider: String = "Gemini") : IOException("Rate limited by $provider API")

class InvalidApiKeyException(provider: String, code: Int) :
    IOException("$provider rejected the API key (HTTP $code)")

/** Gemini answered but returned no field at all; used only as a fallback reason, never shown. */
internal class EmptyScanException : IOException("Scanner returned no fields")

/** Every scan provider failed; [message] is user-safe (fixed strings only), the real error is [cause]. */
class ScanFailedException(message: String, cause: Throwable) : IOException(message, cause)

class NoApiKeyException : IllegalStateException("No scanner API key configured")

// Bumped from 2 (500ms fixed) after live sessions saw 11/12 requests 503 in one sitting (2026-09-22)
// and it recurred 2026-09-24 — a fixed 500ms/1.5s total budget doesn't outlast a real overload
// burst. 3 extra attempts (4 total) with exponential backoff gives ~3.5s worst-case extra wait,
// acceptable for a manual scan action. Retune like GUIDE_FRAME_WIDTH_RATIO if 503s persist.
private const val MAX_SCAN_RETRIES = 3
private const val SCAN_RETRY_BASE_DELAY_MS = 500L

private const val JPEG_QUALITY = 80

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
 * OkHttp call timeout); 429, [invalidKeyCodes] and other failures fail immediately.
 */
internal suspend fun OkHttpClient.executeWithRetry(
    request: Request,
    provider: String,
    invalidKeyCodes: Set<Int> = emptySet()
): Response {
    var lastTimeout: InterruptedIOException? = null
    for (attempt in 0..MAX_SCAN_RETRIES) {
        val response = try {
            newCall(request).execute()
        } catch (e: InterruptedIOException) {
            lastTimeout = e
            if (attempt < MAX_SCAN_RETRIES) {
                delay(backoffDelayMs(attempt))
                continue
            }
            throw e
        }
        if (response.code == 429) {
            response.close()
            throw RateLimitException(provider)
        }
        if (response.code in invalidKeyCodes) {
            response.close()
            throw InvalidApiKeyException(provider, response.code)
        }
        if (response.code in 500..599) {
            response.close() // must close before retrying — avoid leaking the connection
            if (attempt < MAX_SCAN_RETRIES) {
                delay(backoffDelayMs(attempt))
                continue
            }
            throw IOException("$provider error: ${response.code}")
        }
        if (!response.isSuccessful) {
            response.close()
            throw IOException("$provider error: ${response.code}")
        }
        return response
    }
    throw lastTimeout ?: IOException("$provider scan failed after retries")
}

/** Exponential backoff: 500ms, 1000ms, 2000ms for attempts 0/1/2. */
private fun backoffDelayMs(attempt: Int): Long = SCAN_RETRY_BASE_DELAY_MS * (1L shl attempt)
