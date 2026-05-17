package com.skyler.pokedexbinder.domain

import com.skyler.pokedexbinder.data.local.SecondaryBinderDao
import com.skyler.pokedexbinder.data.local.SecondaryBinderEntry
import com.skyler.pokedexbinder.data.model.PokemonSlot
import com.skyler.pokedexbinder.data.model.SlotType
import com.skyler.pokedexbinder.data.model.TcgCard
import com.skyler.pokedexbinder.repository.BinderRepository
import io.mockk.*
import kotlinx.coroutines.test.runTest
import org.junit.Test

class AssignCardUseCaseTest {

    private val binderRepo = mockk<BinderRepository>(relaxed = true)
    private val secondaryDao = mockk<SecondaryBinderDao>(relaxed = true)
    private val useCase = AssignCardUseCase(binderRepo, secondaryDao)

    private fun slot(pokemonId: String, cardId: String? = null) = PokemonSlot(
        id = pokemonId, name = pokemonId.replaceFirstChar { it.uppercase() },
        dexNumber = 1, dexOrder = 1, slotType = SlotType.BASE,
        assignedCardId = cardId,
        assignedCardImageUrl = cardId?.let { "https://img.url/$it" }
    )

    private fun card(id: String, name: String) = TcgCard(
        id = id, name = name, number = "1", setName = "XY",
        imageUrl = "https://img.url/$id", pokemonNames = listOf(name)
    )

    @Test
    fun `assign to empty slot does not touch secondary binder`() = runTest {
        coEvery { binderRepo.getSlotByPokemonId("bulbasaur") } returns slot("bulbasaur")

        useCase.assign("bulbasaur", card("xy1-1", "Bulbasaur"))

        coVerify(exactly = 0) { secondaryDao.insert(any()) }
        coVerify { binderRepo.assignCard("bulbasaur", "xy1-1", "https://img.url/xy1-1") }
    }

    @Test
    fun `assign to occupied slot moves old card to secondary binder`() = runTest {
        coEvery { binderRepo.getSlotByPokemonId("charizard") } returns
            slot("charizard", cardId = "base1-4")

        useCase.assign("charizard", card("xy1-11", "Charizard"))

        coVerify {
            secondaryDao.insert(
                SecondaryBinderEntry(
                    pokemonId = "charizard",
                    pokemonName = "Charizard",
                    cardId = "base1-4",
                    cardImageUrl = "https://img.url/base1-4"
                )
            )
        }
        coVerify { binderRepo.assignCard("charizard", "xy1-11", "https://img.url/xy1-11") }
    }

    @Test
    fun `assign does nothing when slot not found`() = runTest {
        coEvery { binderRepo.getSlotByPokemonId("missingno") } returns null

        useCase.assign("missingno", card("xy1-1", "MissingNo"))

        coVerify(exactly = 0) { secondaryDao.insert(any()) }
        coVerify(exactly = 0) { binderRepo.assignCard(any(), any(), any()) }
    }
}
