package com.skyler.pokedexbinder.ui

import app.cash.turbine.test
import com.skyler.pokedexbinder.data.model.PokemonSlot
import com.skyler.pokedexbinder.data.model.SlotType
import com.skyler.pokedexbinder.repository.BinderRepository
import com.skyler.pokedexbinder.ui.slotdetail.SlotDetailViewModel
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class SlotDetailViewModelTest {

    private val testDispatcher = UnconfinedTestDispatcher()
    private val binderRepo = mockk<BinderRepository>(relaxed = true)

    @Before fun setUp() { Dispatchers.setMain(testDispatcher) }
    @After fun tearDown() { Dispatchers.resetMain() }

    @Test
    fun `slot state loads by pokemonId`() = runTest {
        val slot = PokemonSlot("pikachu", "Pikachu", 25, 25, SlotType.BASE)
        every { binderRepo.observeSlot("pikachu") } returns flowOf(slot)

        val vm = SlotDetailViewModel(binderRepo)
        vm.loadSlot("pikachu")

        vm.slot.test {
            // First emission may be null (initial stateIn value), second is the slot
            val first = awaitItem()
            if (first == null) {
                assertEquals("pikachu", awaitItem()?.id)
            } else {
                assertEquals("pikachu", first.id)
            }
            cancelAndIgnoreRemainingEvents()
        }
        assertFalse(vm.isLoading.value)
    }
}
