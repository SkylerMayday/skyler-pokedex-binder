package com.skyler.pokedexbinder.repository

import com.skyler.pokedexbinder.data.model.TcgCard
import com.skyler.pokedexbinder.data.remote.*
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class CardSearchRepositoryTest {

    private val api = mockk<PokemonTcgApi>()
    private val tcgdexApi = mockk<TcgdexApi>()
    private val tcgcsvApi = mockk<TcgcsvApi>()

    @Test
    fun `searchByNameAndNumber builds correct query and maps results`() = runTest {
        val dto = TcgCardDto(
            id = "xy1-1", name = "Venusaur-EX", number = "1",
            set = TcgSetDto("XY"),
            images = TcgImagesDto("https://small.url", "https://large.url")
        )
        coEvery { api.searchCards("name:*Venusaur* number:\"1\" -set.series:Pocket") } returns
            TcgCardsResponse(data = listOf(dto), totalCount = 1)

        val repo = CardSearchRepository(api, tcgdexApi, tcgcsvApi)
        val results = repo.searchByNameAndNumber("Venusaur", "1")

        assertEquals(1, results.size)
        assertEquals("xy1-1", results[0].id)
        assertEquals("https://large.url", results[0].imageUrl)
    }

    @Test
    fun `searchByName builds name-only query`() = runTest {
        val dto = TcgCardDto(
            id = "base1-58", name = "Pikachu", number = "58",
            set = TcgSetDto("Base"),
            images = TcgImagesDto("https://small.url", "https://large.url")
        )
        coEvery { api.searchCards("name:*Pikachu* -set.series:Pocket") } returns
            TcgCardsResponse(data = listOf(dto), totalCount = 1)
        coEvery { tcgdexApi.searchCards("Pikachu") } returns emptyList()

        val repo = CardSearchRepository(api, tcgdexApi, tcgcsvApi)
        val results = repo.searchByName("Pikachu")

        assertEquals(1, results.size)
        assertEquals("base1-58", results[0].id)
    }

    // --- searchTcgcsvByName (retry-on-empty fallback) ---

    @Test
    fun `searchTcgcsvByName maps products, prefixes id with tcgcsv_, extracts Number, filters by name`() = runTest {
        val group = TcgcsvGroupDto(groupId = 23323, name = "Pokemon TCG Classic")
        val product = TcgcsvProductDto(
            productId = 527885,
            name = "Mr. Mime",
            cleanName = "Mr. Mime",
            imageUrl = "https://tcgplayer-cdn.tcgplayer.com/product/527885_200w.jpg",
            groupId = 23323,
            extendedData = listOf(TcgcsvExtendedDataDto(name = "Number", value = "030/034"))
        )
        coEvery { tcgcsvApi.getGroups() } returns TcgcsvGroupsResponse(results = listOf(group))
        coEvery { tcgcsvApi.getProducts(23323) } returns TcgcsvProductsResponse(results = listOf(product))

        val repo = CardSearchRepository(api, tcgdexApi, tcgcsvApi)
        val results = repo.searchTcgcsvByName("Mr. Mime")

        assertEquals(1, results.size)
        assertEquals("tcgcsv_527885", results[0].id)
        assertEquals("030/034", results[0].number)
        assertEquals("Pokemon TCG Classic", results[0].setName)
    }

    @Test
    fun `searchTcgcsvByName filters out non-matching product names`() = runTest {
        val group = TcgcsvGroupDto(groupId = 1, name = "Base Set")
        val product = TcgcsvProductDto(
            productId = 1, name = "Pikachu", cleanName = "Pikachu",
            imageUrl = null, groupId = 1, extendedData = emptyList()
        )
        coEvery { tcgcsvApi.getGroups() } returns TcgcsvGroupsResponse(results = listOf(group))
        coEvery { tcgcsvApi.getProducts(1) } returns TcgcsvProductsResponse(results = listOf(product))

        val repo = CardSearchRepository(api, tcgdexApi, tcgcsvApi)
        val results = repo.searchTcgcsvByName("Mr. Mime")

        assertTrue(results.isEmpty())
    }

    @Test
    fun `searchTcgcsvByName returns empty and does not throw on API failure`() = runTest {
        coEvery { tcgcsvApi.getGroups() } throws RuntimeException("network down")

        val repo = CardSearchRepository(api, tcgdexApi, tcgcsvApi)
        val results = repo.searchTcgcsvByName("Mr. Mime")

        assertTrue(results.isEmpty())
    }

    // --- searchStreaming ---

    @Test
    fun `searchStreaming emits Fast before Complete`() = runTest {
        coEvery { tcgcsvApi.getGroups() } returns TcgcsvGroupsResponse(results = emptyList())
        val repo = CardSearchRepository(api, tcgdexApi, tcgcsvApi)
        val fast = listOf(
            TcgCard("base1-1", "Pikachu", "58", "Base", "https://img/a.jpg", listOf("Pikachu"))
        )

        val emissions = repo.searchStreaming("Pikachu") { fast }.toList()

        assertEquals(2, emissions.size)
        assertTrue(emissions[0] is SearchProgress.Fast)
        assertTrue(emissions[1] is SearchProgress.Complete)
        assertEquals(fast, (emissions[0] as SearchProgress.Fast).cards)
    }

    @Test
    fun `searchStreaming backfills blank image on existing card without duplicating`() = runTest {
        val group = TcgcsvGroupDto(groupId = 5, name = "SV Promo")
        val product = TcgcsvProductDto(
            productId = 208, name = "Victini", cleanName = "Victini",
            imageUrl = "https://tcgcsv/victini.jpg", groupId = 5,
            extendedData = listOf(TcgcsvExtendedDataDto(name = "Number", value = "SVP208"))
        )
        coEvery { tcgcsvApi.getGroups() } returns TcgcsvGroupsResponse(results = listOf(group))
        coEvery { tcgcsvApi.getProducts(5) } returns TcgcsvProductsResponse(results = listOf(product))
        val repo = CardSearchRepository(api, tcgdexApi, tcgcsvApi)
        val fast = listOf(
            TcgCard("tcgdex_svp-208", "Victini", "208", "SVP", "", listOf("Victini"))
        )

        val complete = repo.searchStreaming("Victini") { fast }.toList()
            .last() as SearchProgress.Complete

        assertEquals(1, complete.cards.size)                                  // no duplicate appended
        assertEquals("tcgdex_svp-208", complete.cards[0].id)                  // identity unchanged
        assertEquals("https://tcgcsv/victini.jpg", complete.cards[0].imageUrl) // image patched in
    }

    @Test
    fun `searchStreaming appends TCGCSV-only card when fast empty`() = runTest {
        val group = TcgcsvGroupDto(groupId = 7, name = "Pokemon TCG Classic")
        val product = TcgcsvProductDto(
            productId = 99, name = "Mr. Mime", cleanName = "Mr. Mime",
            imageUrl = "https://tcgcsv/mrmime.jpg", groupId = 7,
            extendedData = listOf(TcgcsvExtendedDataDto(name = "Number", value = "030/034"))
        )
        coEvery { tcgcsvApi.getGroups() } returns TcgcsvGroupsResponse(results = listOf(group))
        coEvery { tcgcsvApi.getProducts(7) } returns TcgcsvProductsResponse(results = listOf(product))
        val repo = CardSearchRepository(api, tcgdexApi, tcgcsvApi)

        val complete = repo.searchStreaming("Mr. Mime") { emptyList() }.toList()
            .last() as SearchProgress.Complete

        assertEquals(1, complete.cards.size)
        assertEquals("tcgcsv_99", complete.cards[0].id)
    }

    @Test
    fun `searchStreaming does not overwrite an existing non-blank image`() = runTest {
        val group = TcgcsvGroupDto(groupId = 5, name = "SV Promo")
        val product = TcgcsvProductDto(
            productId = 208, name = "Victini", cleanName = "Victini",
            imageUrl = "https://tcgcsv/victini.jpg", groupId = 5,
            extendedData = listOf(TcgcsvExtendedDataDto(name = "Number", value = "SVP208"))
        )
        coEvery { tcgcsvApi.getGroups() } returns TcgcsvGroupsResponse(results = listOf(group))
        coEvery { tcgcsvApi.getProducts(5) } returns TcgcsvProductsResponse(results = listOf(product))
        val repo = CardSearchRepository(api, tcgdexApi, tcgcsvApi)
        val fast = listOf(
            TcgCard("tcgdex_svp-208", "Victini", "208", "SVP", "https://existing/img.jpg", listOf("Victini"))
        )

        val complete = repo.searchStreaming("Victini") { fast }.toList()
            .last() as SearchProgress.Complete

        assertEquals(1, complete.cards.size)                                   // TCGCSV dup suppressed
        assertEquals("https://existing/img.jpg", complete.cards[0].imageUrl)   // not overwritten
    }

    @Test
    fun `cancelling searchStreaming collection stops the in-flight TCGCSV scan`() = runTest {
        val group = TcgcsvGroupDto(groupId = 1, name = "Test Group")
        coEvery { tcgcsvApi.getGroups() } returns TcgcsvGroupsResponse(results = listOf(group))
        var tcgcsvGroupScanCompleted = false
        coEvery { tcgcsvApi.getProducts(1) } coAnswers {
            delay(10_000) // never resolves before the collector is cancelled below
            tcgcsvGroupScanCompleted = true
            TcgcsvProductsResponse(results = emptyList())
        }
        val repo = CardSearchRepository(api, tcgdexApi, tcgcsvApi)
        val fast = listOf(
            TcgCard("base1-1", "Pikachu", "58", "Base", "https://img/a.jpg", listOf("Pikachu"))
        )
        var sawFast = false

        val job = launch {
            repo.searchStreaming("Pikachu") { fast }.collect { progress ->
                if (progress is SearchProgress.Fast) sawFast = true
            }
        }
        advanceTimeBy(100) // let Fast emit and the TCGCSV async reach its delay()
        job.cancel()
        job.join()
        advanceTimeBy(20_000) // give the (now-cancelled) TCGCSV work every chance to finish anyway

        assertTrue("Fast should have emitted before cancellation", sawFast)
        assertFalse(
            "cancelling the collector must cancel the in-flight TCGCSV scan, not let it run to completion",
            tcgcsvGroupScanCompleted
        )
    }

    @Test
    fun `searchStreaming completes normally when TCGCSV throws internally`() = runTest {
        coEvery { tcgcsvApi.getGroups() } throws RuntimeException("network down")
        val repo = CardSearchRepository(api, tcgdexApi, tcgcsvApi)
        val fast = listOf(
            TcgCard("base1-1", "Pikachu", "58", "Base", "https://img/a.jpg", listOf("Pikachu"))
        )

        val emissions = repo.searchStreaming("Pikachu") { fast }.toList()

        assertEquals(2, emissions.size)
        assertTrue(emissions[1] is SearchProgress.Complete)
        assertEquals(fast, (emissions[1] as SearchProgress.Complete).cards) // no crash, no new failure mode
    }
}
