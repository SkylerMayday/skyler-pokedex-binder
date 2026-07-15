package com.skyler.pokedexbinder.repository

import app.cash.turbine.test
import com.skyler.pokedexbinder.data.local.MainBinderDao
import com.skyler.pokedexbinder.data.local.MainBinderEntry
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class BinderRepositoryTest {

    private val mainDao = mockk<MainBinderDao>(relaxed = true)

    @Test
    fun `observeSlots maps DB entries to PokemonSlot with card info`() = runTest {
        val entry = MainBinderEntry(
            pokemonId = "bulbasaur",
            pokemonName = "Bulbasaur",
            dexOrder = 1,
            assignedCardId = "xy1-1",
            assignedCardImageUrl = "https://img.pokemontcg.io/xy1/1.png"
        )
        coEvery { mainDao.observeAll() } returns flowOf(listOf(entry))

        val repo = BinderRepository(mainDao)
        repo.observeSlots().test {
            val slots = awaitItem()
            assertEquals(1, slots.size)
            assertEquals("xy1-1", slots[0].assignedCardId)
            assertTrue(slots[0].isOccupied)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `getSlotByPokemonId returns null when not found`() = runTest {
        coEvery { mainDao.getByPokemonId("missingno") } returns null
        val repo = BinderRepository(mainDao)
        assertNull(repo.getSlotByPokemonId("missingno"))
    }

    @Test
    fun `assignCard upserts entry with card info and returns true when unlocked`() = runTest {
        val existing = MainBinderEntry("charizard", "Charizard", dexNumber = 6, dexOrder = 6)
        coEvery { mainDao.getByPokemonId("charizard") } returns existing
        val repo = BinderRepository(mainDao)

        val result = repo.assignCard("charizard", "base4-4", "https://img.pokemontcg.io/base4/4.png", "Charizard", "Base Set")

        assertTrue(result)
        coVerify {
            mainDao.upsert(
                MainBinderEntry(
                    pokemonId = "charizard",
                    pokemonName = "Charizard",
                    dexNumber = 6,
                    dexOrder = 6,
                    assignedCardId = "base4-4",
                    assignedCardImageUrl = "https://img.pokemontcg.io/base4/4.png",
                    assignedCardName = "Charizard",
                    assignedCardSetName = "Base Set"
                )
            )
        }
    }

    @Test
    fun `assignCard no-ops and returns false when slot is locked`() = runTest {
        val existing = MainBinderEntry("charizard", "Charizard", dexNumber = 6, dexOrder = 6, isLocked = true)
        coEvery { mainDao.getByPokemonId("charizard") } returns existing
        val repo = BinderRepository(mainDao)

        val result = repo.assignCard("charizard", "base4-4", "https://img.pokemontcg.io/base4/4.png", "Charizard", "Base Set")

        assertFalse(result)
        coVerify(exactly = 0) { mainDao.upsert(any()) }
    }

    @Test
    fun `clearCard clears assignment and returns true when unlocked`() = runTest {
        val existing = MainBinderEntry(
            "charizard", "Charizard", dexNumber = 6, dexOrder = 6,
            assignedCardId = "base4-4", assignedCardImageUrl = "https://img.pokemontcg.io/base4/4.png"
        )
        coEvery { mainDao.getByPokemonId("charizard") } returns existing
        val repo = BinderRepository(mainDao)

        val result = repo.clearCard("charizard")

        assertTrue(result)
        coVerify {
            mainDao.upsert(
                existing.copy(
                    assignedCardId = null,
                    assignedCardImageUrl = null,
                    assignedCardName = null,
                    assignedCardSetName = null
                )
            )
        }
    }

    @Test
    fun `clearCard no-ops and returns false when slot is locked`() = runTest {
        val existing = MainBinderEntry(
            "charizard", "Charizard", dexNumber = 6, dexOrder = 6,
            assignedCardId = "base4-4", isLocked = true
        )
        coEvery { mainDao.getByPokemonId("charizard") } returns existing
        val repo = BinderRepository(mainDao)

        val result = repo.clearCard("charizard")

        assertFalse(result)
        coVerify(exactly = 0) { mainDao.upsert(any()) }
    }

    @Test
    fun `updateDetails delegates to dao with raw language name`() = runTest {
        val repo = BinderRepository(mainDao)

        repo.updateDetails("charizard", com.skyler.pokedexbinder.data.model.Language.JA, "Holo copy", true)

        coVerify { mainDao.updateDetails("charizard", "JA", "Holo copy", true) }
    }
}
