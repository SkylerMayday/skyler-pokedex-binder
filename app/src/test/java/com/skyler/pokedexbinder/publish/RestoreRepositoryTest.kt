package com.skyler.pokedexbinder.publish

import com.skyler.pokedexbinder.data.local.ConnectingArtSlot
import com.skyler.pokedexbinder.data.local.MainBinderEntry
import com.skyler.pokedexbinder.data.local.PersonalCollectionEntry
import com.skyler.pokedexbinder.data.local.UnownBinderEntry
import com.skyler.pokedexbinder.publish.model.BinderSnapshot
import com.skyler.pokedexbinder.publish.model.SnapshotBinder
import com.skyler.pokedexbinder.publish.model.SnapshotSection
import com.skyler.pokedexbinder.publish.model.SnapshotSlot
import com.skyler.pokedexbinder.repository.BinderRepository
import com.skyler.pokedexbinder.repository.ConnectingArtRepository
import com.skyler.pokedexbinder.repository.PersonalCollectionRepository
import com.skyler.pokedexbinder.repository.PublishConfig
import com.skyler.pokedexbinder.repository.PublishSettingsRepository
import com.skyler.pokedexbinder.repository.UnownBinderRepository
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class RestoreRepositoryTest {

    private val publishRepository = mockk<PublishRepository>()
    private val publishSettingsRepository = mockk<PublishSettingsRepository>()
    private val binderRepository = mockk<BinderRepository>()
    private val connectingArtRepository = mockk<ConnectingArtRepository>(relaxed = true)
    private val personalCollectionRepository = mockk<PersonalCollectionRepository>(relaxed = true)
    private val unownBinderRepository = mockk<UnownBinderRepository>(relaxed = true)

    private lateinit var repository: RestoreRepository

    private val defaultConfig = PublishConfig(
        githubOwner = "skylermayday",
        githubRepo = "pokedex-binder",
        githubPat = "ghp_test",
        discordWebhookUrl = "",
        publishPokedex = true,
        publishCardHistory = false
    )

    @Before
    fun setUp() {
        coEvery { connectingArtRepository.getAllSlots() } returns emptyList()
        coEvery { personalCollectionRepository.getAllEntries() } returns emptyList()
        coEvery { unownBinderRepository.getAllEntries() } returns emptyList()
        repository = RestoreRepository(
            publishRepository, publishSettingsRepository, binderRepository,
            connectingArtRepository, personalCollectionRepository, unownBinderRepository
        )
    }

    private fun entry(
        id: String,
        name: String,
        dex: Int,
        dexOrder: Int = dex,
        slotType: String = "BASE",
        cardId: String? = null
    ) = MainBinderEntry(
        pokemonId = id,
        pokemonName = name,
        dexNumber = dex,
        dexOrder = dexOrder,
        slotType = slotType,
        assignedCardId = cardId,
        assignedCardImageUrl = cardId?.let { "https://img.url/$it" },
        assignedCardName = cardId?.let { name },
        assignedCardSetName = cardId?.let { "Base Set" }
    )

    private fun snapshotSlot(id: String, cardId: String?, name: String = "Slot$id", set: String? = "Set", owned: Boolean = true) =
        SnapshotSlot(
            dexNumber = 1, slotName = name, slotType = "BASE", slotId = id,
            cardId = cardId, cardName = cardId?.let { name }, cardSet = cardId?.let { set },
            imageUrl = cardId?.let { "https://img.url/$it" }, owned = owned
        )

    private fun localCaSlot(groupId: Int, slotIndex: Int, cardId: String? = null, owned: Boolean = false) =
        ConnectingArtSlot(
            id = groupId * 100 + slotIndex, groupId = groupId, slotIndex = slotIndex,
            cardId = cardId, cardName = cardId?.let { "Card $it" },
            cardImageUrl = cardId?.let { "https://img/$it" }, owned = owned
        )

    private fun caSnapshot(vararg slots: SnapshotSlot) = BinderSnapshot(
        publishedAt = "2026-07-10T12:00:00+08:00",
        binders = listOf(SnapshotBinder("connectingArt", "Connecting Art", listOf(SnapshotSection("Group A", slots.toList()))))
    )

    private fun pcSnapshot(vararg slots: SnapshotSlot) = BinderSnapshot(
        publishedAt = "2026-07-10T12:00:00+08:00",
        binders = listOf(SnapshotBinder("personalCollection", "Personal Collection", listOf(SnapshotSection("Charizard", slots.toList()))))
    )

    private fun pokedexSnapshot(vararg slots: SnapshotSlot, binderId: String = "pokedex") = BinderSnapshot(
        publishedAt = "2026-07-04T12:00:00+08:00",
        binders = listOf(SnapshotBinder(binderId, "Pokédex", listOf(SnapshotSection("Generation I", slots.toList()))))
    )

    @Test
    fun `happy path overlays assignment fields and preserves structural fields`() = runTest {
        // 3 pokedex slots in snapshot: s1 filled, s2 empty (was filled locally -> cleared), s3 filled (new)
        val snapshot = pokedexSnapshot(
            snapshotSlot("s1", cardId = "c1-new", name = "S1"),
            snapshotSlot("s2", cardId = null, name = "S2"),
            snapshotSlot("s3", cardId = "c3", name = "S3")
        )
        val localEntries = listOf(
            entry("s1", "S1", dex = 1, dexOrder = 5, slotType = "BASE", cardId = "c1-old"),
            entry("s2", "S2", dex = 2, dexOrder = 6, slotType = "MEGA", cardId = "c2-old"),
            entry("s3", "S3", dex = 3, dexOrder = 7, slotType = "REGIONAL", cardId = null),
            entry("s4", "S4", dex = 4, dexOrder = 8, slotType = "BASE", cardId = "c4-untouched") // no snapshot entry
        )

        coEvery { publishSettingsRepository.getConfig() } returns defaultConfig
        coEvery { publishRepository.fetchBaselineSnapshot(any(), any()) } returns (snapshot to "sha-1")
        coEvery { binderRepository.getAllEntries() } returns localEntries
        val capturedSlot = slot<List<MainBinderEntry>>()
        coEvery { binderRepository.seedFromJson(capture(capturedSlot)) } returns Unit

        val result = repository.restore {}

        assertTrue(result is RestoreResult.Success)
        val success = result as RestoreResult.Success
        assertEquals(2, success.restoredCount) // s1, s3
        assertEquals(1, success.clearedCount)  // s2
        assertEquals(0, success.skippedCount)  // all snapshot slotIds match locally

        val written = capturedSlot.captured.associateBy { it.pokemonId }

        // s1: restored to new card, structural fields unchanged
        assertEquals("c1-new", written.getValue("s1").assignedCardId)
        assertEquals(1, written.getValue("s1").dexNumber)
        assertEquals(5, written.getValue("s1").dexOrder)
        assertEquals("BASE", written.getValue("s1").slotType)
        assertEquals("S1", written.getValue("s1").pokemonName)

        // s2: cleared, structural fields unchanged
        assertEquals(null, written.getValue("s2").assignedCardId)
        assertEquals(null, written.getValue("s2").assignedCardName)
        assertEquals(null, written.getValue("s2").assignedCardSetName)
        assertEquals(null, written.getValue("s2").assignedCardImageUrl)
        assertEquals(2, written.getValue("s2").dexNumber)
        assertEquals(6, written.getValue("s2").dexOrder)
        assertEquals("MEGA", written.getValue("s2").slotType)

        // s3: restored (was empty locally, snapshot has card)
        assertEquals("c3", written.getValue("s3").assignedCardId)
        assertEquals(3, written.getValue("s3").dexNumber)
        assertEquals(7, written.getValue("s3").dexOrder)
        assertEquals("REGIONAL", written.getValue("s3").slotType)

        // s4: untouched — no snapshot entry
        assertEquals("c4-untouched", written.getValue("s4").assignedCardId)
        assertEquals(4, written.getValue("s4").dexNumber)
        assertEquals(8, written.getValue("s4").dexOrder)
        assertEquals("BASE", written.getValue("s4").slotType)
    }

    @Test
    fun `restore preserves local pokemonName and dexOrder even when snapshot slotName differs`() = runTest {
        // Regression guard for the D2 CRITICAL risk: snapshot slotName is a display name that can
        // diverge from the current local pokemonName (e.g. renamed by a migration), and dexOrder is
        // not present in the snapshot at all. A restore must NEVER let the snapshot's slotName leak
        // into pokemonName, and dexOrder must survive untouched — only the 4 assignment fields move.
        val snapshot = pokedexSnapshot(
            snapshotSlot("s1", cardId = "c1-new", name = "Deoxys (old display name)")
        )
        val localEntries = listOf(
            entry("s1", "Deoxys", dex = 386, dexOrder = 999, slotType = "REGIONAL", cardId = "c1-old")
        )

        coEvery { publishSettingsRepository.getConfig() } returns defaultConfig
        coEvery { publishRepository.fetchBaselineSnapshot(any(), any()) } returns (snapshot to "sha-1")
        coEvery { binderRepository.getAllEntries() } returns localEntries
        val capturedSlot = slot<List<MainBinderEntry>>()
        coEvery { binderRepository.seedFromJson(capture(capturedSlot)) } returns Unit

        val result = repository.restore {}

        assertTrue(result is RestoreResult.Success)
        val written = capturedSlot.captured.single()

        // Assignment fields DID come from the snapshot.
        assertEquals("c1-new", written.assignedCardId)

        // Structural fields did NOT come from the snapshot — pokemonName must stay "Deoxys",
        // never "Deoxys (old display name)"; dexOrder/dexNumber/slotType are untouched local values.
        assertEquals("Deoxys", written.pokemonName)
        assertEquals(386, written.dexNumber)
        assertEquals(999, written.dexOrder)
        assertEquals("REGIONAL", written.slotType)
        assertEquals("s1", written.pokemonId)
    }

    @Test
    fun `404 returns NoSnapshot and never writes`() = runTest {
        coEvery { publishSettingsRepository.getConfig() } returns defaultConfig
        coEvery { publishRepository.fetchBaselineSnapshot(any(), any()) } returns (null to null)

        val result = repository.restore {}

        assertTrue(result is RestoreResult.NoSnapshot)
        coVerify(exactly = 0) { binderRepository.seedFromJson(any()) }
    }

    @Test
    fun `unconfigured PAT fails fast before any fetch`() = runTest {
        coEvery { publishSettingsRepository.getConfig() } returns defaultConfig.copy(githubPat = "")

        val result = repository.restore {}

        assertTrue(result is RestoreResult.Failure)
        assertEquals(RestoreStep.Fetching, (result as RestoreResult.Failure).step)
        coVerify(exactly = 0) { publishRepository.fetchBaselineSnapshot(any(), any()) }
    }

    @Test
    fun `fetch throws maps to Failure at Fetching step`() = runTest {
        coEvery { publishSettingsRepository.getConfig() } returns defaultConfig
        coEvery { publishRepository.fetchBaselineSnapshot(any(), any()) } throws
            PublishFailedException(PublishStep.FetchingCurrent, "boom")

        val result = repository.restore {}

        assertTrue(result is RestoreResult.Failure)
        assertEquals(RestoreStep.Fetching, (result as RestoreResult.Failure).step)
        assertEquals("boom", result.message)
        coVerify(exactly = 0) { binderRepository.seedFromJson(any()) }
    }

    @Test
    fun `empty pokedex binder yields Success with all zero counts`() = runTest {
        val snapshot = pokedexSnapshot() // no slots
        val localEntries = listOf(entry("s1", "S1", dex = 1, cardId = "c1"))

        coEvery { publishSettingsRepository.getConfig() } returns defaultConfig
        coEvery { publishRepository.fetchBaselineSnapshot(any(), any()) } returns (snapshot to "sha-1")
        coEvery { binderRepository.getAllEntries() } returns localEntries
        coEvery { binderRepository.seedFromJson(any()) } returns Unit

        val result = repository.restore {}

        assertTrue(result is RestoreResult.Success)
        val success = result as RestoreResult.Success
        assertEquals(0, success.restoredCount)
        assertEquals(0, success.clearedCount)
        assertEquals(0, success.skippedCount)
    }

    @Test
    fun `snapshot slotId with no local match is counted as skipped`() = runTest {
        val snapshot = pokedexSnapshot(snapshotSlot("ghost-slot", cardId = "c1"))
        val localEntries = listOf(entry("s1", "S1", dex = 1, cardId = null))

        coEvery { publishSettingsRepository.getConfig() } returns defaultConfig
        coEvery { publishRepository.fetchBaselineSnapshot(any(), any()) } returns (snapshot to "sha-1")
        coEvery { binderRepository.getAllEntries() } returns localEntries
        val capturedSlot = slot<List<MainBinderEntry>>()
        coEvery { binderRepository.seedFromJson(capture(capturedSlot)) } returns Unit

        val result = repository.restore {}

        assertTrue(result is RestoreResult.Success)
        val success = result as RestoreResult.Success
        assertEquals(0, success.restoredCount)
        assertEquals(0, success.clearedCount)
        assertEquals(1, success.skippedCount)
        // local row untouched, no orphan insert
        assertEquals(1, capturedSlot.captured.size)
        assertEquals("s1", capturedSlot.captured[0].pokemonId)
    }

    @Test
    fun `non-pokedex binder in snapshot is ignored`() = runTest {
        val snapshot = BinderSnapshot(
            publishedAt = "2026-07-04T12:00:00+08:00",
            binders = listOf(
                SnapshotBinder(
                    "cardHistory", "Card History",
                    listOf(SnapshotSection("Card History", listOf(snapshotSlot("s1-1", cardId = "c1"))))
                )
            )
        )
        val localEntries = listOf(entry("s1", "S1", dex = 1, cardId = "old-card"))

        coEvery { publishSettingsRepository.getConfig() } returns defaultConfig
        coEvery { publishRepository.fetchBaselineSnapshot(any(), any()) } returns (snapshot to "sha-1")
        coEvery { binderRepository.getAllEntries() } returns localEntries
        val capturedSlot = slot<List<MainBinderEntry>>()
        coEvery { binderRepository.seedFromJson(capture(capturedSlot)) } returns Unit

        val result = repository.restore {}

        assertTrue(result is RestoreResult.Success)
        val success = result as RestoreResult.Success
        assertEquals(0, success.restoredCount)
        assertEquals(0, success.clearedCount)
        assertEquals(0, success.skippedCount)
        assertEquals("old-card", capturedSlot.captured[0].assignedCardId)
        assertFalse(capturedSlot.captured.any { it.pokemonId == "s1-1" })
    }

    @Test
    fun `db write failure maps to Failure at Restoring step`() = runTest {
        val snapshot = pokedexSnapshot(snapshotSlot("s1", cardId = "c1"))
        val localEntries = listOf(entry("s1", "S1", dex = 1, cardId = null))

        coEvery { publishSettingsRepository.getConfig() } returns defaultConfig
        coEvery { publishRepository.fetchBaselineSnapshot(any(), any()) } returns (snapshot to "sha-1")
        coEvery { binderRepository.getAllEntries() } returns localEntries
        coEvery { binderRepository.seedFromJson(any()) } throws RuntimeException("db error")

        val result = repository.restore {}

        assertTrue(result is RestoreResult.Failure)
        assertEquals(RestoreStep.Restoring, (result as RestoreResult.Failure).step)
        assertEquals("db error", result.message)
    }

    // --- Connecting Art overlay tests ---

    @Test
    fun `connecting art overlay restores and clears slots by group and position`() = runTest {
        val snapshot = caSnapshot(
            snapshotSlot("ca-1-0", cardId = "c-new", name = "Group A #1"),
            snapshotSlot("ca-1-1", cardId = null, name = "Group A #2")
        )
        val localEntries = listOf(entry("s1", "S1", dex = 1, cardId = null))
        val localCa = listOf(
            localCaSlot(groupId = 1, slotIndex = 0, cardId = null),
            localCaSlot(groupId = 1, slotIndex = 1, cardId = "c-old")
        )

        coEvery { publishSettingsRepository.getConfig() } returns defaultConfig
        coEvery { publishRepository.fetchBaselineSnapshot(any(), any()) } returns (snapshot to "sha-1")
        coEvery { binderRepository.getAllEntries() } returns localEntries
        coEvery { binderRepository.seedFromJson(any()) } returns Unit
        coEvery { connectingArtRepository.getAllSlots() } returns localCa
        val capturedCa = slot<List<ConnectingArtSlot>>()
        coEvery { connectingArtRepository.updateSlots(capture(capturedCa)) } returns Unit

        val result = repository.restore {}

        assertTrue(result is RestoreResult.Success)
        val success = result as RestoreResult.Success
        assertEquals(1, success.restoredCount)
        assertEquals(1, success.clearedCount)

        val written = capturedCa.captured.associateBy { it.slotIndex }
        assertEquals("c-new", written.getValue(0).cardId)
        assertEquals(null, written.getValue(1).cardId)
    }

    @Test
    fun `connecting art overlay counts snapshot slots with no local match as skipped`() = runTest {
        val snapshot = caSnapshot(snapshotSlot("ca-9-0", cardId = "c1", name = "Ghost"))
        val localEntries = listOf(entry("s1", "S1", dex = 1, cardId = null))

        coEvery { publishSettingsRepository.getConfig() } returns defaultConfig
        coEvery { publishRepository.fetchBaselineSnapshot(any(), any()) } returns (snapshot to "sha-1")
        coEvery { binderRepository.getAllEntries() } returns localEntries
        coEvery { binderRepository.seedFromJson(any()) } returns Unit
        coEvery { connectingArtRepository.getAllSlots() } returns emptyList()

        val result = repository.restore {}

        assertTrue(result is RestoreResult.Success)
        val success = result as RestoreResult.Success
        assertEquals(1, success.skippedCount)
        coVerify(exactly = 0) { connectingArtRepository.updateSlots(any()) }
    }

    @Test
    fun `old snapshot without connecting art binder leaves connecting art untouched`() = runTest {
        val snapshot = pokedexSnapshot(snapshotSlot("s1", cardId = "c1"))
        val localEntries = listOf(entry("s1", "S1", dex = 1, cardId = null))

        coEvery { publishSettingsRepository.getConfig() } returns defaultConfig
        coEvery { publishRepository.fetchBaselineSnapshot(any(), any()) } returns (snapshot to "sha-1")
        coEvery { binderRepository.getAllEntries() } returns localEntries
        coEvery { binderRepository.seedFromJson(any()) } returns Unit

        val result = repository.restore {}

        assertTrue(result is RestoreResult.Success)
        coVerify(exactly = 0) { connectingArtRepository.updateSlots(any()) }
    }

    // --- Personal Collection overlay tests ---

    @Test
    fun `personal collection overlay restores owned by cardId`() = runTest {
        val snapshot = pcSnapshot(
            snapshotSlot("cardA", cardId = "cardA", owned = true),
            snapshotSlot("cardB", cardId = "cardB", owned = false)
        )
        val localEntries = listOf(entry("s1", "S1", dex = 1, cardId = null))

        coEvery { publishSettingsRepository.getConfig() } returns defaultConfig
        coEvery { publishRepository.fetchBaselineSnapshot(any(), any()) } returns (snapshot to "sha-1")
        coEvery { binderRepository.getAllEntries() } returns localEntries
        coEvery { binderRepository.seedFromJson(any()) } returns Unit
        coEvery { personalCollectionRepository.getAllEntries() } returns
            listOf(PersonalCollectionEntry(cardId = "cardB", owned = true))

        val result = repository.restore {}

        assertTrue(result is RestoreResult.Success)
        val success = result as RestoreResult.Success
        assertEquals(1, success.restoredCount) // cardA
        assertEquals(1, success.clearedCount)  // cardB
        coVerify { personalCollectionRepository.setOwned("cardA", true) }
        coVerify { personalCollectionRepository.removeOwned("cardB") }
    }

    @Test
    fun `personal collection restores owned for a card with no local entry yet`() = runTest {
        val snapshot = pcSnapshot(snapshotSlot("cardX", cardId = "cardX", owned = true))
        val localEntries = listOf(entry("s1", "S1", dex = 1, cardId = null))

        coEvery { publishSettingsRepository.getConfig() } returns defaultConfig
        coEvery { publishRepository.fetchBaselineSnapshot(any(), any()) } returns (snapshot to "sha-1")
        coEvery { binderRepository.getAllEntries() } returns localEntries
        coEvery { binderRepository.seedFromJson(any()) } returns Unit
        coEvery { personalCollectionRepository.getAllEntries() } returns emptyList()

        val result = repository.restore {}

        assertTrue(result is RestoreResult.Success)
        val success = result as RestoreResult.Success
        assertEquals(1, success.restoredCount)
        coVerify { personalCollectionRepository.setOwned("cardX", true) }
    }

    @Test
    fun `personal collection overlay never writes the cache`() = runTest {
        // The repository exposes no cache-write method reachable from restore — restore only
        // ever calls setOwned/removeOwned, which write personal_collection_entry, never
        // personal_collection_cache. Document via exact call assertions.
        val snapshot = pcSnapshot(snapshotSlot("cardA", cardId = "cardA", owned = true))
        val localEntries = listOf(entry("s1", "S1", dex = 1, cardId = null))

        coEvery { publishSettingsRepository.getConfig() } returns defaultConfig
        coEvery { publishRepository.fetchBaselineSnapshot(any(), any()) } returns (snapshot to "sha-1")
        coEvery { binderRepository.getAllEntries() } returns localEntries
        coEvery { binderRepository.seedFromJson(any()) } returns Unit
        coEvery { personalCollectionRepository.getAllEntries() } returns emptyList()

        repository.restore {}

        coVerify(exactly = 1) { personalCollectionRepository.setOwned(any(), any()) }
        coVerify(exactly = 0) { personalCollectionRepository.removeOwned(any()) }
    }

    @Test
    fun `old snapshot without personal collection binder leaves owned untouched`() = runTest {
        val snapshot = pokedexSnapshot(snapshotSlot("s1", cardId = "c1"))
        val localEntries = listOf(entry("s1", "S1", dex = 1, cardId = null))

        coEvery { publishSettingsRepository.getConfig() } returns defaultConfig
        coEvery { publishRepository.fetchBaselineSnapshot(any(), any()) } returns (snapshot to "sha-1")
        coEvery { binderRepository.getAllEntries() } returns localEntries
        coEvery { binderRepository.seedFromJson(any()) } returns Unit

        val result = repository.restore {}

        assertTrue(result is RestoreResult.Success)
        coVerify(exactly = 0) { personalCollectionRepository.setOwned(any(), any()) }
        coVerify(exactly = 0) { personalCollectionRepository.removeOwned(any()) }
    }

    @Test
    fun `owned flag round-trips through publish then restore`() = runTest {
        // Build a PC snapshot via the real PublishRepository.buildSnapshot (pure function, no
        // network/DB dependencies exercised) then feed it as the restore baseline.
        val realPublishRepository = PublishRepository(
            gitHubApi = mockk(relaxed = true),
            discordApi = mockk(relaxed = true),
            moshi = com.squareup.moshi.Moshi.Builder()
                .addLast(com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory()).build(),
            publishSettingsRepository = mockk(relaxed = true),
            binderRepository = mockk(relaxed = true),
            secondaryBinderDao = mockk(relaxed = true),
            connectingArtRepository = mockk(relaxed = true),
            personalCollectionRepository = mockk(relaxed = true),
            unownBinderRepository = mockk(relaxed = true)
        )
        val cache = listOf(
            com.skyler.pokedexbinder.data.local.PersonalCollectionCache(
                cardId = "ownedCard", pokemonKey = "charizard", name = "Owned",
                imageUrl = "https://img/ownedCard", setName = "Set", releaseDate = "2020-01-01"
            ),
            com.skyler.pokedexbinder.data.local.PersonalCollectionCache(
                cardId = "unownedCard", pokemonKey = "charizard", name = "Unowned",
                imageUrl = "https://img/unownedCard", setName = "Set", releaseDate = "2021-01-01"
            )
        )
        val pcEntries = listOf(PersonalCollectionEntry(cardId = "ownedCard", owned = true))
        val builtSnapshot = realPublishRepository.buildSnapshot(
            emptyList(), emptyList(), defaultConfig,
            personalCache = cache, personalEntries = pcEntries
        )

        val localEntries = listOf(entry("s1", "S1", dex = 1, cardId = null))
        coEvery { publishSettingsRepository.getConfig() } returns defaultConfig
        coEvery { publishRepository.fetchBaselineSnapshot(any(), any()) } returns (builtSnapshot to "sha-1")
        coEvery { binderRepository.getAllEntries() } returns localEntries
        coEvery { binderRepository.seedFromJson(any()) } returns Unit
        coEvery { personalCollectionRepository.getAllEntries() } returns emptyList()

        repository.restore {}

        coVerify { personalCollectionRepository.setOwned("ownedCard", true) }
        coVerify(exactly = 0) { personalCollectionRepository.setOwned("unownedCard", any()) }
        coVerify(exactly = 0) { personalCollectionRepository.removeOwned(any()) }
    }
}
