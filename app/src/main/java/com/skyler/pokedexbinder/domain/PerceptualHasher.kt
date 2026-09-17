package com.skyler.pokedexbinder.domain

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import com.skyler.pokedexbinder.data.model.TcgCard
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import javax.inject.Inject

data class HashMatchResult(val card: TcgCard, val distance: Int, val margin: Int)

class PerceptualHasher @Inject constructor(
    private val okHttpClient: OkHttpClient
) {
    /** Overridden in tests to redirect image downloads to MockWebServer. */
    internal var baseUrl: String = ""

    fun computeHash(bitmap: Bitmap): Long {
        // 8x8 (64 pixels) matches Long's real 64-bit capacity — a 16x16 (256-pixel) resize here
        // used to overflow `1L shl i` (JVM masks shifts to the low 6 bits), silently OR-ing pixels
        // i and i+64/128/192 onto the same bit and causing real hash collisions between different
        // cards. See docs/specs/2026-09-13-scanner-hash-confidence.md for the empirical writeup.
        val small = Bitmap.createScaledBitmap(bitmap, 8, 8, false)
        val pixels = IntArray(64)
        small.getPixels(pixels, 0, 8, 0, 0, 8, 8)
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

    suspend fun findBestMatch(candidates: List<TcgCard>, capturedBitmap: Bitmap): HashMatchResult? {
        if (candidates.isEmpty()) return null
        val capturedHash = computeHash(capturedBitmap)
        return withContext(Dispatchers.IO) {
            val distances = candidates.map { card ->
                val url = if (baseUrl.isNotEmpty()) "$baseUrl${card.imageUrl}" else card.imageUrl
                val cardBitmap = downloadBitmap(url)
                val distance = cardBitmap?.let { hammingDistance(capturedHash, computeHash(it)) }
                    ?: Int.MAX_VALUE
                card to distance
            }
            val sorted = distances.sortedBy { it.second }
            val best = sorted[0]
            val margin = if (sorted.size >= 2) sorted[1].second - best.second else Int.MAX_VALUE
            HashMatchResult(best.first, best.second, margin)
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
