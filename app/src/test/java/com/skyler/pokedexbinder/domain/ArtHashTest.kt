package com.skyler.pokedexbinder.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Random

class ArtHashTest {

    private fun randomGrey(seed: Long, max: Int = 160) =
        Random(seed).let { r -> IntArray(ArtHash.GRID * ArtHash.GRID) { r.nextInt(max + 1) } }

    private fun distance(a: Long, b: Long) = java.lang.Long.bitCount(a xor b)

    @Test
    fun `dct32 of constant image has zero AC`() {
        val coef = ArtHash.dct32(FloatArray(1024) { 100f })
        assertEquals(3200f, coef[0], 0.5f) // orthonormal: 32 * 100
        for (i in 1 until coef.size) assertTrue("coef[$i]=${coef[i]}", Math.abs(coef[i]) < 1e-2f)
    }

    @Test
    fun `dct32 known pattern lands in the k=1 horizontal coefficient`() {
        val n = ArtHash.GRID
        val img = FloatArray(n * n) { i -> Math.cos(Math.PI * (2 * (i % n) + 1) / (2.0 * n)).toFloat() }
        val coef = ArtHash.dct32(img)
        // a(1)*sum(cos^2) horizontally = sqrt(2/32)*16 = 4; vertical DC = sqrt(32) -> ~22.63
        assertEquals(4.0 * Math.sqrt(32.0), coef[1].toDouble(), 1e-2)
        for (i in coef.indices) if (i != 1) assertTrue("coef[$i]=${coef[i]}", Math.abs(coef[i]) < 1e-2f)
    }

    @Test
    fun `hash invariant to brightness shift`() {
        val base = randomGrey(42L)
        val shifted = IntArray(base.size) { base[it] + 40 }
        assertEquals(
            ArtHash.hashFromGrey(base, ArtHash.GRID, ArtHash.GRID),
            ArtHash.hashFromGrey(shifted, ArtHash.GRID, ArtHash.GRID)
        )
    }

    // Independent random images give ~balanced, independent 63-bit hashes (exactly 31 bits set each,
    // expected pairwise distance ~31); <=10 is a ~4-sigma event, so > 10 is a safe bar.
    @Test
    fun `hash distinguishes different patterns`() {
        val a = ArtHash.hashFromGrey(randomGrey(1L), ArtHash.GRID, ArtHash.GRID)
        val b = ArtHash.hashFromGrey(randomGrey(2L), ArtHash.GRID, ArtHash.GRID)
        val c = ArtHash.hashFromGrey(randomGrey(3L), ArtHash.GRID, ArtHash.GRID)
        assertNotEquals(a, b)
        assertTrue(distance(a, b) > 10)
        assertTrue(distance(a, c) > 10)
        assertTrue(distance(b, c) > 10)
    }

    @Test
    fun `hash uses at most 63 bits`() {
        for (seed in 1L..20L) {
            val h = ArtHash.hashFromGrey(randomGrey(seed, 255), ArtHash.GRID, ArtHash.GRID)
            assertEquals(0L, h ushr 63)
        }
    }

    @Test
    fun `boxDownsample of 2x2 constant blocks returns exact block values`() {
        val w = 64
        val grey = IntArray(w * w) { i -> ((i % w) / 2 + (i / w) / 2) % 256 } // 2x2 blocks
        val out = ArtHash.boxDownsample(grey, w, w)
        for (dy in 0 until 32) for (dx in 0 until 32) {
            assertEquals((dx + dy).toFloat(), out[dy * 32 + dx], 1e-4f)
        }
    }

    @Test
    fun `boxDownsample handles non-divisible and tiny sizes and preserves constants`() {
        for ((w, h) in listOf(50 to 70, 7 to 9, 1 to 1)) {
            val out = ArtHash.boxDownsample(IntArray(w * h) { 77 }, w, h)
            assertEquals(1024, out.size)
            out.forEach { assertEquals(77f, it, 1e-4f) }
        }
    }

    @Test
    fun `artRect math`() {
        assertEquals(ArtRect(65, 154, 935, 686), ArtHash.artRect(ArtRect(0, 0, 1000, 1400)))
        // card 749x1046 at (112,157): left 112+48.685, right 112+700.315, top 157+115.06, bottom 157+512.54
        assertEquals(ArtRect(160, 272, 812, 669), ArtHash.artRect(ArtRect(112, 157, 861, 1203)))
    }
}
