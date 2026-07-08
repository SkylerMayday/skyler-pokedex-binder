package com.skyler.pokedexbinder.domain

import android.util.Log
import com.skyler.pokedexbinder.data.local.MainBinderEntry
import com.skyler.pokedexbinder.data.remote.PokemonTcgApi
import com.skyler.pokedexbinder.data.remote.TcgCardDto
import com.skyler.pokedexbinder.data.remote.TcgCardResponse
import com.skyler.pokedexbinder.data.remote.TcgImagesDto
import com.skyler.pokedexbinder.data.remote.TcgSetDto
import com.skyler.pokedexbinder.repository.BinderRepository
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import java.io.IOException

class BackfillCardNamesUseCaseTest {

    private val binderRepo = mockk<BinderRepository>(relaxed = true)
    private val api = mockk<PokemonTcgApi>()
    private val useCase = BackfillCardNamesUseCase(binderRepo, api)

    @Before
    fun setUp() {
        mockkStatic(Log::class)
        every { Log.w(any(), any<String>(), any()) } returns 0
    }

    private fun row(pokemonId: String, cardId: String?) = MainBinderEntry(
        pokemonId = pokemonId,
        pokemonName = pokemonId.replaceFirstChar { it.uppercase() },
        dexOrder = 1,
        assignedCardId = cardId,
        assignedCardImageUrl = cardId?.let { "https://img.url/$it" }
    )

    private fun cardResponse(id: String, name: String, setName: String) = TcgCardResponse(
        data = TcgCardDto(
            id = id, name = name, number = "1",
            set = TcgSetDto(setName),
            images = TcgImagesDto("https://small.url/$id", "https://large.url/$id")
        )
    )

    @Test
    fun `eligible rows are looked up and written back with name and set`() = runTest {
        val rows = listOf(row("bulbasaur", "base1-44"), row("charizard", "base1-4"))
        coEvery { binderRepo.getRowsNeedingBackfill() } returns rows
        coEvery { api.getCard("base1-44") } returns cardResponse("base1-44", "Bulbasaur", "Base Set")
        coEvery { api.getCard("base1-4") } returns cardResponse("base1-4", "Charizard", "Base Set")

        val result = useCase.backfill()

        assertEquals(2, result)
        coVerify { binderRepo.backfillCardNameSet("bulbasaur", "base1-44", "Bulbasaur", "Base Set") }
        coVerify { binderRepo.backfillCardNameSet("charizard", "base1-4", "Charizard", "Base Set") }
    }

    @Test
    fun `tcgdex-prefixed ids are filtered out and never call getCard`() = runTest {
        val rows = listOf(row("mew", "tcgdex_swsh1-1"), row("bulbasaur", "base1-44"))
        coEvery { binderRepo.getRowsNeedingBackfill() } returns rows
        coEvery { api.getCard("base1-44") } returns cardResponse("base1-44", "Bulbasaur", "Base Set")

        val result = useCase.backfill()

        assertEquals(1, result)
        coVerify(exactly = 0) { api.getCard("tcgdex_swsh1-1") }
        coVerify(exactly = 0) { binderRepo.backfillCardNameSet("mew", any(), any(), any()) }
        coVerify { binderRepo.backfillCardNameSet("bulbasaur", "base1-44", "Bulbasaur", "Base Set") }
    }

    @Test
    fun `a failure on one row does not stop processing of remaining rows`() = runTest {
        val rows = listOf(
            row("bulbasaur", "base1-44"),
            row("missingno", "does-not-exist"),
            row("charizard", "base1-4")
        )
        coEvery { binderRepo.getRowsNeedingBackfill() } returns rows
        coEvery { api.getCard("base1-44") } returns cardResponse("base1-44", "Bulbasaur", "Base Set")
        coEvery { api.getCard("does-not-exist") } throws IOException("404")
        coEvery { api.getCard("base1-4") } returns cardResponse("base1-4", "Charizard", "Base Set")

        val result = useCase.backfill()

        assertEquals(2, result)
        coVerify { binderRepo.backfillCardNameSet("bulbasaur", "base1-44", "Bulbasaur", "Base Set") }
        coVerify(exactly = 0) { binderRepo.backfillCardNameSet("missingno", any(), any(), any()) }
        coVerify { binderRepo.backfillCardNameSet("charizard", "base1-4", "Charizard", "Base Set") }
    }

    @Test
    fun `zero eligible rows results in zero api calls`() = runTest {
        coEvery { binderRepo.getRowsNeedingBackfill() } returns emptyList()

        val result = useCase.backfill()

        assertEquals(0, result)
        coVerify(exactly = 0) { api.getCard(any()) }
        coVerify(exactly = 0) { binderRepo.backfillCardNameSet(any(), any(), any(), any()) }
    }

    @Test
    fun `all rows tcgdex-prefixed results in zero api calls`() = runTest {
        coEvery { binderRepo.getRowsNeedingBackfill() } returns listOf(
            row("mew", "tcgdex_swsh1-1"),
            row("mewtwo", "tcgdex_swsh1-2")
        )

        val result = useCase.backfill()

        assertEquals(0, result)
        coVerify(exactly = 0) { api.getCard(any()) }
    }

    @Test
    fun `row with null assignedCardId is excluded from eligible set`() = runTest {
        // Defensive: the DAO query already filters assignedCardId IS NOT NULL, but the use case
        // also null-checks before calling the API — verify that guard holds independently.
        coEvery { binderRepo.getRowsNeedingBackfill() } returns listOf(row("eevee", null))

        val result = useCase.backfill()

        assertEquals(0, result)
        coVerify(exactly = 0) { api.getCard(any()) }
    }
}
