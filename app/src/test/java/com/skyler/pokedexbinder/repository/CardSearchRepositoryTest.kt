package com.skyler.pokedexbinder.repository

import com.skyler.pokedexbinder.data.model.TcgCard
import com.skyler.pokedexbinder.data.remote.*
import com.skyler.pokedexbinder.domain.ParsedCardInfo
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.coVerifyOrder
import io.mockk.mockk
import java.io.IOException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class CardSearchRepositoryTest {

    // Default-empty stubs so unstubbed queries return empty (a successful, empty TCGdex call) instead
    // of a strict-mockk MockKException, which the repository would count as a failed call.
    private val tcgdexApi = mockk<TcgdexApi> {
        coEvery { searchCards(any()) } returns emptyList()
        coEvery { filterCards(any(), any(), any(), any(), any()) } returns emptyList()
        coEvery { searchPromos(any(), any()) } returns emptyList()
        coEvery { cardsInSet(any(), any(), any()) } returns emptyList()
        coEvery { getSets(any()) } returns emptyList()
    }
    private val tcgcsvApi = mockk<TcgcsvApi>()
    private val setCatalog = TcgdexSetCatalog(tcgdexApi)
    private val japanImages = mockk<TcgcsvJapanSource> {
        coEvery { imageUrl(any(), any(), any()) } returns null
    }

    // --- name / number / promo search (TCGdex only) ---

    @Test
    fun `searchByNameWithJapanese maps TCGdex cards with real English set names and high-res images`() = runTest {
        coEvery { tcgdexApi.getSets("en") } returns listOf(TcgdexSetBriefDto("base1", "Base Set"))
        coEvery { tcgdexApi.searchCards("Pikachu") } returns
            listOf(brief("base1-58", "58", "Pikachu", image = "https://assets.tcgdex.net/en/base/base1/58"))
        coEvery { tcgdexApi.getCard("en", "base1-58") } returns TcgdexCardDetailDto("base1-58", "Pikachu", null)

        val results = repo().searchByNameWithJapanese("Pikachu")

        assertEquals(1, results.size)
        assertEquals("tcgdex_base1-58", results[0].id)
        assertEquals("Base Set", results[0].setName)
        assertEquals("https://assets.tcgdex.net/en/base/base1/58/high.webp", results[0].imageUrl)
    }

    @Test
    fun `English set name falls back to the bare set code when the set list is unreachable`() = runTest {
        coEvery { tcgdexApi.getSets("en") } throws IOException("down")
        coEvery { tcgdexApi.searchCards("Pikachu") } returns listOf(brief("base1-58", "58", "Pikachu"))

        val results = repo().searchByNameWithJapanese("Pikachu", 25)

        assertEquals("tcgdex_base1-58", results[0].id)
        assertEquals("BASE1", results[0].setName)
    }

    @Test
    fun `searchByNameWithJapanese keeps both cards with the same name but different number`() = runTest {
        coEvery { tcgdexApi.searchCards("Pikachu") } returns listOf(
            brief("base1-25", "25", "Pikachu"), brief("base1-58", "58", "Pikachu")
        )

        val results = repo().searchByNameWithJapanese("Pikachu", 25)

        assertEquals(listOf("tcgdex_base1-25", "tcgdex_base1-58"), results.map { it.id })
    }

    @Test
    fun `searchByNameWithJapanese drops TCG Pocket cards from the TCGdex results`() = runTest {
        coEvery { tcgdexApi.searchCards("Wishiwashi") } returns listOf(
            TcgdexCardBriefDto(id = "A3-184", localId = "184", name = "Wishiwashi ex", image = "https://assets.tcgdex.net/en/tcgp/A3/184"),
            TcgdexCardBriefDto(id = "sm7-31", localId = "31", name = "Wishiwashi", image = "https://assets.tcgdex.net/en/sm/sm7/31")
        )

        val results = repo().searchByNameWithJapanese("Wishiwashi", 746)

        assertEquals(listOf("tcgdex_sm7-31"), results.map { it.id })
    }

    @Test
    fun `searchByNumber exact-filters the substring localId match`() = runTest {
        coEvery { tcgdexApi.filterCards("en", "SWSH260", null, null, null) } returns listOf(
            brief("swsh12pt5gg-SWSH260", "SWSH260", "Pikachu"),
            brief("other-SWSH2601", "SWSH2601", "Other")
        )

        val results = repo().searchByNumber("swsh260")

        assertEquals(listOf("tcgdex_swsh12pt5gg-SWSH260"), results.map { it.id })
    }

    @Test
    fun `searchByNumber falls back to a set-scoped query for SVP and MEP numbers`() = runTest {
        coEvery { tcgdexApi.cardsInSet("en", "eq:svp", "208") } returns listOf(brief("svp-208", "208", "Victini"))

        val results = repo().searchByNumber("SVP208")

        assertEquals(listOf("tcgdex_svp-208"), results.map { it.id })
        coVerify(exactly = 1) { tcgdexApi.cardsInSet("en", "eq:svp", "208") }
    }

    @Test
    fun `searchByNumber does not use the set-scoped query for other prefixes`() = runTest {
        val results = repo().searchByNumber("SWSH999")

        assertTrue(results.isEmpty())
        coVerify(exactly = 0) { tcgdexApi.cardsInSet(any(), any(), any()) }
    }

    @Test
    fun `searchPromosByName queries the Promo rarity and maps TCGdex ids`() = runTest {
        coEvery { tcgdexApi.searchPromos("Pikachu", "Promo") } returns listOf(brief("svp-208", "208", "Pikachu"))

        val results = repo().searchPromosByName("Pikachu")

        assertEquals(listOf("tcgdex_svp-208"), results.map { it.id })
    }

    // --- searchByParsedInfo (query cascade order, TCGdex only) ---
    // Unstubbed filterCards/searchCards return empty (see the default stubs above), so each test
    // only stubs the queries it wants to hit. parsed() has no language, so the lang order is [ja, en].

    private fun parsed(name: String?, number: String?, total: String?, dex: Int?) =
        ParsedCardInfo(cardName = name, cardNumber = number, setTotal = total, hp = null, artist = null, dexNumber = dex)

    @Test
    fun `searchByParsedInfo prefers dex-pinned match over species-blind number+total`() = runTest {
        // Live 2026-09-24: Castform scan, number+total matched Munna #116.
        coEvery { tcgdexApi.filterCards("en", "116", "eq:172", "eq:351", null) } returns
            listOf(brief("x-116", "116", "Castform"))
        coEvery { tcgdexApi.filterCards("en", "116", "eq:172", null, null) } returns
            listOf(brief("bw-116", "116", "Munna"))

        val result = repo().searchByParsedInfo(parsed("Castform", "116", "172", 351))

        assertEquals(listOf("tcgdex_x-116"), result.cards.map { it.id })
        assertEquals("tcgdex-en+dex+number+total", result.matchedQuery)
    }

    @Test
    fun `searchByParsedInfo tries dex+number+total before name+number+total and species-blind number+total`() = runTest {
        // All three steps would hit: swapping the dex and name steps must fail this test.
        coEvery { tcgdexApi.filterCards("en", "116", "eq:172", "eq:351", null) } returns
            listOf(brief("x-116", "116", "Castform"))
        coEvery { tcgdexApi.filterCards("en", "116", "eq:172", null, "Castform") } returns
            listOf(brief("n-116", "116", "Castform"))
        coEvery { tcgdexApi.filterCards("en", "116", "eq:172", null, null) } returns
            listOf(brief("bw-116", "116", "Castform"))

        val result = repo().searchByParsedInfo(parsed("Castform", "116", "172", 351))

        assertEquals(listOf("tcgdex_x-116"), result.cards.map { it.id })
        assertEquals("tcgdex-en+dex+number+total", result.matchedQuery)
        coVerify(exactly = 0) { tcgdexApi.filterCards("en", "116", "eq:172", null, "Castform") }
    }

    @Test
    fun `searchByParsedInfo continues to broad-number after number+total is name-filtered out`() = runTest {
        coEvery { tcgdexApi.filterCards("en", "11", "eq:64", null, null) } returns
            listOf(brief("jungle-11", "11", "Snorlax"))
        coEvery { tcgdexApi.filterCards("en", "11", null, null, null) } returns listOf(
            brief("jungle-11", "11", "Snorlax"), brief("bw-11", "11", "Black Kyurem")
        )

        val result = repo().searchByParsedInfo(parsed("Black Kyurem", "11", "64", null))

        assertEquals(listOf("tcgdex_bw-11"), result.cards.map { it.id })
        assertEquals("broad-number", result.matchedQuery)
    }

    @Test
    fun `searchByParsedInfo uses name+number+total when there is no dex`() = runTest {
        coEvery { tcgdexApi.filterCards("en", "116", "eq:172", null, "Castform") } returns
            listOf(brief("n-116", "116", "Castform"))
        coEvery { tcgdexApi.filterCards("en", "116", "eq:172", null, null) } returns
            listOf(brief("bw-116", "116", "Munna"))

        val result = repo().searchByParsedInfo(parsed("Castform", "116", "172", null))

        assertEquals(listOf("tcgdex_n-116"), result.cards.map { it.id })
        assertEquals("tcgdex-en+name+number+total", result.matchedQuery)
    }

    @Test
    fun `searchByParsedInfo labels name+number when the set total is unknown`() = runTest {
        coEvery { tcgdexApi.filterCards("en", "116", null, null, "Castform") } returns
            listOf(brief("n-116", "116", "Castform"))

        val result = repo().searchByParsedInfo(parsed("Castform", "116", null, 351))

        assertEquals(listOf("tcgdex_n-116"), result.cards.map { it.id })
        assertEquals("tcgdex-en+name+number", result.matchedQuery)
    }

    @Test
    fun `searchByParsedInfo labels dex+number when the total filter matches nothing`() = runTest {
        coEvery { tcgdexApi.filterCards("en", "116", null, "eq:351", null) } returns
            listOf(brief("x-116", "116", "Castform"))

        val result = repo().searchByParsedInfo(parsed("Castform", "116", "172", 351))

        assertEquals(listOf("tcgdex_x-116"), result.cards.map { it.id })
        assertEquals("tcgdex-en+dex+number", result.matchedQuery)
        assertFalse(result.identityPinned)
    }

    @Test
    fun `searchByParsedInfo labels broad-dex when only a dex number is available`() = runTest {
        coEvery { tcgdexApi.filterCards("en", null, null, "eq:351", null) } returns
            listOf(brief("x-116", "116", "Castform"))

        val result = repo().searchByParsedInfo(parsed(null, null, null, 351))

        assertEquals(listOf("tcgdex_x-116"), result.cards.map { it.id })
        assertEquals("broad-dex", result.matchedQuery)
    }

    @Test
    fun `searchByParsedInfo accepts a name-less number+total hit because there is nothing to verify`() = runTest {
        coEvery { tcgdexApi.filterCards("en", "116", "eq:172", null, null) } returns
            listOf(brief("bw-116", "116", "Munna"))

        val result = repo().searchByParsedInfo(parsed(null, "116", "172", 351))

        assertEquals(listOf("tcgdex_bw-116"), result.cards.map { it.id })
        assertEquals("tcgdex-en+number+total", result.matchedQuery)
    }

    @Test
    fun `searchByParsedInfo reports none-matched and skips number-alone when there is no name`() = runTest {
        val result = repo().searchByParsedInfo(parsed(null, "116", null, null))

        assertTrue(result.cards.isEmpty())
        assertEquals("none-matched", result.matchedQuery)
        // Number alone is thousands of TCGdex cards; without a name to verify it is never queried.
        coVerify(exactly = 0) { tcgdexApi.filterCards(any(), "116", any(), any(), any()) }
    }

    @Test
    fun `searchByParsedInfo labels broad-name when only a name is available`() = runTest {
        coEvery { tcgdexApi.searchCards("Castform") } returns listOf(brief("x-116", "116", "Castform"))

        val result = repo().searchByParsedInfo(parsed("Castform", null, null, null))

        assertEquals(listOf("tcgdex_x-116"), result.cards.map { it.id })
        assertEquals("broad-name", result.matchedQuery)
    }

    @Test
    fun `searchByParsedInfo labels broad-number, not none-matched, when name-verified number-alone hits`() = runTest {
        coEvery { tcgdexApi.filterCards("en", "11", null, null, null) } returns listOf(
            brief("jungle-11", "11", "Snorlax"), brief("bw-11", "11", "Black Kyurem")
        )

        val result = repo().searchByParsedInfo(parsed("Black Kyurem", "11", null, null))

        assertEquals(listOf("tcgdex_bw-11"), result.cards.map { it.id })
        assertEquals("broad-number", result.matchedQuery)
    }

    // --- Stage 1 name verification of species-blind steps (live 2026-10-01 Black Kyurem / Snorlax) ---

    @Test
    fun `searchByParsedInfo rejects a species-blind number+total hit whose name does not match`() = runTest {
        coEvery { tcgdexApi.filterCards("en", "11", "eq:64", null, null) } returns
            listOf(brief("jungle-11", "11", "Snorlax"))

        val result = repo().searchByParsedInfo(parsed("Black Kyurem", "11", "64", 646))

        assertTrue(result.cards.isEmpty())
        assertEquals("none-matched", result.matchedQuery)
    }

    @Test
    fun `searchByParsedInfo keeps number+total hit when the name matches`() = runTest {
        coEvery { tcgdexApi.filterCards("en", "11", "eq:64", null, null) } returns
            listOf(brief("s-11", "11", "Black Kyurem"))

        val result = repo().searchByParsedInfo(parsed("Black Kyurem", "11", "64", 646))

        assertEquals(listOf("tcgdex_s-11"), result.cards.map { it.id })
        assertEquals("tcgdex-en+number+total", result.matchedQuery)
    }

    @Test
    fun `searchByParsedInfo reports none-matched when broad-number hits are all name mismatches`() = runTest {
        coEvery { tcgdexApi.filterCards("en", "11", null, null, null) } returns
            listOf(brief("jungle-11", "11", "Snorlax"))

        val result = repo().searchByParsedInfo(parsed("Black Kyurem", "11", null, null))

        assertTrue(result.cards.isEmpty())
        assertEquals("none-matched", result.matchedQuery)
    }

    @Test
    fun `searchByParsedInfo does not name-filter broad-dex`() = runTest {
        coEvery { tcgdexApi.filterCards("en", null, null, "eq:687", null) } returns
            listOf(brief("m-52", "52", "Malamar"), brief("m-48", "48", "Malamar"))

        val result = repo().searchByParsedInfo(parsed("Mega Malamar ex", "48", "76", 687))

        assertEquals(listOf("tcgdex_m-52", "tcgdex_m-48"), result.cards.map { it.id })
        assertEquals("broad-dex", result.matchedQuery)
    }

    // --- TCGdex outage ---

    private fun assertUnavailable(block: suspend () -> Unit) = runTest {
        try {
            block()
            fail("expected TcgdexUnavailableException")
        } catch (e: TcgdexUnavailableException) {
            assertTrue(e.message!!.contains("unreachable"))
        }
    }

    @Test
    fun `searchByParsedInfo throws Unavailable when every TCGdex call failed and nothing matched`() {
        coEvery { tcgdexApi.filterCards(any(), any(), any(), any(), any()) } throws IOException("network down")
        coEvery { tcgdexApi.searchCards(any()) } throws IOException("network down")

        assertUnavailable { repo().searchByParsedInfo(parsed("Castform", "116", "172", 351)) }
    }

    @Test
    fun `searchByParsedInfo does not throw when only some TCGdex calls failed`() = runTest {
        coEvery { tcgdexApi.filterCards("ja", any(), any(), any(), any()) } throws IOException("down")

        val result = repo().searchByParsedInfo(parsed("Castform", "116", "172", 351))

        assertTrue(result.cards.isEmpty())
        assertEquals("none-matched", result.matchedQuery)
    }

    @Test
    fun `searchByParsedInfo with nothing to query is none-matched, not Unavailable`() = runTest {
        val result = repo().searchByParsedInfo(parsed(null, null, null, null))

        assertEquals("none-matched", result.matchedQuery)
    }

    @Test
    fun `searchByNameWithJapanese throws Unavailable when the name search fails and nothing else answered`() {
        coEvery { tcgdexApi.searchCards(any()) } throws IOException("down")

        assertUnavailable { repo().searchByNameWithJapanese("Pikachu") }
    }

    @Test
    fun `searchByNameWithJapanese returns an empty list when TCGdex answered with no cards`() = runTest {
        assertTrue(repo().searchByNameWithJapanese("Nonexistentmon").isEmpty())
    }

    @Test
    fun `searchByDexNumber searchByNumber and searchPromosByName throw Unavailable on outage`() {
        coEvery { tcgdexApi.filterCards(any(), any(), any(), any(), any()) } throws IOException("down")
        coEvery { tcgdexApi.searchPromos(any(), any()) } throws IOException("down")

        assertUnavailable { repo().searchByDexNumber(25) }
        assertUnavailable { repo().searchByNumber("SWSH001") }
        assertUnavailable { repo().searchPromosByName("Pikachu") }
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

        val repo = CardSearchRepository(tcgdexApi, tcgcsvApi, setCatalog, japanImages)
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

        val repo = CardSearchRepository(tcgdexApi, tcgcsvApi, setCatalog, japanImages)
        val results = repo.searchTcgcsvByName("Mr. Mime")

        assertTrue(results.isEmpty())
    }

    @Test
    fun `searchTcgcsvByName returns empty and does not throw on API failure`() = runTest {
        coEvery { tcgcsvApi.getGroups() } throws RuntimeException("network down")

        val repo = CardSearchRepository(tcgdexApi, tcgcsvApi, setCatalog, japanImages)
        val results = repo.searchTcgcsvByName("Mr. Mime")

        assertTrue(results.isEmpty())
    }

    // --- searchStreaming ---

    @Test
    fun `searchStreaming emits Fast before Complete`() = runTest {
        coEvery { tcgcsvApi.getGroups() } returns TcgcsvGroupsResponse(results = emptyList())
        val repo = CardSearchRepository(tcgdexApi, tcgcsvApi, setCatalog, japanImages)
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
        val repo = CardSearchRepository(tcgdexApi, tcgcsvApi, setCatalog, japanImages)
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
        val repo = CardSearchRepository(tcgdexApi, tcgcsvApi, setCatalog, japanImages)

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
        val repo = CardSearchRepository(tcgdexApi, tcgcsvApi, setCatalog, japanImages)
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
        val repo = CardSearchRepository(tcgdexApi, tcgcsvApi, setCatalog, japanImages)
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
        val repo = CardSearchRepository(tcgdexApi, tcgcsvApi, setCatalog, japanImages)
        val fast = listOf(
            TcgCard("base1-1", "Pikachu", "58", "Base", "https://img/a.jpg", listOf("Pikachu"))
        )

        val emissions = repo.searchStreaming("Pikachu") { fast }.toList()

        assertEquals(2, emissions.size)
        assertTrue(emissions[1] is SearchProgress.Complete)
        assertEquals(fast, (emissions[1] as SearchProgress.Complete).cards) // no crash, no new failure mode
    }

    @Test
    fun `searchStreaming with an unavailable fast source emits TCGCSV cards as the only Complete`() = runTest {
        val group = TcgcsvGroupDto(groupId = 7, name = "Pokemon TCG Classic")
        val product = TcgcsvProductDto(
            productId = 99, name = "Mr. Mime", cleanName = "Mr. Mime",
            imageUrl = "https://tcgcsv/mrmime.jpg", groupId = 7,
            extendedData = listOf(TcgcsvExtendedDataDto(name = "Number", value = "030/034"))
        )
        coEvery { tcgcsvApi.getGroups() } returns TcgcsvGroupsResponse(results = listOf(group))
        coEvery { tcgcsvApi.getProducts(7) } returns TcgcsvProductsResponse(results = listOf(product))

        val emissions = repo().searchStreaming("Mr. Mime") { throw TcgdexUnavailableException() }.toList()

        assertEquals(1, emissions.size)
        assertTrue(emissions[0] is SearchProgress.Complete)
        assertEquals(listOf("tcgcsv_99"), (emissions[0] as SearchProgress.Complete).cards.map { it.id })
    }

    @Test
    fun `searchStreaming rethrows the fast source failure when TCGCSV also finds nothing`() = runTest {
        coEvery { tcgcsvApi.getGroups() } returns TcgcsvGroupsResponse(results = emptyList())

        try {
            repo().searchStreaming("Mr. Mime") { throw TcgdexUnavailableException() }.toList()
            fail("expected TcgdexUnavailableException")
        } catch (e: TcgdexUnavailableException) {
            // expected: the UI shows e.message instead of "no results"
        }
    }

    // --- TCGdex-first scanner cascade (ja + en) ---

    private fun brief(id: String, localId: String, name: String, image: String? = null) =
        TcgdexCardBriefDto(id = id, localId = localId, name = name, image = image)

    private fun parsedFull(
        name: String?, number: String?, total: String?, dex: Int?,
        language: String? = null, printedName: String? = null
    ) = ParsedCardInfo(
        cardName = name, cardNumber = number, setTotal = total, hp = null, artist = null,
        dexNumber = dex, language = language, printedName = printedName
    )

    private fun repo() = CardSearchRepository(tcgdexApi, tcgcsvApi, setCatalog, japanImages)

    @Test
    fun `ja dex+number+total pins a single imageless card and marks it JA`() = runTest {
        coEvery { tcgdexApi.filterCards("ja", "48", "eq:76", "eq:687", null) } returns
            listOf(brief("M6-048", "048", "Mega Malamar ex"))

        val result = repo().searchByParsedInfo(parsedFull("Malamar", "48", "76", 687, language = "ja"))

        assertEquals(listOf("tcgdex_ja_M6-048"), result.cards.map { it.id })
        assertEquals("M6 (JA)", result.cards[0].setName)
        assertEquals("", result.cards[0].imageUrl)
        assertEquals(listOf("Malamar"), result.cards[0].pokemonNames) // English slot name, not the ja card name
        assertEquals("tcgdex-ja+dex+number+total", result.matchedQuery)
        assertTrue(result.identityPinned)
        assertFalse(result.nameVerified)
    }

    @Test
    fun `ja card with a blank image is filled from the TCGCSV Japan source, label and flags unchanged`() = runTest {
        coEvery { tcgdexApi.filterCards("ja", "48", "eq:76", "eq:687", null) } returns
            listOf(brief("M6-048", "048", "Mega Malamar ex"))
        coEvery { japanImages.imageUrl("M6", "048", any()) } returns "https://cdn/709201_400w.jpg"

        val result = repo().searchByParsedInfo(parsedFull("Malamar", "48", "76", 687, language = "ja"))

        assertEquals("https://cdn/709201_400w.jpg", result.cards[0].imageUrl)
        assertEquals("tcgdex-ja+dex+number+total", result.matchedQuery)
        assertTrue(result.identityPinned)
    }

    @Test
    fun `Japan source is skipped for ja cards that have an image and for non-ja cards`() = runTest {
        coEvery { tcgdexApi.filterCards("ja", "48", "eq:76", "eq:687", null) } returns
            listOf(brief("M6-048", "048", "Mega Malamar ex", image = "https://assets.tcgdex.net/ja/M/M6/048"))
        coEvery { tcgdexApi.filterCards("en", "16", "eq:172", "eq:351", null) } returns
            listOf(brief("swsh9-016", "016", "Castform"))

        val ja = repo().searchByParsedInfo(parsedFull("Malamar", "48", "76", 687, language = "ja"))
        val en = repo().searchByParsedInfo(parsedFull("Castform", "16", "172", 351, language = "en"))

        assertEquals("https://assets.tcgdex.net/ja/M/M6/048/high.webp", ja.cards[0].imageUrl)
        assertEquals("", en.cards[0].imageUrl)
        coVerify(exactly = 0) { japanImages.imageUrl(any(), any(), any()) }
    }

    @Test
    fun `Japan image budget is spent only on cards that survive the number and name filters`() = runTest {
        coEvery { tcgdexApi.filterCards("ja", "11", "eq:64", null, null) } returns listOf(
            brief("SV7a-011", "011", "ブラックキュレムex"),
            brief("SM11a-011", "011", "ポリゴン"),
            brief("SV6a-111", "111", "ブラックキュレムex")
        )

        val result = repo().searchByParsedInfo(
            parsedFull("Black Kyurem", "11", "64", null, language = "ja", printedName = "ブラックキュレムex")
        )

        assertEquals(listOf("tcgdex_ja_SV7a-011"), result.cards.map { it.id })
        coVerify(exactly = 1) { japanImages.imageUrl(any(), any(), any()) }
        coVerify(exactly = 1) { japanImages.imageUrl("SV7a", "011", any()) }
    }

    @Test
    fun `searchByNameAllLanguages is complete only when every call succeeded`() = runTest {
        coEvery { tcgdexApi.searchCards("Malamar") } returns listOf(brief("sm6-50", "50", "Malamar"))
        stubAllAsianLangsByDex(687)
        assertTrue(repo().searchByNameAllLanguages("Malamar", 687).complete)

        coEvery { tcgdexApi.filterCards("ko", null, null, "eq:687", null) } throws IOException("down")
        assertFalse(repo().searchByNameAllLanguages("Malamar", 687).complete)

        coEvery { tcgdexApi.getCard(any(), any()) } throws IOException("down")
        val dexFailed = repo().searchByNameAllLanguages("Malamar")
        assertEquals(listOf("tcgdex_sm6-50"), dexFailed.cards.map { it.id })
        assertFalse(dexFailed.complete)
    }

    @Test
    fun `TCGdex results are exact-number filtered because localId is a substring match`() = runTest {
        coEvery { tcgdexApi.filterCards("en", "16", "eq:172", "eq:351", null) } returns listOf(
            brief("swsh9-016", "016", "Castform"),
            brief("swsh9-116", "116", "Castform"),
            brief("swsh9-160", "160", "Castform")
        )

        val result = repo().searchByParsedInfo(parsedFull("Castform", "16", "172", 351, language = "en"))

        assertEquals(listOf("tcgdex_swsh9-016"), result.cards.map { it.id })
    }

    @Test
    fun `ja number+total is verified against printedName when the dex step is empty`() = runTest {
        coEvery { tcgdexApi.filterCards("ja", "11", "eq:64", null, null) } returns listOf(
            brief("SV7a-011", "011", "ブラックキュレムex"),
            brief("SM11a-011", "011", "ポリゴン"),
            brief("SV6a-011", "011", "ピカチュウ")
        )

        val result = repo().searchByParsedInfo(
            parsedFull("Black Kyurem", "11", "64", null, language = "ja", printedName = "ブラックキュレムex")
        )

        assertEquals(listOf("tcgdex_ja_SV7a-011"), result.cards.map { it.id })
        assertEquals("tcgdex-ja+number+total", result.matchedQuery)
        assertFalse(result.identityPinned)
        assertTrue(result.nameVerified)
    }

    @Test
    fun `ja number+total step is skipped when printedName is null`() = runTest {
        coEvery { tcgdexApi.filterCards("ja", "11", "eq:64", null, null) } returns
            listOf(brief("SV6a-011", "011", "ピカチュウ"))

        val result = repo().searchByParsedInfo(parsedFull("Black Kyurem", "11", "64", null, language = "ja"))

        assertTrue(result.cards.isEmpty())
        assertEquals("none-matched", result.matchedQuery)
        coVerify(exactly = 0) { tcgdexApi.filterCards("ja", "11", "eq:64", null, null) }
    }

    @Test
    fun `non-ja language tries the en dex step before ja`() = runTest {
        coEvery { tcgdexApi.filterCards("en", "116", "eq:172", "eq:351", null) } returns
            listOf(brief("swsh9-116", "116", "Castform"))
        coEvery { tcgdexApi.filterCards("ja", "116", "eq:172", "eq:351", null) } returns
            listOf(brief("S9-116", "116", "ポワルン"))

        val result = repo().searchByParsedInfo(parsedFull("Castform", "116", "172", 351, language = "en"))

        assertEquals("tcgdex-en+dex+number+total", result.matchedQuery)
        assertEquals(listOf("tcgdex_swsh9-116"), result.cards.map { it.id })
        coVerify(exactly = 0) { tcgdexApi.filterCards("ja", any(), any(), any(), any()) }
    }

    @Test
    fun `falls back to the looser TCGdex dex+number step when the total-bearing steps are empty`() = runTest {
        coEvery { tcgdexApi.filterCards("en", "116", null, "eq:351", null) } returns
            listOf(brief("swsh9-116", "116", "Castform"))

        val result = repo().searchByParsedInfo(parsedFull("Castform", "116", "172", 351, language = "en"))

        assertEquals(listOf("tcgdex_swsh9-116"), result.cards.map { it.id })
        assertEquals("tcgdex-en+dex+number", result.matchedQuery)
        assertFalse(result.identityPinned)
    }

    @Test
    fun `TCG Pocket cards are dropped on TCGdex filter paths`() = runTest {
        coEvery { tcgdexApi.filterCards("en", "184", "eq:190", "eq:746", null) } returns listOf(
            brief("A3-184", "184", "Wishiwashi ex", image = "https://assets.tcgdex.net/en/tcgp/A3/184"),
            brief("sv3-184", "184", "Wishiwashi", image = "https://assets.tcgdex.net/en/sv/sv3/184")
        )

        val result = repo().searchByParsedInfo(parsedFull("Wishiwashi", "184", "190", 746, language = "en"))

        assertEquals(listOf("tcgdex_sv3-184"), result.cards.map { it.id })
    }

    @Test
    fun `searchByNameWithJapanese appends ja printings of the resolved species marked JA`() = runTest {
        coEvery { tcgdexApi.searchCards("Malamar") } returns listOf(brief("sm6-50", "50", "Malamar"))
        coEvery { tcgdexApi.getCard("en", "sm6-50") } returns TcgdexCardDetailDto("sm6-50", "Malamar", listOf(687))
        coEvery { tcgdexApi.filterCards("ja", null, null, "eq:687", null) } returns
            listOf(brief("M6-048", "048", "Mega Malamar ex"))

        val results = repo().searchByNameWithJapanese("Malamar")

        assertEquals(listOf("tcgdex_sm6-50", "tcgdex_ja_M6-048"), results.map { it.id })
        assertEquals("M6 (JA)", results[1].setName)
        assertEquals(listOf("Malamar"), results[1].pokemonNames)
    }

    @Test
    fun `searchByNameWithJapanese returns only en results when the dex lookup fails`() = runTest {
        coEvery { tcgdexApi.searchCards("Malamar") } returns listOf(brief("sm6-50", "50", "Malamar"))
        coEvery { tcgdexApi.getCard(any(), any()) } throws RuntimeException("network down")

        val results = repo().searchByNameWithJapanese("Malamar")

        assertEquals(listOf("tcgdex_sm6-50"), results.map { it.id })
    }

    @Test
    fun `searchByNameWithJapanese with a known dex never calls getCard`() = runTest {
        coEvery { tcgdexApi.searchCards("Castform") } returns listOf(brief("swsh9-116", "116", "Castform"))
        coEvery { tcgdexApi.filterCards("ja", null, null, "eq:351", null) } returns
            listOf(brief("S9-116", "116", "ポワルン"))

        val results = repo().searchByNameWithJapanese("Castform", 351)

        assertEquals(listOf("tcgdex_swsh9-116", "tcgdex_ja_S9-116"), results.map { it.id })
        coVerify(exactly = 0) { tcgdexApi.getCard(any(), any()) }
    }

    @Test
    fun `searchByNameWithJapanese prefers the exact name over a substring match (Mew vs Mewtwo)`() = runTest {
        // TCGdex name search is substring: "Mew" lists Mewtwo first.
        coEvery { tcgdexApi.searchCards("Mew") } returns listOf(
            brief("base1-10", "10", "Mewtwo"),
            brief("basep-8", "8", "Mew")
        )
        coEvery { tcgdexApi.getCard("en", "base1-10") } returns TcgdexCardDetailDto("base1-10", "Mewtwo", listOf(150))
        coEvery { tcgdexApi.getCard("en", "basep-8") } returns TcgdexCardDetailDto("basep-8", "Mew", listOf(151))
        coEvery { tcgdexApi.filterCards("ja", null, null, "eq:150", null) } returns
            listOf(brief("SV2a-150", "150", "ミュウツー"))
        coEvery { tcgdexApi.filterCards("ja", null, null, "eq:151", null) } returns
            listOf(brief("SV2a-151", "151", "ミュウ"))

        val results = repo().searchByNameWithJapanese("Mew")

        assertEquals(listOf("tcgdex_base1-10", "tcgdex_basep-8", "tcgdex_ja_SV2a-151"), results.map { it.id })
        assertEquals(listOf("Mew"), results[2].pokemonNames)
    }

    @Test
    fun `null language tries the ja dex step before en`() = runTest {
        coEvery { tcgdexApi.filterCards("en", "116", "eq:172", "eq:351", null) } returns
            listOf(brief("swsh9-116", "116", "Castform"))

        val result = repo().searchByParsedInfo(parsedFull("Castform", "116", "172", 351, language = null))

        assertEquals("tcgdex-en+dex+number+total", result.matchedQuery)
        coVerifyOrder {
            tcgdexApi.filterCards("ja", "116", "eq:172", "eq:351", null)
            tcgdexApi.filterCards("en", "116", "eq:172", "eq:351", null)
        }
    }

    @Test
    fun `every TCGdex call failing with no result throws Unavailable instead of reporting no match`() = runTest {
        coEvery { tcgdexApi.filterCards(any(), any(), any(), any(), any()) } throws IOException("network down")
        coEvery { tcgdexApi.searchCards(any()) } throws IOException("network down")

        try {
            repo().searchByParsedInfo(
                parsedFull("Castform", "116", "172", 351, language = "ja", printedName = "ポワルン")
            )
            fail("expected TcgdexUnavailableException")
        } catch (e: TcgdexUnavailableException) {
            assertEquals("Card database (TCGdex) unreachable. Check your connection and try again.", e.message)
        }
    }

    // --- Language mapping (ko / zh-tw / zh-cn / th / id) ---

    @Test
    fun `tcgdexLangsFor maps scan language to ordered TCGdex codes`() {
        val jaEn = listOf("ja", "en")
        val enJa = listOf("en", "ja")
        val tw = listOf("zh-tw", "zh-cn", "en")
        val cn = listOf("zh-cn", "zh-tw", "en")
        assertEquals(jaEn, tcgdexLangsFor(null))
        assertEquals(jaEn, tcgdexLangsFor(""))
        assertEquals(jaEn, tcgdexLangsFor("ja"))
        assertEquals(listOf("ko", "en"), tcgdexLangsFor("ko"))
        assertEquals(listOf("th", "en"), tcgdexLangsFor("th"))
        assertEquals(listOf("id", "en"), tcgdexLangsFor("id"))
        assertEquals(tw, tcgdexLangsFor("zh"))
        assertEquals(tw, tcgdexLangsFor("zh-tw"))
        assertEquals(tw, tcgdexLangsFor("zh-hant"))
        assertEquals(cn, tcgdexLangsFor("zh-cn"))
        assertEquals(cn, tcgdexLangsFor("zh-hans"))
        assertEquals(cn, tcgdexLangsFor("ZH_CN"))
        assertEquals(enJa, tcgdexLangsFor("en"))
        assertEquals(enJa, tcgdexLangsFor("fr"))
        assertTrue(listOf("zh", "zh-cn", "ko", null, "en").all { tcgdexLangsFor(it).size <= MAX_TCGDEX_LANGS })
    }

    @Test
    fun `zh dex step tries zh-tw before zh-cn and labels the hit`() = runTest {
        coEvery { tcgdexApi.filterCards("zh-cn", "36", "eq:78", "eq:25", null) } returns
            listOf(brief("CSV1C-036", "036", "皮卡丘"))

        val result = repo().searchByParsedInfo(parsedFull("Pikachu", "36", "78", 25, language = "zh"))

        assertEquals("tcgdex-zh-cn+dex+number+total", result.matchedQuery)
        assertEquals(listOf("tcgdex_zh-cn_CSV1C-036"), result.cards.map { it.id })
        assertEquals("CSV1C (ZH-CN)", result.cards[0].setName)
        assertTrue(result.identityPinned)
        coVerifyOrder {
            tcgdexApi.filterCards("zh-tw", "36", "eq:78", "eq:25", null)
            tcgdexApi.filterCards("zh-cn", "36", "eq:78", "eq:25", null)
        }
    }

    @Test
    fun `simplified language queries zh-cn first and never reaches zh-tw on a hit`() = runTest {
        coEvery { tcgdexApi.filterCards("zh-cn", "36", "eq:78", "eq:25", null) } returns
            listOf(brief("CSV1C-036", "036", "皮卡丘"))

        repo().searchByParsedInfo(parsedFull("Pikachu", "36", "78", 25, language = "zh-cn"))

        coVerify(exactly = 0) { tcgdexApi.filterCards("zh-tw", any(), any(), any(), any()) }
    }

    @Test
    fun `ko number+total hit matching printedName is name-verified and marked KO`() = runTest {
        coEvery { tcgdexApi.filterCards("ko", "4", "eq:71", null, null) } returns
            listOf(brief("SV5K-004", "004", "수풀부기"))

        val result = repo().searchByParsedInfo(
            parsedFull("Grovyle", "4", "71", null, language = "ko", printedName = "수풀부기")
        )

        assertEquals(listOf("tcgdex_ko_SV5K-004"), result.cards.map { it.id })
        assertEquals("SV5K (KO)", result.cards[0].setName)
        assertEquals("tcgdex-ko+number+total", result.matchedQuery)
        assertEquals(listOf("Grovyle"), result.cards[0].pokemonNames)
        assertTrue(result.nameVerified)
        assertFalse(result.identityPinned)
    }

    @Test
    fun `th and id number+total hits are name-verified with their own marker`() = runTest {
        coEvery { tcgdexApi.filterCards("th", "36", "eq:78", null, null) } returns
            listOf(brief("SV6-036", "036", "ปิกาจู"))
        coEvery { tcgdexApi.filterCards("id", "36", "eq:78", null, null) } returns
            listOf(brief("SV6-036", "036", "Pikachu"))

        val th = repo().searchByParsedInfo(
            parsedFull("Pikachu", "36", "78", null, language = "th", printedName = "ปิกาจู")
        )
        val id = repo().searchByParsedInfo(
            parsedFull("Pikachu", "36", "78", null, language = "id", printedName = "Pikachu")
        )

        assertEquals(listOf("tcgdex_th_SV6-036"), th.cards.map { it.id })
        assertEquals("SV6 (TH)", th.cards[0].setName)
        assertTrue(th.nameVerified)
        assertEquals(listOf("tcgdex_id_SV6-036"), id.cards.map { it.id })
        assertEquals("SV6 (ID)", id.cards[0].setName)
        assertTrue(id.nameVerified)
    }

    @Test
    fun `printedName mismatch falls through and two printedName matches are not name-verified`() = runTest {
        coEvery { tcgdexApi.filterCards("ko", "4", "eq:71", null, null) } returns
            listOf(brief("SV5K-004", "004", "수풀부기"))

        val miss = repo().searchByParsedInfo(
            parsedFull("Grovyle", "4", "71", null, language = "ko", printedName = "다른이름")
        )
        assertTrue(miss.cards.isEmpty())
        assertEquals("none-matched", miss.matchedQuery)

        coEvery { tcgdexApi.filterCards("ko", "4", "eq:71", null, null) } returns listOf(
            brief("SV5K-004", "004", "수풀부기"),
            brief("SV5L-004", "004", "수풀부기")
        )
        val two = repo().searchByParsedInfo(
            parsedFull("Grovyle", "4", "71", null, language = "ko", printedName = "수풀부기")
        )
        assertEquals(2, two.cards.size)
        assertFalse(two.nameVerified)
    }

    @Test
    fun `Thai names that differ only in vowel marks do not match`() = runTest {
        // "กี" (ก + SARA II, a non-spacing mark) vs "กา": marks stripped would reduce both to "ก"-prefixed
        // strings and the substring match would keep the wrong card.
        coEvery { tcgdexApi.filterCards("th", "36", "eq:78", null, null) } returns listOf(
            brief("SV6-036", "036", "กี"),
            brief("SV7-036", "036", "กา")
        )

        val result = repo().searchByParsedInfo(
            parsedFull("Pikachu", "36", "78", null, language = "th", printedName = "กี")
        )

        assertEquals(listOf("tcgdex_th_SV6-036"), result.cards.map { it.id })
        assertTrue(result.nameVerified)
    }

    @Test
    fun `zh scan on a total miss stays inside the per-scan TCGdex call budget`() = runTest {
        repo().searchByParsedInfo(
            parsedFull("Castform", "116", "172", 351, language = "zh", printedName = "ポワルン")
        )

        // number-bearing steps: dex+total 3 + name+total 1 + printedName 2 + en number+total 1
        // + name+number 1 + dex+number 3 + broad-number 1 = 12
        coVerify(atMost = 12) { tcgdexApi.filterCards(any(), "116", any(), any(), any()) }
        // broad-dex walks at most MAX_TCGDEX_LANGS languages
        coVerify(atMost = 3) { tcgdexApi.filterCards(any(), isNull(), isNull(), "eq:351", isNull()) }
        coVerify(exactly = 0) {
            tcgdexApi.filterCards(match { it in setOf("ko", "th", "id", "ja") }, any(), any(), any(), any())
        }
    }

    @Test
    fun `null language never queries ko zh th or id`() = runTest {
        repo().searchByParsedInfo(
            parsedFull("Castform", "116", "172", 351, language = null, printedName = "ポワルン")
        )

        coVerify(exactly = 0) {
            tcgdexApi.filterCards(match { it !in setOf("ja", "en") }, any(), any(), any(), any())
        }
    }

    // --- Manual search / dex search: all six non-English languages ---

    private fun stubAllAsianLangsByDex(dex: Int) {
        TCGDEX_ASIAN_LANGS.forEach { lang ->
            coEvery { tcgdexApi.filterCards(lang, null, null, "eq:$dex", null) } returns
                listOf(brief("SET-001", "001", "name-$lang"))
        }
    }

    private val allSixIds = listOf(
        "tcgdex_ja_SET-001", "tcgdex_ko_SET-001", "tcgdex_zh-tw_SET-001",
        "tcgdex_zh-cn_SET-001", "tcgdex_th_SET-001", "tcgdex_id_SET-001"
    )

    @Test
    fun `searchByNameWithJapanese appends all six languages in order after en`() = runTest {
        coEvery { tcgdexApi.searchCards("Malamar") } returns listOf(brief("sm6-50", "50", "Malamar"))
        stubAllAsianLangsByDex(687)

        val results = repo().searchByNameWithJapanese("Malamar", 687)

        assertEquals(listOf("tcgdex_sm6-50") + allSixIds, results.map { it.id })
        assertEquals("SET (ZH-TW)", results[3].setName)
        TCGDEX_ASIAN_LANGS.forEach { lang ->
            coVerify(exactly = 1) { tcgdexApi.filterCards(lang, null, null, "eq:687", null) }
        }
    }

    @Test
    fun `searchByNameWithJapanese keeps the other languages when one fails`() = runTest {
        coEvery { tcgdexApi.searchCards("Malamar") } returns listOf(brief("sm6-50", "50", "Malamar"))
        stubAllAsianLangsByDex(687)
        coEvery { tcgdexApi.filterCards("ko", null, null, "eq:687", null) } throws IOException("down")

        val results = repo().searchByNameWithJapanese("Malamar", 687)

        assertEquals(
            listOf("tcgdex_sm6-50") + allSixIds.filter { it != "tcgdex_ko_SET-001" },
            results.map { it.id }
        )
    }

    @Test
    fun `searchByDexNumber includes all six non-English languages`() = runTest {
        stubAllAsianLangsByDex(25)

        val results = repo().searchByDexNumber(25)

        assertEquals(allSixIds, results.map { it.id })
    }

    @Test
    fun `pokemonNames seeding applies to non-English ids only`() = runTest {
        coEvery { tcgdexApi.filterCards("th", "36", "eq:78", "eq:25", null) } returns
            listOf(brief("SV6-036", "036", "ปิกาจู"))
        val th = repo().searchByParsedInfo(parsedFull("Pikachu", "36", "78", 25, language = "th"))
        assertEquals(listOf("Pikachu"), th.cards[0].pokemonNames)

        coEvery { tcgdexApi.filterCards("en", "10", "eq:80", null, "Pikachu") } returns
            listOf(brief("sv1-010", "010", "Pikachu & Zekrom GX"))
        val en = repo().searchByParsedInfo(parsedFull("Pikachu", "10", "80", null, language = "en"))
        assertEquals(listOf("Pikachu", "Zekrom"), en.cards[0].pokemonNames)
    }

    // --- cross-language fallback flag ---

    private fun tcgCard(id: String) = TcgCard(id, "Palafin ex", "228", "Set", "", listOf("Palafin"))

    @Test
    fun `isCrossLanguageFallback is true for Asian languages with only English cards`() {
        val en = listOf(tcgCard("tcgdex_sv9-228"))
        listOf("ja", "ko", "zh", "zh-tw", "zh_CN", "zh-hans", "ZH-Hant", "th", "id", " ko ").forEach {
            assertTrue(it, isCrossLanguageFallback(it, en))
        }
    }

    @Test
    fun `isCrossLanguageFallback is false for null, blank, English and European languages`() {
        val en = listOf(tcgCard("tcgdex_sv9-228"))
        listOf(null, "", "  ", "en", "EN-US", "en_GB", "fr", "de", "es", "it", "pt", "xx").forEach {
            assertFalse(it.toString(), isCrossLanguageFallback(it, en))
        }
    }

    @Test
    fun `isCrossLanguageFallback is false for native cards, mixed lists and empty results`() {
        assertFalse(isCrossLanguageFallback("zh", listOf(tcgCard("tcgdex_zh-cn_X"))))
        assertFalse(isCrossLanguageFallback("ja", listOf(tcgCard("tcgdex_ja_X"))))
        assertFalse(isCrossLanguageFallback("ja", listOf(tcgCard("tcgdex_sv9-228"), tcgCard("tcgdex_ja_X"))))
        assertFalse(isCrossLanguageFallback("zh", emptyList()))
    }

    @Test
    fun `zh scan falling to broad-name with English printings is cross-language`() = runTest {
        coEvery { tcgdexApi.searchCards("Palafin ex") } returns
            listOf(brief("sv9-228", "228", "Palafin ex"), brief("sv9-120", "120", "Palafin ex"))

        val result = repo().searchByParsedInfo(parsedFull("Palafin ex", "228", "208", 964, language = "zh"))

        assertEquals("broad-name", result.matchedQuery)
        assertTrue(result.crossLanguage)
    }

    @Test
    fun `zh scan pinned by a single en dex+number+total hit is cross-language`() = runTest {
        coEvery { tcgdexApi.filterCards("en", "228", "eq:208", "eq:964", null) } returns
            listOf(brief("sv9-228", "228", "Palafin ex"))

        val result = repo().searchByParsedInfo(parsedFull("Palafin ex", "228", "208", 964, language = "zh"))

        assertEquals("tcgdex-en+dex+number+total", result.matchedQuery)
        assertTrue(result.identityPinned)
        assertTrue(result.crossLanguage)
    }

    @Test
    fun `same English hit is not cross-language for en, null and fr scans`() = runTest {
        coEvery { tcgdexApi.filterCards("en", "228", "eq:208", "eq:964", null) } returns
            listOf(brief("sv9-228", "228", "Palafin ex"))
        coEvery { tcgdexApi.filterCards("ja", "228", "eq:208", "eq:964", null) } returns
            listOf(brief("sv9-228", "228", "Palafin ex"))

        listOf("en", null, "fr").forEach {
            val result = repo().searchByParsedInfo(parsedFull("Palafin ex", "228", "208", 964, language = it))
            assertFalse(it.toString(), result.crossLanguage)
        }
    }

    @Test
    fun `ko scan with a native hit is not cross-language`() = runTest {
        coEvery { tcgdexApi.filterCards("ko", "4", "eq:71", null, null) } returns
            listOf(brief("SV5K-004", "004", "수풀부기"))

        val result = repo().searchByParsedInfo(
            parsedFull("Grovyle", "4", "71", null, language = "ko", printedName = "수풀부기")
        )

        assertFalse(result.crossLanguage)
    }
}
