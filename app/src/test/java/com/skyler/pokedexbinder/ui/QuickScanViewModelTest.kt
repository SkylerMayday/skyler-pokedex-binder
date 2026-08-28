package com.skyler.pokedexbinder.ui

import androidx.lifecycle.SavedStateHandle
import com.skyler.pokedexbinder.data.local.SecondaryBinderDao
import com.skyler.pokedexbinder.data.local.SecondaryBinderEntry
import com.skyler.pokedexbinder.data.model.PokemonSlot
import com.skyler.pokedexbinder.data.model.SlotType
import com.skyler.pokedexbinder.data.model.TcgCard
import com.skyler.pokedexbinder.domain.AssignCardUseCase
import com.skyler.pokedexbinder.repository.BinderRepository
import com.skyler.pokedexbinder.repository.CardSearchRepository
import com.skyler.pokedexbinder.repository.SearchProgress
import com.skyler.pokedexbinder.ui.quickscan.QuickScanState
import com.skyler.pokedexbinder.ui.quickscan.QuickScanViewModel
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
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

    private fun viewModel(slotId: String = "", replacing: Boolean = false) = QuickScanViewModel(
        savedStateHandle = SavedStateHandle(mapOf("slotId" to slotId, "replacing" to replacing)),
        cardSearchRepository = cardSearchRepository,
        binderRepository = binderRepository,
        secondaryBinderDao = secondaryBinderDao,
        assignCardUseCase = assignCardUseCase
    )

    private fun pokemonSlot(
        id: String,
        name: String,
        slotType: SlotType = SlotType.BASE,
        dexNumber: Int = 0,
        dexOrder: Int = 0
    ) = PokemonSlot(id = id, name = name, dexNumber = dexNumber, dexOrder = dexOrder, slotType = slotType)

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

    // --- search() query routing: the fastSearch lambda passed to searchStreaming was never
    // actually invoked by any prior test (searchStreaming is fully mocked above) -- these tests
    // capture it and invoke it directly to prove it routes to the right repository method. ---

    @Test
    fun `search with a numeric string routes fastSearch to searchByDexNumber`() = runTest {
        val fastSearch = slot<suspend () -> List<TcgCard>>()
        every { cardSearchRepository.searchStreaming(any(), capture(fastSearch)) } returns
            flowOf(SearchProgress.Complete(emptyList()))
        coEvery { cardSearchRepository.searchByDexNumber(25) } returns listOf(card("dex25"))

        viewModel().search("25")
        fastSearch.captured.invoke()

        coVerify { cardSearchRepository.searchByDexNumber(25) }
    }

    @Test
    fun `search with a promo-number-shaped string routes fastSearch to searchByNumber`() = runTest {
        val fastSearch = slot<suspend () -> List<TcgCard>>()
        every { cardSearchRepository.searchStreaming(any(), capture(fastSearch)) } returns
            flowOf(SearchProgress.Complete(emptyList()))
        coEvery { cardSearchRepository.searchByNumber("SWSH001") } returns listOf(card("promo"))

        viewModel().search("SWSH001")
        fastSearch.captured.invoke()

        coVerify { cardSearchRepository.searchByNumber("SWSH001") }
        coVerify(exactly = 0) { cardSearchRepository.searchByName(any()) }
    }

    @Test
    fun `search with a promo-number-shaped string falls back to searchByName when searchByNumber is empty`() = runTest {
        val fastSearch = slot<suspend () -> List<TcgCard>>()
        every { cardSearchRepository.searchStreaming(any(), capture(fastSearch)) } returns
            flowOf(SearchProgress.Complete(emptyList()))
        coEvery { cardSearchRepository.searchByNumber("SWSH001") } returns emptyList()
        coEvery { cardSearchRepository.searchByName("SWSH001") } returns listOf(card("fallback"))

        viewModel().search("SWSH001")
        fastSearch.captured.invoke()

        coVerify { cardSearchRepository.searchByName("SWSH001") }
    }

    @Test
    fun `search with a plain name routes fastSearch to searchByName`() = runTest {
        val fastSearch = slot<suspend () -> List<TcgCard>>()
        every { cardSearchRepository.searchStreaming(any(), capture(fastSearch)) } returns
            flowOf(SearchProgress.Complete(emptyList()))
        coEvery { cardSearchRepository.searchByName("Pikachu") } returns listOf(card("pika"))

        viewModel().search("Pikachu")
        fastSearch.captured.invoke()

        coVerify { cardSearchRepository.searchByName("Pikachu") }
    }

    // --- init block: auto-search triggered by constructing the ViewModel with a non-blank slotId ---

    @Test
    fun `init auto-searches using the slot's effectiveSearchName when the slot exists`() = runTest {
        // REGIONAL slot: effectiveSearchName strips the region prefix ("Alolan Vulpix" -> "Vulpix"),
        // actually exercising the transform rather than just passing name through unchanged.
        val slotFixture = pokemonSlot(id = "vulpix_alola", name = "Alolan Vulpix", slotType = SlotType.REGIONAL)
        coEvery { binderRepository.getSlotByPokemonId("vulpix_alola") } returns slotFixture
        every { cardSearchRepository.searchStreaming(eq("Vulpix"), any()) } returns
            flowOf(SearchProgress.Complete(emptyList()))

        viewModel(slotId = "vulpix_alola")

        coVerify { binderRepository.getSlotByPokemonId("vulpix_alola") }
        verify { cardSearchRepository.searchStreaming(eq("Vulpix"), any()) }
    }

    @Test
    fun `init does nothing and stays Idle when the slot lookup returns null`() = runTest {
        coEvery { binderRepository.getSlotByPokemonId("missing") } returns null

        val vm = viewModel(slotId = "missing")

        assertEquals(QuickScanState.Idle, vm.state.value)
        verify(exactly = 0) { cardSearchRepository.searchStreaming(any(), any()) }
    }

    // --- selectCard(): slot pre-selected (targetSlotId non-blank) ---

    @Test
    fun `selectCard with slot pre-selected reuses the current CardSelection cards list`() = runTest {
        val slotFixture = pokemonSlot(id = "target", name = "Bulbasaur")
        coEvery { binderRepository.getSlotByPokemonId("target") } returns slotFixture
        every { cardSearchRepository.searchStreaming(any(), any()) } returns
            flowOf(SearchProgress.Complete(emptyList()))
        val vm = viewModel(slotId = "target", replacing = true)
        val options = listOf(card("a"), card("b"))
        vm.wrongCard(options)

        vm.selectCard(options[0])

        assertEquals(QuickScanState.CardConfirm(options[0], options, true), vm.state.value)
        coVerify(exactly = 0) { assignCardUseCase.assign(any(), any()) }
    }

    @Test
    fun `selectCard with slot pre-selected falls back to a single-card list outside CardSelection`() = runTest {
        val slotFixture = pokemonSlot(id = "target", name = "Bulbasaur")
        coEvery { binderRepository.getSlotByPokemonId("target") } returns slotFixture
        every { cardSearchRepository.searchStreaming(any(), any()) } returns
            flowOf(SearchProgress.Complete(emptyList()))
        val vm = viewModel(slotId = "target")
        // Init's auto-search (stubbed above to complete with no results) genuinely establishes a
        // non-CardSelection starting state -- NotFound, not an incidental relaxed-mock no-op.
        assertEquals(QuickScanState.NotFound("Bulbasaur"), vm.state.value)
        val solo = card("solo")

        vm.selectCard(solo)

        assertEquals(QuickScanState.CardConfirm(solo, listOf(solo), false), vm.state.value)
    }

    // --- selectCard(): no slot pre-selected (targetSlotId blank) -- resolves via findMatchingSlots ---

    @Test
    fun `selectCard with no slot pre-selected and zero matches becomes NoSlot`() = runTest {
        coEvery { binderRepository.getAllSlots() } returns listOf(pokemonSlot(id = "s1", name = "Bulbasaur"))
        val vm = viewModel()
        val c = card("x", name = "Charizard")

        vm.selectCard(c)

        assertEquals(QuickScanState.NoSlot(c), vm.state.value)
    }

    @Test
    fun `selectCard with no slot pre-selected and exactly one match auto-assigns`() = runTest {
        val match = pokemonSlot(id = "charizard_id", name = "Charizard")
        coEvery { binderRepository.getAllSlots() } returns listOf(match, pokemonSlot(id = "bulbasaur_id", name = "Bulbasaur"))
        val vm = viewModel()
        val c = card("x", name = "Charizard")

        vm.selectCard(c)

        coVerify { assignCardUseCase.assign("charizard_id", c) }
        assertEquals(QuickScanState.Success(c, "Charizard"), vm.state.value)
    }

    @Test
    fun `selectCard with no slot pre-selected and multiple matches becomes SlotSelection`() = runTest {
        // "Charizard" substring-matches both a BASE slot and a MEGA slot's name.
        val base = pokemonSlot(id = "charizard_base", name = "Charizard", slotType = SlotType.BASE)
        val mega = pokemonSlot(id = "charizard_mega", name = "Mega Charizard X", slotType = SlotType.MEGA)
        coEvery { binderRepository.getAllSlots() } returns listOf(base, mega)
        val vm = viewModel()
        val c = card("x", name = "Charizard")

        vm.selectCard(c)

        assertEquals(QuickScanState.SlotSelection(c, listOf(base, mega)), vm.state.value)
    }

    @Test
    fun `selectCard slot matching is case-insensitive`() = runTest {
        coEvery { binderRepository.getAllSlots() } returns listOf(pokemonSlot(id = "cz", name = "CHARIZARD"))
        val vm = viewModel()
        val c = card("x", name = "charizard")

        vm.selectCard(c)

        coVerify { assignCardUseCase.assign("cz", c) }
    }

    // --- confirmCard / wrongCard / assignToSlot ---

    @Test
    fun `confirmCard assigns to the pre-selected slot and reports its display name`() = runTest {
        val slotFixture = pokemonSlot(id = "target", name = "Bulbasaur")
        coEvery { binderRepository.getSlotByPokemonId("target") } returns slotFixture
        every { cardSearchRepository.searchStreaming(any(), any()) } returns
            flowOf(SearchProgress.Complete(emptyList()))
        val vm = viewModel(slotId = "target")
        val c = card("x")

        vm.confirmCard(c)

        coVerify { assignCardUseCase.assign("target", c) }
        assertEquals(QuickScanState.Success(c, "Bulbasaur"), vm.state.value)
    }

    @Test
    fun `wrongCard shows the alternate list without stillSearching`() = runTest {
        val vm = viewModel()
        val options = listOf(card("a"), card("b"))

        vm.wrongCard(options)

        assertEquals(QuickScanState.CardSelection(options, stillSearching = false), vm.state.value)
    }

    @Test
    fun `assignToSlot assigns to the given slot and reports its name`() = runTest {
        val vm = viewModel()
        val slotFixture = pokemonSlot(id = "s1", name = "Squirtle")
        val c = card("x")

        vm.assignToSlot(slotFixture, c)

        coVerify { assignCardUseCase.assign("s1", c) }
        assertEquals(QuickScanState.Success(c, "Squirtle"), vm.state.value)
    }

    // --- addToSecondary(): secondary-binder fallback ---

    @Test
    fun `addToSecondary inserts a SecondaryBinderEntry using the first pokemonName`() = runTest {
        val vm = viewModel()
        val c = card("x", name = "Mewtwo")

        vm.addToSecondary(c)

        coVerify {
            secondaryBinderDao.insertAtEnd(
                SecondaryBinderEntry(
                    pokemonId = "Mewtwo",
                    pokemonName = c.name,
                    cardId = c.id,
                    cardImageUrl = c.imageUrl
                )
            )
        }
        assertEquals(QuickScanState.Success(c, "Secondary Binder"), vm.state.value)
    }

    @Test
    fun `addToSecondary falls back to an empty pokemonId when pokemonNames is empty`() = runTest {
        val vm = viewModel()
        val c = card("y").copy(pokemonNames = emptyList())

        vm.addToSecondary(c)

        coVerify {
            secondaryBinderDao.insertAtEnd(
                SecondaryBinderEntry(
                    pokemonId = "",
                    pokemonName = c.name,
                    cardId = c.id,
                    cardImageUrl = c.imageUrl
                )
            )
        }
    }

    // --- manual entry flow ---

    @Test
    fun `showManualEntry prefills the last search query`() = runTest {
        every { cardSearchRepository.searchStreaming(eq("Eevee"), any()) } returns
            flowOf(SearchProgress.Complete(emptyList()))
        val vm = viewModel()
        vm.search("Eevee")

        vm.showManualEntry()

        assertEquals(QuickScanState.ManualEntry(prefillName = "Eevee"), vm.state.value)
    }

    @Test
    fun `confirmCustomCard assigns to the pre-selected slot`() = runTest {
        val slotFixture = pokemonSlot(id = "target", name = "Pidgey")
        coEvery { binderRepository.getSlotByPokemonId("target") } returns slotFixture
        every { cardSearchRepository.searchStreaming(any(), any()) } returns
            flowOf(SearchProgress.Complete(emptyList()))
        val vm = viewModel(slotId = "target")

        vm.confirmCustomCard("Pidgey", "Custom Set", "001", "https://img/custom.jpg")

        coVerify {
            assignCardUseCase.assign(
                "target",
                match<TcgCard> {
                    it.name == "Pidgey" && it.setName == "Custom Set" &&
                        it.number == "001" && it.imageUrl == "https://img/custom.jpg" &&
                        it.id.startsWith("custom_")
                }
            )
        }
        assertEquals("Pidgey", (vm.state.value as QuickScanState.Success).slotName)
    }

    @Test
    fun `confirmCustomCard with no slot pre-selected lets the user pick`() = runTest {
        val vm = viewModel()

        vm.confirmCustomCard("MysteryMon", "SetX", "099", "https://img/mystery.jpg")

        val selection = vm.state.value as QuickScanState.CardSelection
        assertEquals(1, selection.cards.size)
        assertEquals("MysteryMon", selection.cards[0].name)
    }

    // --- retry() / reset() ---

    @Test
    fun `retry re-runs the last query`() = runTest {
        every { cardSearchRepository.searchStreaming(eq("Squirtle"), any()) } returnsMany listOf(
            flowOf(SearchProgress.Complete(listOf(card("s1")))),
            flowOf(SearchProgress.Complete(listOf(card("s2"))))
        )
        val vm = viewModel()
        vm.search("Squirtle")
        assertEquals(QuickScanState.CardSelection(listOf(card("s1")), stillSearching = false), vm.state.value)

        vm.retry()

        assertEquals(QuickScanState.CardSelection(listOf(card("s2")), stillSearching = false), vm.state.value)
    }

    @Test
    fun `retry with no prior query stays Idle and does not call the repository`() = runTest {
        val vm = viewModel()

        vm.retry()

        assertEquals(QuickScanState.Idle, vm.state.value)
        verify(exactly = 0) { cardSearchRepository.searchStreaming(any(), any()) }
    }

    @Test
    fun `reset returns to Idle from any non-Idle state`() = runTest {
        val vm = viewModel()
        vm.wrongCard(listOf(card("a")))
        assertTrue(vm.state.value is QuickScanState.CardSelection)

        vm.reset()

        assertEquals(QuickScanState.Idle, vm.state.value)
    }
}
