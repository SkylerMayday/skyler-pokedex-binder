package com.skyler.pokedexbinder.data.remote

import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

class TcgdexSetCatalogTest {

    private val api = mockk<TcgdexApi>()
    private var now = 1_800_000_000_000L

    @Test
    fun `englishNames fetches the set list once and keys it by lowercase set id`() = runTest {
        coEvery { api.getSets("en") } returns listOf(
            TcgdexSetBriefDto("sv08", "Surging Sparks"), TcgdexSetBriefDto("SWSH1", "Sword & Shield")
        )
        val catalog = TcgdexSetCatalog(api)

        assertEquals("Surging Sparks", catalog.englishNames()["sv08"])
        assertEquals("Sword & Shield", catalog.englishNames()["swsh1"])
        assertNull(catalog.englishNames()["nope"])
        coVerify(exactly = 1) { api.getSets("en") }
    }

    @Test
    fun `a failure is remembered`() = runTest {
        coEvery { api.getSets("en") } throws IOException("down")
        val catalog = TcgdexSetCatalog(api).also { it.nowMs = { now } }

        repeat(3) { assertTrue(catalog.englishNames().isEmpty()) }

        coVerify(exactly = 1) { api.getSets("en") }
    }

    @Test
    fun `a failure is retried after the cooldown`() = runTest {
        coEvery { api.getSets("en") } throws IOException("down")
        val catalog = TcgdexSetCatalog(api).also { it.nowMs = { now } }
        assertTrue(catalog.englishNames().isEmpty())

        now += 16 * 60_000L
        coEvery { api.getSets("en") } returns listOf(TcgdexSetBriefDto("sv08", "Surging Sparks"))

        assertEquals("Surging Sparks", catalog.englishNames()["sv08"])
        coVerify(exactly = 2) { api.getSets("en") }
    }
}
