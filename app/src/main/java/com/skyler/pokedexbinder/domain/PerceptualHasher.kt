package com.skyler.pokedexbinder.domain

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import com.skyler.pokedexbinder.data.model.TcgCard
import com.skyler.pokedexbinder.ui.scanner.cardRectInCrop
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import javax.inject.Inject

/** [ranked] = the [RANKED_LOG_SIZE] closest candidates as (card id, distance), for calibration logging. */
data class HashMatchResult(
    val card: TcgCard,
    val distance: Int,
    val margin: Int,
    val ranked: List<Pair<String, Int>> = emptyList()
)

private const val RANKED_LOG_SIZE = 5

/**
 * Art-region perceptual hash (63 meaningful bits, see [ArtHash]) comparison between a captured
 * photo and candidate reference images. Collision history of the old 8x8 whole-bitmap hash:
 * docs/specs/2026-09-13-scanner-hash-confidence.md.
 */
class PerceptualHasher @Inject constructor(
    private val okHttpClient: OkHttpClient
) {
    /** Overridden in tests to redirect image downloads to MockWebServer. */
    internal var baseUrl: String = ""

    /** Reference-card path: the whole bitmap IS the card. */
    fun computeHash(bitmap: Bitmap): Long = computeHash(bitmap, null)

    /** [card] = rect of the card within [bitmap]; null = whole bitmap is the card. */
    internal fun computeHash(bitmap: Bitmap, card: ArtRect?): Long {
        val cardRect = card ?: ArtRect(0, 0, bitmap.width, bitmap.height)
        val art = ArtHash.artRect(cardRect)
        val left = art.left.coerceAtLeast(0)
        val top = art.top.coerceAtLeast(0)
        val width = art.right.coerceAtMost(bitmap.width) - left
        val height = art.bottom.coerceAtMost(bitmap.height) - top
        if (width < 1 || height < 1) return 0L // degenerate bitmap: fixed hash, never throw
        val pixels = IntArray(width * height)
        bitmap.getPixels(pixels, 0, width, left, top, width, height)
        val grey = IntArray(pixels.size) { i ->
            val px = pixels[i]
            (((px shr 16) and 0xFF) * 299 + ((px shr 8) and 0xFF) * 587 + (px and 0xFF) * 114) / 1000
        }
        return ArtHash.hashFromGrey(grey, width, height)
    }

    fun hammingDistance(a: Long, b: Long): Int = java.lang.Long.bitCount(a xor b)

    suspend fun findBestMatch(candidates: List<TcgCard>, capturedBitmap: Bitmap): HashMatchResult? {
        if (candidates.isEmpty()) return null
        val crop = cardRectInCrop(capturedBitmap.width, capturedBitmap.height)
        val capturedHash = computeHash(capturedBitmap, ArtRect(crop.left, crop.top, crop.right, crop.bottom))
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
            val ranked = sorted.take(RANKED_LOG_SIZE).map { it.first.id to it.second }
            HashMatchResult(best.first, best.second, margin, ranked)
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
