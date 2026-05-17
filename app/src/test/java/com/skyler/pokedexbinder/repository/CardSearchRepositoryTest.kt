package com.skyler.pokedexbinder.repository

import com.skyler.pokedexbinder.data.remote.*
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class CardSearchRepositoryTest {

    private val api = mockk<PokemonTcgApi>()

    @Test
    fun `searchByNameAndNumber builds correct query and maps results`() = runTest {
        val dto = TcgCardDto(
            id = "xy1-1", name = "Venusaur-EX", number = "1",
            set = TcgSetDto("XY"),
            images = TcgImagesDto("https://small.url", "https://large.url")
        )
        coEvery { api.searchCards("name:\"Venusaur\" number:\"1\"") } returns
            TcgCardsResponse(data = listOf(dto), totalCount = 1)

        val repo = CardSearchRepository(api)
        val results = repo.searchByNameAndNumber("Venusaur", "1")

        assertEquals(1, results.size)
        assertEquals("xy1-1", results[0].id)
        assertEquals("https://large.url", results[0].imageUrl)
    }

    @Test
    fun `searchByName builds name-only query`() = runTest {
        coEvery { api.searchCards("name:\"Pikachu\"") } returns
            TcgCardsResponse(data = emptyList(), totalCount = 0)

        val repo = CardSearchRepository(api)
        val results = repo.searchByName("Pikachu")
        assertTrue(results.isEmpty())
    }
}
