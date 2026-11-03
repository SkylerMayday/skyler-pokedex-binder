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

@JsonClass(generateAdapter = true)
data class GeminiResponse(val candidates: List<GeminiCandidate>? = null)

@JsonClass(generateAdapter = true)
data class GeminiCandidate(val content: GeminiContent? = null)

@JsonClass(generateAdapter = true)
data class GeminiContent(val parts: List<GeminiPart>? = null)

@JsonClass(generateAdapter = true)
data class GeminiPart(val text: String? = null)

@JsonClass(generateAdapter = true)
data class GeminiCardResult(
    val name: String? = null,
    val number: String? = null,
    val setTotal: String? = null,
    val hp: String? = null,
    val artist: String? = null,
    val dexNumber: Int? = null,
    val language: String? = null,
    val printedName: String? = null
)

// gemini-2.5-flash was retired 2026-09-10: any API key/project created after that date gets a
// hard 404 ("This model ... is no longer available to new users"), confirmed directly against
// the real API with Skyler's newly-split-off pokedex-binder key/project. Old keys created before
// the retirement (e.g. the prior shared "Default Gemini Project") kept working against the old
// model — the 404 only surfaced once quota-decoupling created a genuinely new key. Google's own
// error names gemini-3.6-flash as the replacement; confirmed working against the same new key.
private const val GEMINI_BASE_URL =
    "https://generativelanguage.googleapis.com/v1beta/models/gemini-3.8-flash:generateContent"

class GeminiCardScanner @Inject constructor(
    private val okHttpClient: OkHttpClient,
    private val moshi: Moshi
) {
    /** Overridden in tests to redirect requests to MockWebServer. */
    internal var baseUrl: String = GEMINI_BASE_URL

    private val responseAdapter by lazy { moshi.adapter(GeminiResponse::class.java) }
    private val cardResultAdapter by lazy { moshi.adapter(GeminiCardResult::class.java) }

    suspend fun scan(bitmap: Bitmap, apiKey: String): ParsedCardInfo = withContext(Dispatchers.IO) {
        val base64 = bitmapToJpegBase64(bitmap)
        val body = buildRequestJson(CARD_SCAN_PROMPT, base64)
        val request = Request.Builder()
            .url(baseUrl)
            .header("x-goog-api-key", apiKey)
            .post(body.toRequestBody("application/json".toMediaType()))
            .build()

        val raw = okHttpClient.executeWithRetry(request, "Gemini").use { it.body?.string() }
            ?: throw IOException("Empty Gemini response")
        val geminiResponse = responseAdapter.fromJson(raw)
            ?: throw IOException("Empty Gemini response")
        val text = geminiResponse.candidates
            ?.firstOrNull()?.content?.parts?.firstOrNull()?.text
            ?: throw IOException("No text in Gemini response")

        val result = runCatching { cardResultAdapter.fromJson(text) }.getOrNull() ?: GeminiCardResult()
        result.toParsedCardInfo()
    }

    private fun buildRequestJson(prompt: String, base64: String): String =
        """{"contents":[{"parts":[{"text":${JSONObject.quote(prompt)}},{"inline_data":{"mime_type":"image/jpeg","data":"$base64"}}]}],"generationConfig":{"response_mime_type":"application/json"}}"""
}
