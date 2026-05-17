package com.skyler.pokedexbinder.ui

import android.content.Context
import app.cash.turbine.test
import com.skyler.pokedexbinder.data.model.PokemonSlot
import com.skyler.pokedexbinder.data.model.SlotType
import com.skyler.pokedexbinder.repository.BinderRepository
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
    fun `slots state reflects repository flow`() = runTest {
        val slot = PokemonSlot("bulbasaur", "Bulbasaur", 1, 1, SlotType.BASE)
        every { binderRepo.observeSlots() } returns flowOf(listOf(slot))
        coJustRun { binderRepo.seedIfEmpty(context) }

        val vm = MainBinderViewModel(binderRepo, context)
        vm.slots.test {
            val items = awaitItem()
            assertEquals(1, items.size)
            assertEquals("bulbasaur", items[0].id)
            cancelAndIgnoreRemainingEvents()
        }
    }
}
