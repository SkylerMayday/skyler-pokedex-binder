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
    fun `englishNames failure returns empty and is retried next time`() = runTest {
        coEvery { api.getSets("en") } throws IOException("down")
        val catalog = TcgdexSetCatalog(api)

        assertTrue(catalog.englishNames().isEmpty())
        coEvery { api.getSets("en") } returns listOf(TcgdexSetBriefDto("sv08", "Surging Sparks"))

        assertEquals("Surging Sparks", catalog.englishNames()["sv08"])
        coVerify(exactly = 2) { api.getSets("en") }
    }
}
