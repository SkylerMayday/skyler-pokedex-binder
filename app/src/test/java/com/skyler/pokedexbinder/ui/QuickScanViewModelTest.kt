package com.skyler.pokedexbinder.ui

import androidx.lifecycle.SavedStateHandle
import com.skyler.pokedexbinder.data.local.SecondaryBinderDao
import com.skyler.pokedexbinder.data.model.TcgCard
import com.skyler.pokedexbinder.domain.AssignCardUseCase
import com.skyler.pokedexbinder.repository.BinderRepository
import com.skyler.pokedexbinder.repository.CardSearchRepository
import com.skyler.pokedexbinder.repository.SearchProgress
import com.skyler.pokedexbinder.ui.quickscan.QuickScanState
import com.skyler.pokedexbinder.ui.quickscan.QuickScanViewModel
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

/**
 * Covers the TCGCSV wiring added 2026-07-15: QuickScanViewModel is the ViewModel behind the
 * MAIN Pokédex binder's slot-tap assign flow — it previously called CardSearchRepository's plain
 * searchByName directly, never touching searchStreaming/TCGCSV. These tests lock in the fix.
 */
class QuickScanViewModelTest {

    private val testDispatcher = UnconfinedTestDispatcher()
    private val cardSearchRepository = mockk<CardSearchRepository>()
    private val binderRepository = mockk<BinderRepository>(relaxed = true)
    private val secondaryBinderDao = mockk<SecondaryBinderDao>(relaxed = true)
    private val assignCardUseCase = mockk<AssignCardUseCase>(relaxed = true)

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun card(id: String, name: String = "Mr. Mime") = TcgCard(
        id = id, name = name, number = "030/034", setName = "Set",
        imageUrl = "https://img/$id.jpg", pokemonNames = listOf(name)
    )

    private fun viewModel(slotId: String = "") = QuickScanViewModel(
        savedStateHandle = SavedStateHandle(mapOf("slotId" to slotId, "replacing" to false)),
        cardSearchRepository = cardSearchRepository,
        binderRepository = binderRepository,
        secondaryBinderDao = secondaryBinderDao,
        assignCardUseCase = assignCardUseCase
    )

    @Test
    fun `manual search goes through searchStreaming, not the plain fast-only path`() = runTest {
        val fast = listOf(card("base1-1"))
        val complete = listOf(card("base1-1"), card("tcgcsv_1"))
        every { cardSearchRepository.searchStreaming(eq("Mr Mime"), any()) } returns
            flowOf(SearchProgress.Fast(fast), SearchProgress.Complete(complete))

        val vm = viewModel()
        vm.search("Mr Mime")

        assertEquals(QuickScanState.CardSelection(complete, stillSearching = false), vm.state.value)
    }

    @Test
    fun `fast results show immediately with stillSearching true`() = runTest {
        val fast = listOf(card("base1-1"))
        every { cardSearchRepository.searchStreaming(eq("Victini"), any()) } returns
            flow { emit(SearchProgress.Fast(fast)); awaitCancellation() }

        val vm = viewModel()
        vm.search("Victini")

        assertEquals(QuickScanState.CardSelection(fast, stillSearching = true), vm.state.value)
    }

    @Test
    fun `empty fast result stays Searching, does not jump to NotFound early`() = runTest {
        // CLB Mr. Mime scenario: fast source (pokemontcg.io+TCGdex) finds nothing, but TCGCSV
        // (still running) might rescue it — must NOT show "not found" before TCGCSV finishes.
        every { cardSearchRepository.searchStreaming(eq("Mr Mime"), any()) } returns
            flow { emit(SearchProgress.Fast(emptyList())); awaitCancellation() }

        val vm = viewModel()
        vm.search("Mr Mime")

        assertEquals(QuickScanState.Searching, vm.state.value)
    }

    @Test
    fun `NotFound only reached after Complete is also empty`() = runTest {
        every { cardSearchRepository.searchStreaming(eq("NoSuchCard"), any()) } returns
            flowOf(SearchProgress.Fast(emptyList()), SearchProgress.Complete(emptyList()))

        val vm = viewModel()
        vm.search("NoSuchCard")

        assertEquals(QuickScanState.NotFound("NoSuchCard"), vm.state.value)
    }

    @Test
    fun `TCGCSV rescues an empty fast result into a real match`() = runTest {
        // The actual CLB Mr. Mime fix: fast is empty, TCGCSV's Complete emission has the card.
        val rescued = listOf(card("tcgcsv_528184", name = "Mr. Mime"))
        every { cardSearchRepository.searchStreaming(eq("Mr Mime"), any()) } returns
            flowOf(SearchProgress.Fast(emptyList()), SearchProgress.Complete(rescued))

        val vm = viewModel()
        vm.search("Mr Mime")

        assertEquals(QuickScanState.CardSelection(rescued, stillSearching = false), vm.state.value)
    }

    @Test
    fun `new search cancels previous in-flight search`() = runTest {
        var slowCancelled = false
        every { cardSearchRepository.searchStreaming(eq("Slow"), any()) } returns
            flow {
                emit(SearchProgress.Fast(listOf(card("slow"))))
                try { awaitCancellation() } finally { slowCancelled = true }
            }
        every { cardSearchRepository.searchStreaming(eq("Fast"), any()) } returns
            flowOf(
                SearchProgress.Fast(listOf(card("fast"))),
                SearchProgress.Complete(listOf(card("fast")))
            )

        val vm = viewModel()
        vm.search("Slow")
        assertEquals(QuickScanState.CardSelection(listOf(card("slow")), stillSearching = true), vm.state.value)

        vm.search("Fast")

        assertTrue("previous search's flow should have been cancelled", slowCancelled)
        assertEquals(QuickScanState.CardSelection(listOf(card("fast")), stillSearching = false), vm.state.value)
    }
}
