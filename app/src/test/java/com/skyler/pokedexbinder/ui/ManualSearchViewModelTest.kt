package com.skyler.pokedexbinder.ui

import com.skyler.pokedexbinder.data.model.TcgCard
import com.skyler.pokedexbinder.repository.CardSearchRepository
import com.skyler.pokedexbinder.repository.SearchProgress
import com.skyler.pokedexbinder.ui.manualsearch.ManualSearchViewModel
import com.skyler.pokedexbinder.ui.manualsearch.SearchState
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

class ManualSearchViewModelTest {

    private val testDispatcher = UnconfinedTestDispatcher()
    private val cardSearchRepository = mockk<CardSearchRepository>()

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

    @Test
    fun `search shows stillSearching until fast resolves`() = runTest {
        val fast = listOf(card("base1-1"))
        // Emit Fast, then hang -> collection parks at stillSearching = true.
        every { cardSearchRepository.searchStreaming(eq("Mr Mime"), any()) } returns
            flow { emit(SearchProgress.Fast(fast)); awaitCancellation() }

        val vm = ManualSearchViewModel(cardSearchRepository)
        vm.search("Mr Mime")

        assertEquals(SearchState.Results(fast, stillSearching = true), vm.state.value)
    }

    @Test
    fun `search clears stillSearching on Complete`() = runTest {
        val fast = listOf(card("base1-1"))
        val complete = listOf(card("base1-1"), card("tcgcsv_1"))
        every { cardSearchRepository.searchStreaming(eq("Mr Mime"), any()) } returns
            flowOf(SearchProgress.Fast(fast), SearchProgress.Complete(complete))

        val vm = ManualSearchViewModel(cardSearchRepository)
        vm.search("Mr Mime")

        assertEquals(SearchState.Results(complete, stillSearching = false), vm.state.value)
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

        val vm = ManualSearchViewModel(cardSearchRepository)
        vm.search("Slow")
        assertEquals(SearchState.Results(listOf(card("slow")), stillSearching = true), vm.state.value)

        vm.search("Fast")

        assertTrue("previous search's flow should have been cancelled", slowCancelled)
        assertEquals(SearchState.Results(listOf(card("fast")), stillSearching = false), vm.state.value)
    }

    @Test
    fun `search error then retry re-runs search`() = runTest {
        var call = 0
        every { cardSearchRepository.searchStreaming(eq("Mr Mime"), any()) } answers {
            call++
            if (call == 1) flow<SearchProgress> { throw RuntimeException("boom") }
            else flowOf(SearchProgress.Complete(emptyList()))
        }

        val vm = ManualSearchViewModel(cardSearchRepository)
        vm.search("Mr Mime")
        assertTrue(vm.state.value is SearchState.Error)

        vm.retry()

        assertEquals(SearchState.Results(emptyList(), stillSearching = false), vm.state.value)
    }
}
