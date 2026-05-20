package com.skyler.pokedexbinder.domain

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import com.skyler.pokedexbinder.data.model.TcgCard
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import javax.inject.Inject

class PerceptualHasher @Inject constructor(
    private val okHttpClient: OkHttpClient
) {
    /** Overridden in tests to redirect image downloads to MockWebServer. */
    internal var baseUrl: String = ""

    fun computeHash(bitmap: Bitmap): Long {
        val small = Bitmap.createScaledBitmap(bitmap, 16, 16, false)
        val pixels = IntArray(256)
        small.getPixels(pixels, 0, 16, 0, 0, 16, 16)
        small.recycle()
        val grays = pixels.map { px ->
            val r = (px shr 16) and 0xFF
            val g = (px shr 8) and 0xFF
            val b = px and 0xFF
            (r * 299 + g * 587 + b * 114) / 1000
        }
        val mean = grays.average()
        return grays.foldIndexed(0L) { i, acc, gray ->
            if (gray > mean) acc or (1L shl i) else acc
        }
    }

    fun hammingDistance(a: Long, b: Long): Int = java.lang.Long.bitCount(a xor b)

    suspend fun findBestMatch(candidates: List<TcgCard>, capturedBitmap: Bitmap): TcgCard? {
        if (candidates.isEmpty()) return null
        if (candidates.size == 1) return candidates.first()
        val capturedHash = computeHash(capturedBitmap)
        return withContext(Dispatchers.IO) {
            candidates.minByOrNull { card ->
                val url = if (baseUrl.isNotEmpty()) "$baseUrl${card.imageUrl}" else card.imageUrl
                val cardBitmap = downloadBitmap(url) ?: return@minByOrNull Int.MAX_VALUE
                hammingDistance(capturedHash, computeHash(cardBitmap))
            }
        }
    }

    private suspend fun downloadBitmap(url: String): Bitmap? = withContext(Dispatchers.IO) {
        runCatching {
            val bytes = okHttpClient.newCall(Request.Builder().url(url).build())
                .execute().body?.bytes()
            bytes?.let { BitmapFactory.decodeByteArray(it, 0, it.size) }
        }.getOrNull()
    }
}
