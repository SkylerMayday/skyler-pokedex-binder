package com.skyler.pokedexbinder.domain

import android.graphics.Bitmap
import android.util.Base64
import com.squareup.moshi.JsonClass
import com.squareup.moshi.Moshi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.IOException
import javax.inject.Inject

class RateLimitException : IOException("Rate limited by Gemini API")

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
    val artist: String? = null
)

private const val GEMINI_BASE_URL =
    "https://generativelanguage.googleapis.com/v1beta/models/gemini-2.5-flash:generateContent"

class GeminiCardScanner @Inject constructor(
    private val okHttpClient: OkHttpClient,
    private val moshi: Moshi,
    private val baseUrl: String = GEMINI_BASE_URL
) {
    private val responseAdapter by lazy { moshi.adapter(GeminiResponse::class.java) }
    private val cardResultAdapter by lazy { moshi.adapter(GeminiCardResult::class.java) }

    suspend fun scan(bitmap: Bitmap, apiKey: String): ParsedCardInfo = withContext(Dispatchers.IO) {
        val base64 = bitmapToBase64(bitmap)
        val prompt = "Analyze this Pokémon TCG card. Return ONLY a JSON object with no markdown:\n" +
                "{\"name\":\"pokemon name or null\",\"number\":\"card number without leading zeros or null\"," +
                "\"setTotal\":\"total cards in set or null\",\"hp\":\"HP value or null\",\"artist\":\"artist name or null\"}"
        val body = buildRequestJson(prompt, base64)
        val request = Request.Builder()
            .url("$baseUrl?key=$apiKey")
            .post(body.toRequestBody("application/json".toMediaType()))
            .build()

        val response = okHttpClient.newCall(request).execute()
        if (response.code == 429) throw RateLimitException()
        if (!response.isSuccessful) throw IOException("Gemini error: ${response.code}")

        val geminiResponse = responseAdapter.fromJson(response.body!!.string())
            ?: throw IOException("Empty Gemini response")
        val text = geminiResponse.candidates
            ?.firstOrNull()?.content?.parts?.firstOrNull()?.text
            ?: throw IOException("No text in Gemini response")

        val result = runCatching { cardResultAdapter.fromJson(text) }.getOrNull() ?: GeminiCardResult()
        ParsedCardInfo(
            cardName = result.name,
            cardNumber = result.number,
            setTotal = result.setTotal,
            hp = result.hp,
            artist = result.artist
        )
    }

    private fun bitmapToBase64(bitmap: Bitmap): String {
        val out = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.JPEG, 80, out)
        return Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP)
    }

    private fun buildRequestJson(prompt: String, base64: String): String =
        """{"contents":[{"parts":[{"text":${JSONObject.quote(prompt)}},{"inline_data":{"mime_type":"image/jpeg","data":"$base64"}}]}],"generationConfig":{"response_mime_type":"application/json"}}"""
}
