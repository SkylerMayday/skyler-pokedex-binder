package com.skyler.pokedexbinder.domain

import android.graphics.Bitmap
import com.squareup.moshi.JsonClass
import com.squareup.moshi.Moshi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.IOException
import javax.inject.Inject

internal const val CLAUDE_MODEL = "claude-sonnet-4-6"
private const val CLAUDE_BASE_URL = "https://api.anthropic.com/v1/messages"
private const val ANTHROPIC_VERSION = "2023-06-01"
private const val CLAUDE_MAX_TOKENS = 512 // the card JSON object is ~100 tokens
private val CLAUDE_INVALID_KEY_CODES = setOf(401, 403)

@JsonClass(generateAdapter = true)
data class ClaudeResponse(val content: List<ClaudeContentBlock>? = null)

@JsonClass(generateAdapter = true)
data class ClaudeContentBlock(val type: String? = null, val text: String? = null)

/** Fallback scanner used when Gemini fails. Never logs the request, URL, headers, or body. */
class ClaudeCardScanner @Inject constructor(
    private val okHttpClient: OkHttpClient,
    private val moshi: Moshi
) {
    /** Overridden in tests to redirect requests to MockWebServer. */
    internal var baseUrl: String = CLAUDE_BASE_URL

    private val responseAdapter by lazy { moshi.adapter(ClaudeResponse::class.java) }
    private val cardResultAdapter by lazy { moshi.adapter(GeminiCardResult::class.java) }

    suspend fun scan(bitmap: Bitmap, apiKey: String): ParsedCardInfo = withContext(Dispatchers.IO) {
        val body = buildRequestJson(CARD_SCAN_PROMPT, bitmapToJpegBase64(bitmap))
        val request = Request.Builder()
            .url(baseUrl)
            .header("x-api-key", apiKey)
            .header("anthropic-version", ANTHROPIC_VERSION)
            .post(body.toRequestBody("application/json".toMediaType()))
            .build()

        val responseText = okHttpClient.executeWithRetry(request, "Claude", CLAUDE_INVALID_KEY_CODES).use {
            it.body?.string()
        } ?: throw IOException("Empty Claude response")

        val claudeResponse = responseAdapter.fromJson(responseText)
            ?: throw IOException("Empty Claude response")
        val text = claudeResponse.content
            ?.firstOrNull { it.type == "text" && it.text != null }?.text
            ?: throw IOException("No text in Claude response")

        val result = runCatching { cardResultAdapter.fromJson(extractJsonObject(text)) }.getOrNull()
            ?: GeminiCardResult()
        result.toParsedCardInfo()
    }

    private fun buildRequestJson(prompt: String, base64: String): String =
        """{"model":"$CLAUDE_MODEL","max_tokens":$CLAUDE_MAX_TOKENS,"messages":[{"role":"user","content":[{"type":"image","source":{"type":"base64","media_type":"image/jpeg","data":"$base64"}},{"type":"text","text":${JSONObject.quote(prompt)}}]}]}"""
}

/** Claude may wrap the JSON in a markdown fence or prose; slice first `{` to last `}`. */
internal fun extractJsonObject(text: String): String {
    val start = text.indexOf('{')
    val end = text.lastIndexOf('}')
    return if (start in 0 until end) text.substring(start, end + 1) else text
}
