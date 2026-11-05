package com.skyler.pokedexbinder.domain

import com.skyler.pokedexbinder.data.local.ConnectingArtDao
import com.skyler.pokedexbinder.data.local.MainBinderDao
import com.skyler.pokedexbinder.data.local.PersonalCollectionDao
import com.skyler.pokedexbinder.data.local.SecondaryBinderDao
import com.skyler.pokedexbinder.data.local.UnownBinderDao
import com.skyler.pokedexbinder.data.remote.TcgdexApi
import com.skyler.pokedexbinder.data.remote.TcgdexCardDetailDto
import com.skyler.pokedexbinder.data.remote.TcgdexRef
import com.skyler.pokedexbinder.repository.SettingsRepository
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.yield
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

class SyncCardImagesUseCaseTest {

    private val api = mockk<TcgdexApi>()
    private val main = mockk<MainBinderDao>(relaxed = true)
    private val unown = mockk<UnownBinderDao>(relaxed = true)
    private val art = mockk<ConnectingArtDao>(relaxed = true)
    private val history = mockk<SecondaryBinderDao>(relaxed = true)
    private val pc = mockk<PersonalCollectionDao>(relaxed = true)
    private val settings = mockk<SettingsRepository>(relaxed = true)

    private fun useCase(vararg resolvers: BlankImageResolver) =
        SyncCardImagesUseCase(api, main, unown, art, history, pc, settings, resolvers.toSet())

    private fun detail(id: String, image: String?) = TcgdexCardDetailDto(id = id, name = "n", image = image)

    @Test
    fun `fills blank rows in all five tables with the high webp url`() = runTest {
        coEvery { main.blankImageCardIds() } returns listOf("tcgdex_sv08-048")
        coEvery { api.getCard("en", "sv08-048") } returns detail("sv08-048", "https://assets.tcgdex.net/en/sv/sv08/048")

        val result = useCase().sync()

        assertEquals(ImageSyncResult(1, 1, 0, 0, 0), result)
        val url = "https://assets.tcgdex.net/en/sv/sv08/048/high.webp"
        coVerify { main.fillBlankImage("tcgdex_sv08-048", url) }
        coVerify { unown.fillBlankImage("tcgdex_sv08-048", url) }
        coVerify { art.fillBlankImage("tcgdex_sv08-048", url) }
        coVerify { history.fillBlankImage("tcgdex_sv08-048", url) }
        coVerify { pc.fillBlankImage("tcgdex_sv08-048", url) }
    }

    @Test
    fun `ids shared between tables are looked up once and routed by language`() = runTest {
        coEvery { main.blankImageCardIds() } returns listOf("tcgdex_ja_M6-048")
        coEvery { pc.blankImageCardIds() } returns listOf("tcgdex_ja_M6-048")
        coEvery { api.getCard("ja", "M6-048") } returns detail("M6-048", "base")

        useCase().sync()

        coVerify(exactly = 1) { api.getCard("ja", "M6-048") }
    }

    @Test
    fun `tcgcsv and legacy ids are unsyncable and cause no network call`() = runTest {
        coEvery { main.blankImageCardIds() } returns listOf("tcgcsv_ja_123", "swsh1-1")

        val result = useCase().sync()

        assertEquals(ImageSyncResult(0, 0, 0, 2, 0), result)
        coVerify(exactly = 0) { api.getCard(any(), any()) }
    }

    @Test
    fun `no image yet counts as still missing and writes nothing`() = runTest {
        coEvery { main.blankImageCardIds() } returns listOf("tcgdex_ja_M6-048")
        coEvery { api.getCard("ja", "M6-048") } returns detail("M6-048", null)

        val result = useCase().sync()

        assertEquals(1, result.stillMissing)
        coVerify(exactly = 0) { main.fillBlankImage(any(), any()) }
    }

    @Test
    fun `one failure does not stop the rest`() = runTest {
        coEvery { main.blankImageCardIds() } returns listOf("tcgdex_a-1", "tcgdex_b-1", "tcgdex_c-1")
        coEvery { api.getCard("en", "a-1") } throws IOException("offline")
        coEvery { api.getCard("en", "b-1") } returns detail("b-1", "x")
        coEvery { api.getCard("en", "c-1") } returns detail("c-1", "y")

        val result = useCase().sync()

        assertEquals(ImageSyncResult(3, 2, 0, 0, 1), result)
    }

    @Test
    fun `resolver is tried only after tcgdex has no image and first non-null wins`() = runTest {
        coEvery { main.blankImageCardIds() } returns listOf("tcgdex_ja_M6-048", "tcgdex_sv08-048")
        coEvery { api.getCard("ja", "M6-048") } returns detail("M6-048", null)
        coEvery { api.getCard("en", "sv08-048") } returns detail("sv08-048", "base")
        val asked = mutableListOf<TcgdexRef>()
        val resolver = BlankImageResolver { asked += it; "https://img/709201_400w.jpg" }

        val result = useCase(resolver).sync()

        assertEquals(listOf(TcgdexRef("ja", "M6-048")), asked)
        assertEquals(2, result.updated)
        coVerify { main.fillBlankImage("tcgdex_ja_M6-048", "https://img/709201_400w.jpg") }
    }

    @Test
    fun `syncIfDue within 24h makes no calls`() = runTest {
        coEvery { settings.getLastImageSyncAt() } returns 1_000L

        val result = useCase().syncIfDue(now = 1_000L + SyncCardImagesUseCase.AUTO_SYNC_INTERVAL_MS - 1)

        assertNull(result)
        coVerify(exactly = 0) { main.blankImageCardIds() }
        coVerify(exactly = 0) { api.getCard(any(), any()) }
    }

    @Test
    fun `syncIfDue after 24h runs and stamps the time`() = runTest {
        coEvery { settings.getLastImageSyncAt() } returns 1_000L
        coEvery { main.blankImageCardIds() } returns listOf("tcgdex_sv08-048")
        coEvery { api.getCard("en", "sv08-048") } returns detail("sv08-048", "base")
        val now = 1_000L + SyncCardImagesUseCase.AUTO_SYNC_INTERVAL_MS

        val result = useCase().syncIfDue(now)

        assertEquals(1, result?.updated)
        coVerify { settings.setLastImageSyncAt(now) }
    }

    @Test
    fun `an all-failed run is not stamped`() = runTest {
        coEvery { main.blankImageCardIds() } returns listOf("tcgdex_a-1", "tcgdex_b-1")
        coEvery { api.getCard(any(), any()) } throws IOException("offline")

        val result = useCase().sync()

        assertEquals(2, result.failed)
        coVerify(exactly = 0) { settings.setLastImageSyncAt(any()) }
    }

    @Test
    fun `auto run is capped at MAX_AUTO_IDS but manual is not`() = runTest {
        val ids = (1..SyncCardImagesUseCase.MAX_AUTO_IDS + 5).map { "tcgdex_s-$it" }
        coEvery { settings.getLastImageSyncAt() } returns 0L
        coEvery { main.blankImageCardIds() } returns ids
        coEvery { api.getCard(any(), any()) } returns detail("x", null)

        assertEquals(SyncCardImagesUseCase.MAX_AUTO_IDS, useCase().syncIfDue(1L shl 40)?.checked)
        assertEquals(ids.size, useCase().sync().checked)
    }

    // Mutation: forcing `start = 0` in SyncCardImagesUseCase.run leaves ids 401..405 unreached -> red.
    @Test
    fun `successive auto runs rotate so every blank id is reached`() = runTest {
        val total = SyncCardImagesUseCase.MAX_AUTO_IDS + 5
        val ids = (1..total).map { "tcgdex_s-%03d".format(it) }
        coEvery { settings.getLastImageSyncAt() } returns 0L
        coEvery { main.blankImageCardIds() } returns ids
        coEvery { api.getCard(any(), any()) } returns detail("x", null)
        val interval = SyncCardImagesUseCase.AUTO_SYNC_INTERVAL_MS

        val day1 = useCase().syncIfDue(interval)
        val day2 = useCase().syncIfDue(2 * interval)

        assertEquals(SyncCardImagesUseCase.MAX_AUTO_IDS, day1?.checked)
        assertEquals(SyncCardImagesUseCase.MAX_AUTO_IDS, day2?.checked)
        for (n in 1..total) coVerify(atLeast = 1) { api.getCard("en", "s-%03d".format(n)) }
    }

    @Test
    fun `capped auto run checks image-bearing languages before ko and zh-cn blanks`() = runTest {
        val noImage = (1..SyncCardImagesUseCase.MAX_AUTO_IDS / 2).flatMap {
            listOf("tcgdex_ko_K-$it", "tcgdex_zh-cn_C-$it")
        }
        coEvery { settings.getLastImageSyncAt() } returns 0L
        coEvery { main.blankImageCardIds() } returns noImage + listOf("tcgdex_ja_M6-048", "tcgdex_sv08-048")
        coEvery { api.getCard(any(), any()) } returns detail("x", null)

        useCase().syncIfDue(1L shl 40)

        coVerify(exactly = 1) { api.getCard("ja", "M6-048") }
        coVerify(exactly = 1) { api.getCard("en", "sv08-048") }
    }

    @Test
    fun `auto run while a manual sync is running returns null without any lookup`() = runTest {
        val gate = CompletableDeferred<Unit>()
        coEvery { settings.getLastImageSyncAt() } returns 0L
        coEvery { main.blankImageCardIds() } coAnswers { gate.await(); listOf("tcgdex_a-1") }
        coEvery { api.getCard(any(), any()) } returns detail("x", null)
        val useCase = useCase()

        val manual = launch { useCase.sync() }
        yield() // the manual run takes the lock and parks on the gate
        val auto = async { useCase.syncIfDue(1L shl 40) }
        runCurrent()
        assertTrue("auto run must return immediately, not wait for the manual run", auto.isCompleted)
        gate.complete(Unit)
        manual.join()

        assertNull(auto.await())
        coVerify(exactly = 1) { main.blankImageCardIds() }
        coVerify(exactly = 1) { api.getCard(any(), any()) }
    }

    @Test
    fun `progress reports each card`() = runTest {
        coEvery { main.blankImageCardIds() } returns listOf("tcgdex_a-1", "tcgdex_b-1")
        coEvery { api.getCard(any(), any()) } returns detail("x", null)
        val seen = java.util.Collections.synchronizedList(mutableListOf<Pair<Int, Int>>())

        useCase().sync { done, total -> seen += done to total }

        assertEquals(listOf(0 to 2, 1 to 2, 2 to 2), seen.sortedBy { it.first })
    }
}
