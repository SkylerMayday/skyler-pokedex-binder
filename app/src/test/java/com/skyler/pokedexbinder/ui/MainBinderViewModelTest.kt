package com.skyler.pokedexbinder.ui

import android.content.Context
import app.cash.turbine.test
import com.skyler.pokedexbinder.data.model.PokemonSlot
import com.skyler.pokedexbinder.data.model.SlotType
import com.skyler.pokedexbinder.repository.BinderRepository
import com.skyler.pokedexbinder.ui.mainbinder.BinderDisplayItem
import com.skyler.pokedexbinder.ui.mainbinder.MainBinderViewModel
import io.mockk.coJustRun
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class MainBinderViewModelTest {

    private val testDispatcher = UnconfinedTestDispatcher()
    private val binderRepo = mockk<BinderRepository>(relaxed = true)
    private val context = mockk<Context>()

    @Before fun setUp() { Dispatchers.setMain(testDispatcher) }
    @After fun tearDown() { Dispatchers.resetMain() }

    @Test
    fun `grouped view shows gen header and slot`() = runTest {
        val slot = PokemonSlot("bulbasaur", "Bulbasaur", 1, 1, SlotType.BASE)
        every { binderRepo.observeSlots() } returns flowOf(listOf(slot))
        coJustRun { binderRepo.seedIfEmpty(context) }

        val vm = MainBinderViewModel(binderRepo, context)
        vm.displayItems.test {
            val items = awaitItem()
            assertTrue(items.any { it is BinderDisplayItem.Header && (it as BinderDisplayItem.Header).title == "Generation I" })
            assertTrue(items.any { it is BinderDisplayItem.Slot && (it as BinderDisplayItem.Slot).pokemonSlot.id == "bulbasaur" })
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `search filters by name`() = runTest {
        val bulbasaur = PokemonSlot("bulbasaur", "Bulbasaur", 1, 1, SlotType.BASE)
        val charmander = PokemonSlot("charmander", "Charmander", 4, 4, SlotType.BASE)
        every { binderRepo.observeSlots() } returns flowOf(listOf(bulbasaur, charmander))
        coJustRun { binderRepo.seedIfEmpty(context) }

        val vm = MainBinderViewModel(binderRepo, context)
        vm.setSearch("char")
        vm.displayItems.test {
            val items = awaitItem()
            assertEquals(1, items.size)
            val slot = items[0] as BinderDisplayItem.Slot
            assertEquals("charmander", slot.pokemonSlot.id)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `search filters by dex number`() = runTest {
        val bulbasaur = PokemonSlot("bulbasaur", "Bulbasaur", 1, 1, SlotType.BASE)
        val charmander = PokemonSlot("charmander", "Charmander", 4, 4, SlotType.BASE)
        every { binderRepo.observeSlots() } returns flowOf(listOf(bulbasaur, charmander))
        coJustRun { binderRepo.seedIfEmpty(context) }

        val vm = MainBinderViewModel(binderRepo, context)
        vm.setSearch("4")
        vm.displayItems.test {
            val items = awaitItem()
            assertEquals(1, items.size)
            val slot = items[0] as BinderDisplayItem.Slot
            assertEquals("charmander", slot.pokemonSlot.id)
            cancelAndIgnoreRemainingEvents()
        }
    }
}
