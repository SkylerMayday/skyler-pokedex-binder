package com.skyler.pokedexbinder.data.remote

import android.content.Context
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.test.runTest
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import retrofit2.HttpException
import retrofit2.Response
import java.io.File
import java.io.IOException

class TcgcsvJapanSourceTest {
    @get:Rule val tmp = TemporaryFolder()

    private val api = mockk<TcgcsvApi>()
    private var now = 1_800_000_000_000L // 2027-01-15
    private val minute = 60_000L
    private val hour = 60 * minute
    private val day = 24 * hour

    /** A fresh instance has no in-memory copy, so it reads what the previous one wrote to disk. */
    private fun source(): TcgcsvJapanSource {
        val context = mockk<Context> { every { cacheDir } returns tmp.root }
        return TcgcsvJapanSource(api, context).also {
            it.nowMs = { now }
            it.log = {}
        }
    }

    private fun group(id: Int, abbr: String?, name: String = "$abbr: Set", published: String = "2027-01-10T00:00:00") =
        TcgcsvGroupDto(id, name, abbr, published)

    private fun product(id: Int, name: String, number: String?, group: Int = 1) = TcgcsvProductDto(
        productId = id, name = name, groupId = group,
        imageUrl = "https://tcgplayer-cdn.tcgplayer.com/product/${id}_200w.jpg",
        extendedData = if (number == null) emptyList() else listOf(TcgcsvExtendedDataDto("Number", number))
    )

    private fun stubGroups(vararg groups: TcgcsvGroupDto) {
        coEvery { api.getJapanGroups() } returns TcgcsvGroupsResponse(groups.toList())
    }

    private fun stubProducts(groupId: Int, vararg products: TcgcsvProductDto) {
        coEvery { api.getJapanProducts(groupId) } returns TcgcsvProductsResponse(products.toList())
    }

    private val m6 = arrayOf(
        product(709201, "Mega Malamar ex", "048/076"),
        product(709177, "Mega Swampert ex", "021/076")
    )

    @Test
    fun `joins by abbreviation and number numerator and returns the 400w image`() = runTest {
        stubGroups(group(1, "M6"))
        stubProducts(1, *m6)
        val s = source()

        assertEquals("https://tcgplayer-cdn.tcgplayer.com/product/709201_400w.jpg", s.imageUrl("M6", "048"))
        assertEquals("https://tcgplayer-cdn.tcgplayer.com/product/709177_400w.jpg", s.imageUrl("m6", "21"))
    }

    @Test
    fun `five lookups in one set cost one groups call and one product call`() = runTest {
        stubGroups(group(1, "M6"))
        stubProducts(1, *m6)
        val s = source()

        repeat(5) { s.imageUrl("M6", "048") }

        coVerify(exactly = 1) { api.getJapanGroups() }
        coVerify(exactly = 1) { api.getJapanProducts(1) }
    }

    @Test
    fun `variants lose to the plain product, then the lowest productId wins`() = runTest {
        stubGroups(group(1, "M6"))
        stubProducts(
            1,
            product(30, "Card [Set of 2]", "005/076"),
            product(20, "Card (Poke Ball Pattern)", "005/076"),
            product(25, "Card", "005/076"),
            product(24, "Card", "005/076"),
            product(40, "Only Variant (Master Ball Pattern)", "006/076"),
            product(41, "Promo", "001/SV-P")
        )
        val s = source()

        assertTrue(s.imageUrl("M6", "5")!!.contains("/24_400w"))
        assertTrue(s.imageUrl("M6", "6")!!.contains("/40_400w"))
        assertTrue(s.imageUrl("M6", "1")!!.contains("/41_400w"))
    }

    @Test
    fun `denominator mismatch still matches, unknown set and non-numeric id give null without a product call`() = runTest {
        stubGroups(group(1, "M6"))
        stubProducts(1, product(7, "Card", "037/080"))
        val s = source()

        assertTrue(s.imageUrl("M6", "037")!!.contains("/7_400w"))
        assertNull(s.imageUrl("E1", "037"))
        assertNull(s.imageUrl("M6", "TG01"))
        coVerify(exactly = 1) { api.getJapanProducts(any()) }
    }

    @Test
    fun `duplicate abbreviation prefers the group named after the set id`() = runTest {
        stubGroups(group(1, "M6", name = "Other thing"), group(2, "M6", name = "M6: Real Set"))
        stubProducts(2, product(9, "Card", "003/010", group = 2))
        val s = source()

        assertTrue(s.imageUrl("M6", "3")!!.contains("/9_400w"))
        coVerify(exactly = 0) { api.getJapanProducts(1) }
    }

    @Test
    fun `three sets cost one groups call and three product calls, a budget of 2 stops the third`() = runTest {
        stubGroups(group(1, "A1"), group(2, "A2"), group(3, "A3"))
        stubProducts(1, product(11, "x", "001"))
        stubProducts(2, product(12, "x", "001", group = 2))
        stubProducts(3, product(13, "x", "001", group = 3))
        val s = source()
        val budget = FetchBudget(2)

        assertTrue(s.imageUrl("A1", "1", budget)!!.contains("/11_"))
        assertTrue(s.imageUrl("A2", "1", budget)!!.contains("/12_"))
        assertNull(s.imageUrl("A3", "1", budget))

        coVerify(exactly = 1) { api.getJapanGroups() }
        coVerify(exactly = 0) { api.getJapanProducts(3) }
        assertEquals(0, budget.left)
    }

    @Test
    fun `five concurrent cold lookups make one product call`() = runTest {
        stubGroups(group(1, "M6"))
        stubProducts(1, *m6)
        val s = source()

        val results = (1..5).map { async { s.imageUrl("M6", "048") } }.awaitAll()

        assertTrue(results.all { it != null })
        coVerify(exactly = 1) { api.getJapanProducts(1) }
    }

    @Test
    fun `groups list refreshes only after 24h`() = runTest {
        stubGroups(group(1, "M6"))
        stubProducts(1, *m6)
        source().imageUrl("M6", "048")

        now += 23 * hour
        source().imageUrl("M6", "048")
        coVerify(exactly = 1) { api.getJapanGroups() }

        now += 2 * hour
        source().imageUrl("M6", "048")
        coVerify(exactly = 2) { api.getJapanGroups() }
    }

    @Test
    fun `young group file refetches after 24h, settled group file does not`() = runTest {
        stubGroups(group(1, "NEW", published = "2027-01-10T00:00:00"), group(2, "OLD", published = "2025-01-01T00:00:00"))
        stubProducts(1, product(11, "x", "001"))
        stubProducts(2, product(12, "x", "001", group = 2))
        source().apply { imageUrl("NEW", "1"); imageUrl("OLD", "1") }

        now += 23 * hour
        source().apply { imageUrl("NEW", "1"); imageUrl("OLD", "1") }
        coVerify(exactly = 1) { api.getJapanProducts(1) }
        coVerify(exactly = 1) { api.getJapanProducts(2) }

        now += 2 * hour
        source().apply { imageUrl("NEW", "1"); imageUrl("OLD", "1") }
        coVerify(exactly = 2) { api.getJapanProducts(1) }
        coVerify(exactly = 1) { api.getJapanProducts(2) }
    }

    @Test
    fun `a miss in a file older than 24h refetches once`() = runTest {
        stubGroups(group(1, "OLD", published = "2025-01-01T00:00:00"))
        stubProducts(1, product(11, "x", "001"))
        source().imageUrl("OLD", "1")

        now += 25 * hour
        stubProducts(1, product(11, "x", "001"), product(12, "x", "002"))
        assertTrue(source().imageUrl("OLD", "2")!!.contains("/12_"))
        coVerify(exactly = 2) { api.getJapanProducts(1) }

        now += hour
        assertNull(source().imageUrl("OLD", "9"))
        coVerify(exactly = 2) { api.getJapanProducts(1) }
    }

    @Test
    fun `warm cache answers offline and stale copies are served when refresh fails`() = runTest {
        stubGroups(group(1, "M6"))
        stubProducts(1, *m6)
        source().imageUrl("M6", "048")

        now += 3 * day
        coEvery { api.getJapanGroups() } throws IOException("offline")
        coEvery { api.getJapanProducts(1) } throws IOException("offline")

        assertTrue(source().imageUrl("M6", "048")!!.contains("/709201_400w"))
    }

    @Test
    fun `cold and offline gives null and writes nothing`() = runTest {
        coEvery { api.getJapanGroups() } throws IOException("offline")

        assertNull(source().imageUrl("M6", "048"))

        assertFalse(File(tmp.root, "tcgcsv_ja").exists())
    }

    @Test
    fun `a failed groups refresh is not retried by the next lookups until the cooldown ends`() = runTest {
        coEvery { api.getJapanGroups() } throws IOException("offline")
        val s = source()
        val budget = FetchBudget(3)

        repeat(5) { assertNull(s.imageUrl("M6", "048", budget)) }
        coVerify(exactly = 1) { api.getJapanGroups() }

        now += 14 * minute
        s.imageUrl("M6", "048")
        coVerify(exactly = 1) { api.getJapanGroups() }

        now += 2 * minute
        stubGroups(group(1, "M6"))
        stubProducts(1, *m6)
        assertTrue(s.imageUrl("M6", "048")!!.contains("/709201_400w"))
        coVerify(exactly = 2) { api.getJapanGroups() }
    }

    @Test
    fun `a stale groups copy is served without refetching while the cooldown runs`() = runTest {
        stubGroups(group(1, "M6"))
        stubProducts(1, *m6)
        val s = source()
        s.imageUrl("M6", "048")

        now += 2 * day
        coEvery { api.getJapanGroups() } throws IOException("offline")
        repeat(5) { assertTrue(s.imageUrl("M6", "048")!!.contains("/709201_400w")) }

        coVerify(exactly = 2) { api.getJapanGroups() }
    }

    @Test
    fun `a 401 from the groups call backs off for hours, not minutes`() = runTest {
        coEvery { api.getJapanGroups() } throws
            HttpException(Response.error<Any>(401, "blocked".toResponseBody("text/plain".toMediaType())))
        val s = source()

        s.imageUrl("M6", "048")
        now += hour
        s.imageUrl("M6", "048")
        coVerify(exactly = 1) { api.getJapanGroups() }

        now += 6 * hour
        s.imageUrl("M6", "048")
        coVerify(exactly = 2) { api.getJapanGroups() }
    }

    @Test
    fun `a 403 from the groups call also backs off for hours`() = runTest {
        coEvery { api.getJapanGroups() } throws
            HttpException(Response.error<Any>(403, "blocked".toResponseBody("text/plain".toMediaType())))
        val s = source()

        s.imageUrl("M6", "048")
        now += hour
        s.imageUrl("M6", "048")
        coVerify(exactly = 1) { api.getJapanGroups() }
    }

    @Test
    fun `a 503 from the groups call is retried after 15 minutes, not hours`() = runTest {
        coEvery { api.getJapanGroups() } throws
            HttpException(Response.error<Any>(503, "down".toResponseBody("text/plain".toMediaType())))
        val s = source()

        s.imageUrl("M6", "048")
        now += 16 * 60 * 1000L
        s.imageUrl("M6", "048")
        coVerify(exactly = 2) { api.getJapanGroups() }
    }

    private fun stubThreeSharedAbbreviationGroups() = stubGroups(
        group(1, "M6", name = "Old", published = "2026-01-01T00:00:00"),
        group(2, "M6", name = "Mid", published = "2026-06-01T00:00:00"),
        group(3, "M6", name = "New", published = "2027-01-01T00:00:00")
    )

    @Test
    fun `two groups sharing an abbreviation without a set-id name try the two newest`() = runTest {
        stubThreeSharedAbbreviationGroups()
        stubProducts(3, product(31, "x", "002/010", group = 3))
        stubProducts(2, product(21, "x", "001/010", group = 2))
        val s = source()

        assertTrue(s.imageUrl("M6", "1")!!.contains("/21_400w"))
        coVerify(exactly = 1) { api.getJapanProducts(3) }
        coVerify(exactly = 1) { api.getJapanProducts(2) }
        coVerify(exactly = 0) { api.getJapanProducts(1) }
    }

    @Test
    fun `a miss in the two newest groups never reaches the third`() = runTest {
        stubThreeSharedAbbreviationGroups()
        stubProducts(3, product(31, "x", "002/010", group = 3))
        stubProducts(2, product(21, "x", "005/010", group = 2))
        stubProducts(1, product(11, "x", "001/010", group = 1)) // would match if take(3) regressed
        val s = source()

        assertNull(s.imageUrl("M6", "1"))
        coVerify(exactly = 1) { api.getJapanProducts(3) }
        coVerify(exactly = 1) { api.getJapanProducts(2) }
        coVerify(exactly = 0) { api.getJapanProducts(1) }
    }

    @Test
    fun `failed product download is remembered per group`() = runTest {
        stubGroups(group(1, "M6"))
        coEvery { api.getJapanProducts(1) } throws IOException("down")
        val s = source()

        repeat(5) { assertNull(s.imageUrl("M6", "048")) }

        coVerify(exactly = 1) { api.getJapanProducts(1) }
    }

    // Mutation: swapping the cooldown check and budget.tryTake() in fetchProducts makes the
    // second lookup spend budget (left 1) -> red.
    @Test
    fun `a cooled-down group spends no FetchBudget`() = runTest {
        stubGroups(group(1, "M6"))
        coEvery { api.getJapanProducts(1) } throws IOException("down")
        val s = source()
        val budget = FetchBudget(3)

        s.imageUrl("M6", "048", budget)
        assertEquals(2, budget.left)

        s.imageUrl("M6", "048", budget)
        assertEquals(2, budget.left)
        coVerify(exactly = 1) { api.getJapanProducts(1) }
    }

    @Test
    fun `failed product download is retried after 15 minutes`() = runTest {
        stubGroups(group(1, "M6"))
        coEvery { api.getJapanProducts(1) } throws IOException("down")
        val s = source()

        repeat(3) { s.imageUrl("M6", "048") }
        coVerify(exactly = 1) { api.getJapanProducts(1) }

        now += 16 * minute
        s.imageUrl("M6", "048")
        coVerify(exactly = 2) { api.getJapanProducts(1) }
    }

    @Test
    fun `401 on product download backs off 6 hours`() = runTest {
        stubGroups(group(1, "M6"))
        coEvery { api.getJapanProducts(1) } throws
            HttpException(Response.error<Any>(401, "blocked".toResponseBody("text/plain".toMediaType())))
        val s = source()

        s.imageUrl("M6", "048")
        now += 5 * hour
        s.imageUrl("M6", "048")
        coVerify(exactly = 1) { api.getJapanProducts(1) }

        now += 2 * hour
        s.imageUrl("M6", "048")
        coVerify(exactly = 2) { api.getJapanProducts(1) }
    }

    @Test
    fun `a failed group does not block other groups`() = runTest {
        stubGroups(group(1, "A1"), group(2, "A2"))
        coEvery { api.getJapanProducts(1) } throws IOException("down")
        stubProducts(2, product(12, "x", "001", group = 2))
        val s = source()

        assertNull(s.imageUrl("A1", "1"))
        assertTrue(s.imageUrl("A2", "1")!!.contains("/12_400w"))
    }

    @Test
    fun `corrupt cache files are refetched`() = runTest {
        stubGroups(group(1, "M6"))
        stubProducts(1, *m6)
        val s = source()
        s.imageUrl("M6", "048")
        File(s.cacheRoot, "g1.json").writeText("{not json")
        File(s.cacheRoot, "groups.json").writeText("")

        val result = source().imageUrl("M6", "048")

        assertTrue(result!!.contains("/709201_400w"))
        coVerify(exactly = 2) { api.getJapanProducts(1) }
    }

    @Test
    fun `product files of groups that left the list are deleted`() = runTest {
        stubGroups(group(1, "M6"), group(2, "M5"))
        stubProducts(1, *m6)
        stubProducts(2, product(5, "x", "001", group = 2))
        val s = source()
        s.imageUrl("M6", "048")
        s.imageUrl("M5", "1")
        assertTrue(File(s.cacheRoot, "g2.json").exists())

        now += 2 * day
        stubGroups(group(1, "M6"))
        source().imageUrl("M6", "048")

        assertFalse(File(s.cacheRoot, "g2.json").exists())
        assertTrue(File(s.cacheRoot, "g1.json").exists())
    }
}
