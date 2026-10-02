package com.skyler.pokedexbinder.domain

/** Integer rect, right/bottom exclusive. Pure JVM so [ArtHash] stays unit-testable without Android. */
internal data class ArtRect(val left: Int, val top: Int, val right: Int, val bottom: Int) {
    val width: Int get() = right - left
    val height: Int get() = bottom - top
}

/**
 * Perceptual hash of a card's artwork window: box-downsample to 32x32 grey, 2D DCT-II, threshold the
 * low 8x8 block (DC dropped) at its median -> 63 meaningful bits. Hashing only the art box keeps
 * borders, text and whatever surrounds the card in a photo out of the comparison.
 */
internal object ArtHash {
    const val GRID = 32                  // DCT input is GRID x GRID
    const val LOW = 8                    // low-frequency block edge
    const val HASH_BITS = LOW * LOW - 1  // 63, DC dropped

    // Art window as a fraction of the card rect. Double (not Float) so e.g. 1000 * 0.065 lands on
    // exactly 65.0 before truncation. Named for re-tuning from saved device captures.
    const val ART_LEFT = 0.065
    const val ART_RIGHT = 0.935
    const val ART_TOP = 0.11
    const val ART_BOTTOM = 0.49

    /** Art rect inside a card rect (same coordinate space). */
    fun artRect(card: ArtRect): ArtRect = ArtRect(
        left = (card.left + card.width * ART_LEFT).toInt(),
        top = (card.top + card.height * ART_TOP).toInt(),
        right = (card.left + card.width * ART_RIGHT).toInt(),
        bottom = (card.top + card.height * ART_BOTTOM).toInt()
    )

    /** Box (area) average of a w*h grey image down to GRID*GRID, row-major. Each cell spans >= 1 px. */
    fun boxDownsample(grey: IntArray, w: Int, h: Int): FloatArray {
        val out = FloatArray(GRID * GRID)
        for (dy in 0 until GRID) {
            val y0 = dy * h / GRID
            val y1 = maxOf(y0 + 1, (dy + 1) * h / GRID)
            for (dx in 0 until GRID) {
                val x0 = dx * w / GRID
                val x1 = maxOf(x0 + 1, (dx + 1) * w / GRID)
                var sum = 0L
                for (y in y0 until y1) for (x in x0 until x1) sum += grey[y * w + x]
                out[dy * GRID + dx] = sum.toFloat() / ((y1 - y0) * (x1 - x0))
            }
        }
        return out
    }

    // Orthonormal DCT-II basis, row k = frequency, column n = sample. Built once.
    private val cosTable: FloatArray by lazy {
        FloatArray(GRID * GRID).also { t ->
            for (k in 0 until GRID) {
                val scale = Math.sqrt((if (k == 0) 1.0 else 2.0) / GRID)
                for (n in 0 until GRID) {
                    t[k * GRID + n] = (scale * Math.cos(Math.PI * (2 * n + 1) * k / (2.0 * GRID))).toFloat()
                }
            }
        }
    }

    /** Separable orthonormal 32x32 DCT-II. In/out row-major (out index = vertical freq * 32 + horizontal freq). */
    fun dct32(grey: FloatArray): FloatArray {
        val t = cosTable
        val rows = FloatArray(GRID * GRID) // horizontal transform of each row
        for (y in 0 until GRID) for (k in 0 until GRID) {
            var s = 0f
            for (n in 0 until GRID) s += t[k * GRID + n] * grey[y * GRID + n]
            rows[y * GRID + k] = s
        }
        val out = FloatArray(GRID * GRID)
        for (u in 0 until GRID) for (v in 0 until GRID) {
            var s = 0f
            for (y in 0 until GRID) s += t[u * GRID + y] * rows[y * GRID + v]
            out[u * GRID + v] = s
        }
        return out
    }

    /** Low 8x8 block (row-major), DC skipped; bit i-1 set if coefficient i is strictly above the median of the 63. */
    fun hashFromCoefficients(coef: FloatArray): Long {
        val low = FloatArray(LOW * LOW - 1)
        var i = 1
        for (u in 0 until LOW) for (v in 0 until LOW) {
            if (u == 0 && v == 0) continue
            low[i - 1] = coef[u * GRID + v]
            i++
        }
        val median = low.sortedArray()[low.size / 2]
        var hash = 0L
        for (bit in low.indices) if (low[bit] > median) hash = hash or (1L shl bit)
        return hash
    }

    fun hashFromGrey(grey: IntArray, w: Int, h: Int): Long =
        hashFromCoefficients(dct32(boxDownsample(grey, w, h)))
}
