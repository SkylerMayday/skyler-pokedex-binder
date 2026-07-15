package com.skyler.pokedexbinder.ui.unown

import app.cash.turbine.test
import com.skyler.pokedexbinder.data.local.UnownBinderEntry
import com.skyler.pokedexbinder.data.model.Language
import com.skyler.pokedexbinder.data.model.TcgCard
import com.skyler.pokedexbinder.repository.UnownBinderRepository
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

class UnownBinderViewModelTest {

    private val testDispatcher = UnconfinedTestDispatcher()
    private val repository = mockk<UnownBinderRepository>(relaxed = true)
    private val entriesFlow = MutableStateFlow<List<UnownBinderEntry>>(emptyList())

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
        every { repository.observeEntries() } returns entriesFlow
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `beginAssign then assignPendingCard assigns the card to the pending letter and clears pending`() = runTest {
        val vm = UnownBinderViewModel(repository)
        val card = TcgCard(
            id = "unown-1",
            name = "Unown",
            number = "1",
            setName = "Neo Discovery",
            imageUrl = "https://example.com/unown.png",
            pokemonNames = listOf("Unown")
        )

        vm.beginAssign("A")
        vm.assignPendingCard(card)

        coVerify(exactly = 1) {
            repository.assignCard("A", "unown-1", "https://example.com/unown.png", "Unown", "Neo Discovery")
        }
    }

    @Test
    fun `cancelAssign clears pending letter without assigning`() = runTest {
        val vm = UnownBinderViewModel(repository)

        vm.beginAssign("B")
        vm.cancelAssign()
        vm.assignPendingCard(
            TcgCard(
                id = "unown-2", name = "Unown", number = "2", setName = "Neo Discovery",
                imageUrl = "https://example.com/unown2.png", pokemonNames = listOf("Unown")
            )
        )

        coVerify(exactly = 0) { repository.assignCard(any(), any(), any(), any(), any()) }
    }

    @Test
    fun `clearCard emits blockedByLock when repository reports the letter slot was locked`() = runTest {
        val vm = UnownBinderViewModel(repository)
        coEvery { repository.clearCard("A") } returns false

        vm.blockedByLock.test {
            vm.clearCard("A")
            awaitItem() // Unit event fired — proves the block surfaced to the UI layer.
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `clearCard does not emit blockedByLock when the letter slot was unlocked`() = runTest {
        val vm = UnownBinderViewModel(repository)
        coEvery { repository.clearCard("A") } returns true

        vm.blockedByLock.test {
            vm.clearCard("A")
            expectNoEvents()
        }
    }

    @Test
    fun `updateDetails delegates language, remarks, and lock state to the repository`() = runTest {
        val vm = UnownBinderViewModel(repository)

        vm.updateDetails("A", Language.KO, "Signed copy", true)

        coVerify { repository.updateDetails("A", Language.KO, "Signed copy", true) }
    }
}
