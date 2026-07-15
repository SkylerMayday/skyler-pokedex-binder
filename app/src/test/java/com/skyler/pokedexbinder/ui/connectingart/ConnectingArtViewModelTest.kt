package com.skyler.pokedexbinder.ui.connectingart

import app.cash.turbine.test
import com.skyler.pokedexbinder.data.local.ConnectingArtGroup
import com.skyler.pokedexbinder.data.local.ConnectingArtSlot
import com.skyler.pokedexbinder.data.model.Language
import com.skyler.pokedexbinder.data.model.TcgCard
import com.skyler.pokedexbinder.repository.ConnectingArtRepository
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class ConnectingArtViewModelTest {

    private val testDispatcher = UnconfinedTestDispatcher()
    private val repository = mockk<ConnectingArtRepository>(relaxed = true)

    private val groupsFlow = MutableStateFlow<List<ConnectingArtGroup>>(emptyList())
    private val slotsFlow = MutableStateFlow<List<ConnectingArtSlot>>(emptyList())

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
        every { repository.observeGroups() } returns groupsFlow
        every { repository.observeAllSlots() } returns slotsFlow
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `createGroup with a selected grid preset calls repository with matching rows and cols`() = runTest {
        val vm = ConnectingArtViewModel(repository)

        // Simulate the 2x2 preset selection from the create-group sheet.
        vm.createGroup("My Group", rows = 2, cols = 2)

        coVerify(exactly = 1) { repository.createGroup("My Group", 2, 2) }
    }

    @Test
    fun `createGroup with 1x4 preset threads rows and cols through unchanged`() = runTest {
        val vm = ConnectingArtViewModel(repository)

        vm.createGroup("Long Row", rows = 1, cols = 4)

        coVerify(exactly = 1) { repository.createGroup("Long Row", 1, 4) }
    }

    @Test
    fun `deleteGroup calls repository delete only and performs no manual slot cleanup`() = runTest {
        val vm = ConnectingArtViewModel(repository)

        vm.deleteGroup(groupId = 7)

        // Confirms the Planner's explicit non-goal: the ViewModel must not attempt to
        // enumerate or delete slots itself — cascade FK on connecting_art_slot.groupId
        // is relied upon exclusively.
        coVerify(exactly = 1) { repository.deleteGroup(7) }
        coVerify(exactly = 0) { repository.removeCard(any()) }
        coVerify(exactly = 0) { repository.setOwned(any(), any()) }
    }

    @Test
    fun `setOwned toggles owned state via repository`() = runTest {
        val vm = ConnectingArtViewModel(repository)

        vm.setOwned(slotId = 3, owned = true)
        coVerify(exactly = 1) { repository.setOwned(3, true) }

        vm.setOwned(slotId = 3, owned = false)
        coVerify(exactly = 1) { repository.setOwned(3, false) }
    }

    @Test
    fun `beginAssign then assignPendingCard assigns the card to the pending slot and clears pending`() = runTest {
        val vm = ConnectingArtViewModel(repository)
        val card = TcgCard(
            id = "card-1",
            name = "Pikachu",
            number = "25",
            setName = "Base Set",
            imageUrl = "https://example.com/pikachu.png",
            pokemonNames = listOf("Pikachu")
        )

        vm.beginAssign(slotId = 42)
        assertEquals(42, vm.pendingAssignSlotId.value)

        vm.assignPendingCard(card)

        coVerify(exactly = 1) {
            repository.assignCard(42, "card-1", "Pikachu", "https://example.com/pikachu.png")
        }
        assertNull(vm.pendingAssignSlotId.value)
    }

    @Test
    fun `cancelAssign clears pending slot without assigning`() = runTest {
        val vm = ConnectingArtViewModel(repository)

        vm.beginAssign(slotId = 9)
        vm.cancelAssign()

        assertNull(vm.pendingAssignSlotId.value)
    }

    @Test
    fun `assignPendingCard is a no-op when no slot is pending`() = runTest {
        val vm = ConnectingArtViewModel(repository)
        val card = TcgCard(
            id = "card-2",
            name = "Eevee",
            number = "1",
            setName = "Base Set",
            imageUrl = "https://example.com/eevee.png",
            pokemonNames = listOf("Eevee")
        )

        vm.assignPendingCard(card)

        coVerify(exactly = 0) { repository.assignCard(any(), any(), any(), any()) }
    }

    @Test
    fun `uiState groups slots by groupId from combined flows`() = runTest {
        val group1 = ConnectingArtGroup(id = 1, name = "Eeveelutions", rows = 2, cols = 2, position = 0)
        val slot1 = ConnectingArtSlot(id = 10, groupId = 1, slotIndex = 0)
        val slot2 = ConnectingArtSlot(id = 11, groupId = 1, slotIndex = 1)

        every { repository.observeGroups() } returns flowOf(listOf(group1))
        every { repository.observeAllSlots() } returns flowOf(listOf(slot1, slot2))

        val vm = ConnectingArtViewModel(repository)

        vm.uiState.test {
            val state = awaitItem()
            assertEquals(listOf(group1), state.groups)
            assertEquals(listOf(slot1, slot2), state.slotsByGroup[1])
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `reorderGroups reorders local id list and calls repository reorderGroups`() = runTest {
        val g1 = ConnectingArtGroup(id = 1, name = "A", rows = 1, cols = 2, position = 0)
        val g2 = ConnectingArtGroup(id = 2, name = "B", rows = 1, cols = 2, position = 1)
        val g3 = ConnectingArtGroup(id = 3, name = "C", rows = 1, cols = 2, position = 2)
        every { repository.observeGroups() } returns flowOf(listOf(g1, g2, g3))
        every { repository.observeAllSlots() } returns flowOf(emptyList())

        val vm = ConnectingArtViewModel(repository)
        // Force uiState to be observed/computed at least once (StateFlow with WhileSubscribed
        // needs a collector to populate .value under UnconfinedTestDispatcher).
        vm.uiState.test {
            awaitItem()
            cancelAndIgnoreRemainingEvents()
        }

        vm.reorderGroups(fromIndex = 0, toIndex = 2)

        coVerify(exactly = 1) { repository.reorderGroups(listOf(2, 3, 1)) }
    }

    @Test
    fun `updateLanguage delegates slotId and language to the repository`() = runTest {
        val vm = ConnectingArtViewModel(repository)

        vm.updateLanguage(slotId = 5, language = Language.ZH)

        coVerify(exactly = 1) { repository.updateLanguage(5, Language.ZH) }
    }
}
