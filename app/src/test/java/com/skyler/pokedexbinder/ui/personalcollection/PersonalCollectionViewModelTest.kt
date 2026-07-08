package com.skyler.pokedexbinder.ui.personalcollection

import app.cash.turbine.test
import com.skyler.pokedexbinder.data.local.PersonalCollectionCache
import com.skyler.pokedexbinder.data.local.PersonalCollectionEntry
import com.skyler.pokedexbinder.repository.PersonalCollectionRepository
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class PersonalCollectionViewModelTest {

    private val testDispatcher = UnconfinedTestDispatcher()
    private val repository = mockk<PersonalCollectionRepository>(relaxed = true)

    private val cacheFlow = MutableStateFlow<List<PersonalCollectionCache>>(emptyList())
    private val entriesFlow = MutableStateFlow<List<PersonalCollectionEntry>>(emptyList())

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
        every { repository.observeAllCache() } returns cacheFlow
        every { repository.observeEntries() } returns entriesFlow
        coEvery { repository.cacheCount() } returns 1 // non-zero: skip auto refreshAll() in init for most tests
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun cacheRow(cardId: String, pokemonKey: String = "charizard", name: String = "Charizard") =
        PersonalCollectionCache(
            cardId = cardId,
            pokemonKey = pokemonKey,
            name = name,
            imageUrl = "https://example.com/$cardId.png",
            setName = "Base Set",
            releaseDate = "1999-01-09"
        )

    @Test
    fun `init triggers refreshAll only when cache is empty`() = runTest {
        coEvery { repository.cacheCount() } returns 0

        PersonalCollectionViewModel(repository)

        coVerify(atLeast = 1) { repository.refreshPokemon(any(), any()) }
    }

    @Test
    fun `init does not trigger refreshAll when cache already populated`() = runTest {
        coEvery { repository.cacheCount() } returns 5

        PersonalCollectionViewModel(repository)

        coVerify(exactly = 0) { repository.refreshPokemon(any(), any()) }
    }

    @Test
    fun `toggleOwned marks a card owned via setOwned when currently unowned`() = runTest {
        val vm = PersonalCollectionViewModel(repository)

        vm.toggleOwned(cardId = "card-1", currentlyOwned = false)

        coVerify(exactly = 1) { repository.setOwned("card-1", true) }
        coVerify(exactly = 0) { repository.removeOwned(any()) }
    }

    @Test
    fun `toggleOwned removes ownership via removeOwned when currently owned`() = runTest {
        val vm = PersonalCollectionViewModel(repository)

        vm.toggleOwned(cardId = "card-1", currentlyOwned = true)

        coVerify(exactly = 1) { repository.removeOwned("card-1") }
        coVerify(exactly = 0) { repository.setOwned(any(), any()) }
    }

    @Test
    fun `uiState marks cards owned based on entries and unowned otherwise`() = runTest {
        cacheFlow.value = listOf(cacheRow("card-1"), cacheRow("card-2"))
        entriesFlow.value = listOf(PersonalCollectionEntry(cardId = "card-1", owned = true))

        val vm = PersonalCollectionViewModel(repository)

        vm.uiState.test {
            val state = awaitItem()
            val cards = state.sections["charizard"].orEmpty()
            assertTrue(cards.first { it.cardId == "card-1" }.owned)
            assertFalse(cards.first { it.cardId == "card-2" }.owned)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `refreshing with fresh cache data preserves an existing owned entry`() = runTest {
        // Seed: one card already cached and marked owned.
        cacheFlow.value = listOf(cacheRow("card-1"))
        entriesFlow.value = listOf(PersonalCollectionEntry(cardId = "card-1", owned = true))

        val vm = PersonalCollectionViewModel(repository)

        vm.uiState.test {
            val before = awaitItem()
            assertTrue(before.sections["charizard"].orEmpty().first { it.cardId == "card-1" }.owned)

            // Simulate refreshPokemon's real effect: it replaces cache rows for a pokemonKey via
            // PersonalCollectionDao.replaceCacheForPokemon, which never touches
            // personal_collection_entry. A refresh that returns the same card id plus a new one
            // must NOT wipe the existing owned entry — the entriesFlow is untouched by design.
            cacheFlow.value = listOf(cacheRow("card-1"), cacheRow("card-2"))
            vm.refreshAll()

            val after = awaitItem()
            val afterCards = after.sections["charizard"].orEmpty()
            assertTrue(
                "existing owned entry for card-1 must survive a refresh",
                afterCards.first { it.cardId == "card-1" }.owned
            )
            assertFalse(afterCards.first { it.cardId == "card-2" }.owned)
            cancelAndIgnoreRemainingEvents()
        }

        // The ViewModel must delegate to repository.refreshPokemon per section and must never
        // attempt to merge/preserve owned state itself (that guarantee comes from the two-table
        // cache/entry split at the repository/DAO layer, per spec).
        coVerify { repository.refreshPokemon(any(), any()) }
        coVerify(exactly = 0) { repository.setOwned(any(), any()) }
        coVerify(exactly = 0) { repository.removeOwned(any()) }
    }

    @Test
    fun `refreshAll settles isRefreshing back to false and clears prior error on success`() = runTest {
        val vm = PersonalCollectionViewModel(repository)

        vm.refreshAll()

        vm.uiState.test {
            // Under UnconfinedTestDispatcher, refreshAll()'s coroutine body (set true, do work,
            // set false in finally) has already fully run by the time refreshAll() returns, so a
            // fresh collector observes only the final settled state.
            val settled = awaitItem()
            assertFalse(settled.isRefreshing)
            assertNull(settled.errorMessage)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `refreshAll surfaces an error message when repository throws`() = runTest {
        coEvery { repository.refreshPokemon(any(), any()) } throws RuntimeException("network down")

        val vm = PersonalCollectionViewModel(repository)

        vm.refreshAll()

        vm.uiState.test {
            val settled = awaitItem()
            assertFalse(settled.isRefreshing)
            assertEquals("network down", settled.errorMessage)
            cancelAndIgnoreRemainingEvents()
        }
    }
}
