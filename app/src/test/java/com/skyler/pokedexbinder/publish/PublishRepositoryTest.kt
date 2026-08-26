package com.skyler.pokedexbinder.publish

import android.util.Base64
import android.util.Log
import com.skyler.pokedexbinder.data.local.ConnectingArtGroup
import com.skyler.pokedexbinder.data.local.ConnectingArtSlot
import com.skyler.pokedexbinder.data.local.MainBinderEntry
import com.skyler.pokedexbinder.data.local.PersonalCollectionCache
import com.skyler.pokedexbinder.data.local.PersonalCollectionEntry
import com.skyler.pokedexbinder.data.local.SecondaryBinderDao
import com.skyler.pokedexbinder.data.local.SecondaryBinderEntry
import com.skyler.pokedexbinder.data.local.UnownBinderEntry
import com.skyler.pokedexbinder.data.remote.DiscordApi
import com.skyler.pokedexbinder.data.remote.GitHubApi
import com.skyler.pokedexbinder.data.remote.GitHubContentDto
import com.skyler.pokedexbinder.data.remote.GitHubPutRequest
import com.skyler.pokedexbinder.data.remote.GitHubPutResponse
import com.skyler.pokedexbinder.publish.model.BinderSnapshot
import com.skyler.pokedexbinder.publish.model.ChangeType
import com.skyler.pokedexbinder.publish.model.CHANGELOG_MAX_ENTRIES
import com.skyler.pokedexbinder.publish.model.Changelog
import com.skyler.pokedexbinder.publish.model.ChangelogEntry
import com.skyler.pokedexbinder.publish.model.PublishSummaryCounts
import com.skyler.pokedexbinder.publish.model.SlotChange
import com.skyler.pokedexbinder.publish.model.SnapshotBinder
import com.skyler.pokedexbinder.publish.model.SnapshotSection
import com.skyler.pokedexbinder.publish.model.SnapshotSlot
import com.skyler.pokedexbinder.repository.BinderRepository
import com.skyler.pokedexbinder.repository.ConnectingArtRepository
import com.skyler.pokedexbinder.repository.PersonalCollectionRepository
import com.skyler.pokedexbinder.repository.PublishConfig
import com.skyler.pokedexbinder.repository.PublishSettingsRepository
import com.skyler.pokedexbinder.repository.UnownBinderRepository
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import kotlinx.coroutines.test.runTest
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import retrofit2.Response

class PublishRepositoryTest {

    private val gitHubApi = mockk<GitHubApi>()
    private val discordApi = mockk<DiscordApi>()
    private val moshi = Moshi.Builder().addLast(KotlinJsonAdapterFactory()).build()
    private val publishSettingsRepository = mockk<PublishSettingsRepository>()
    private val binderRepository = mockk<BinderRepository>()
    private val secondaryBinderDao = mockk<SecondaryBinderDao>(relaxed = true)
    private val connectingArtRepository = mockk<ConnectingArtRepository>(relaxed = true)
    private val personalCollectionRepository = mockk<PersonalCollectionRepository>(relaxed = true)
    private val unownBinderRepository = mockk<UnownBinderRepository>(relaxed = true)

    private lateinit var repository: PublishRepository

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
        mockkStatic(Base64::class)
        every { Base64.encodeToString(any(), any()) } answers {
            java.util.Base64.getEncoder().encodeToString(firstArg())
        }
        every { Base64.decode(any<String>(), any()) } answers {
            java.util.Base64.getDecoder().decode(firstArg<String>())
        }
        mockkStatic(Log::class)
        every { Log.w(any(), any<String>()) } returns 0

        repository = PublishRepository(
            gitHubApi, discordApi, moshi, publishSettingsRepository, binderRepository,
            secondaryBinderDao,
            connectingArtRepository, personalCollectionRepository, unownBinderRepository
        )
    }

    private fun caGroup(id: Int, name: String, position: Int = id) =
        ConnectingArtGroup(id = id, name = name, rows = 1, cols = 2, position = position)

    private fun caSlot(groupId: Int, slotIndex: Int, cardId: String? = null, owned: Boolean = false) =
        ConnectingArtSlot(
            id = groupId * 100 + slotIndex, groupId = groupId, slotIndex = slotIndex,
            cardId = cardId, cardName = cardId?.let { "Card $it" },
            cardImageUrl = cardId?.let { "https://img/$it" }, owned = owned
        )

    private fun pcCache(cardId: String, key: String, name: String = "Card $cardId", release: String = "2020-01-01") =
        PersonalCollectionCache(
            cardId = cardId, pokemonKey = key, name = name,
            imageUrl = "https://img/$cardId", setName = "Set", releaseDate = release
        )

    private fun pcEntry(cardId: String, owned: Boolean) = PersonalCollectionEntry(cardId, owned)

    private fun entry(
        id: String,
        name: String,
        dex: Int,
        cardId: String? = null,
        language: String = "EN",
        remarks: String? = null,
        isLocked: Boolean = false
    ) = MainBinderEntry(
        pokemonId = id,
        pokemonName = name,
        dexNumber = dex,
        dexOrder = dex,
        slotType = "BASE",
        assignedCardId = cardId,
        assignedCardImageUrl = cardId?.let { "https://img.url/$it" },
        assignedCardName = cardId?.let { name },
        assignedCardSetName = cardId?.let { "Base Set" },
        language = language,
        remarks = remarks,
        isLocked = isLocked
    )

    private fun notFound(): Response<GitHubContentDto> =
        Response.error(404, "not found".toResponseBody("text/plain".toMediaType()))

    private fun snapshotOf(entries: List<MainBinderEntry>): BinderSnapshot {
        val slots = entries.map {
            SnapshotSlot(
                dexNumber = it.dexNumber,
                slotName = it.pokemonName,
                slotType = it.slotType,
                slotId = it.pokemonId,
                cardId = it.assignedCardId,
                cardName = it.assignedCardName,
                cardSet = it.assignedCardSetName,
                imageUrl = it.assignedCardImageUrl
            )
        }
        return BinderSnapshot(
            publishedAt = "2026-07-04T12:00:00+08:00",
            binders = listOf(SnapshotBinder(id = "pokedex", name = "Pokédex", sections = listOf(SnapshotSection("Generation I", slots))))
        )
    }

    private fun contentResponse(snapshot: BinderSnapshot, sha: String = "sha-1"): Response<GitHubContentDto> {
        val json = moshi.adapter(BinderSnapshot::class.java).toJson(snapshot)
        val encoded = java.util.Base64.getEncoder().encodeToString(json.toByteArray())
        return Response.success(GitHubContentDto(content = encoded, sha = sha, encoding = "base64"))
    }

    @Test
    fun `no changes returns NoChanges without PUT calls or webhook`() = runTest {
        val entries = listOf(entry("bulbasaur", "Bulbasaur", 1, cardId = "xy1-1"))
        coEvery { publishSettingsRepository.getConfig() } returns defaultConfig
        coEvery { binderRepository.getAllEntries() } returns entries
        coEvery { gitHubApi.getContent(any(), any(), any(), eq("binder.json"), any()) } returns
            contentResponse(snapshotOf(entries))

        val result = repository.publish {}

        assertTrue(result is PublishResult.NoChanges)
        coVerify(exactly = 0) { gitHubApi.putContent(any(), any(), any(), any(), any()) }
        coVerify(exactly = 0) { discordApi.sendWebhook(any(), any()) }
    }

    @Test
    fun `github binder PUT failure returns Failure at Uploading step without webhook`() = runTest {
        val entries = listOf(entry("bulbasaur", "Bulbasaur", 1, cardId = "xy1-1"))
        coEvery { publishSettingsRepository.getConfig() } returns defaultConfig
        coEvery { binderRepository.getAllEntries() } returns entries
        coEvery { gitHubApi.getContent(any(), any(), any(), eq("binder.json"), any()) } returns notFound()
        coEvery { gitHubApi.putContent(any(), any(), any(), eq("binder.json"), any()) } returns
            Response.error(500, "server error".toResponseBody("text/plain".toMediaType()))

        val result = repository.publish {}

        assertTrue(result is PublishResult.Failure)
        assertEquals(PublishStep.Uploading, (result as PublishResult.Failure).step)
        coVerify(exactly = 0) { discordApi.sendWebhook(any(), any()) }
    }

    @Test
    fun `first publish sets isFirstPublish and PUTs with null sha`() = runTest {
        val entries = listOf(
            entry("bulbasaur", "Bulbasaur", 1, cardId = "xy1-1"),
            entry("ivysaur", "Ivysaur", 2, cardId = null)
        )
        coEvery { publishSettingsRepository.getConfig() } returns defaultConfig
        coEvery { binderRepository.getAllEntries() } returns entries
        coEvery { gitHubApi.getContent(any(), any(), any(), eq("binder.json"), any()) } returns notFound()
        coEvery { gitHubApi.putContent(any(), any(), any(), eq("binder.json"), match { it.sha == null }) } returns
            Response.success(GitHubPutResponse(content = null, commit = null))
        coEvery { gitHubApi.getContent(any(), any(), any(), eq("changelog.json"), any()) } returns notFound()
        coEvery { gitHubApi.putContent(any(), any(), any(), eq("changelog.json"), any()) } returns
            Response.success(GitHubPutResponse(content = null, commit = null))

        val result = repository.publish {}

        assertTrue(result is PublishResult.Success)
        val diff = (result as PublishResult.Success).diff
        assertTrue(diff.isFirstPublish)
        assertEquals(1, diff.added)
        assertTrue(diff.deltas.all { it.type == ChangeType.ADDED })

        coVerify { gitHubApi.putContent(any(), any(), any(), eq("binder.json"), match { it.sha == null }) }
    }

    @Test
    fun `webhook not configured succeeds without discord call`() = runTest {
        val entries = listOf(entry("bulbasaur", "Bulbasaur", 1, cardId = "xy1-1"))
        coEvery { publishSettingsRepository.getConfig() } returns defaultConfig.copy(discordWebhookUrl = "")
        coEvery { binderRepository.getAllEntries() } returns entries
        coEvery { gitHubApi.getContent(any(), any(), any(), eq("binder.json"), any()) } returns notFound()
        coEvery { gitHubApi.putContent(any(), any(), any(), eq("binder.json"), any()) } returns
            Response.success(GitHubPutResponse(content = null, commit = null))
        coEvery { gitHubApi.getContent(any(), any(), any(), eq("changelog.json"), any()) } returns notFound()
        coEvery { gitHubApi.putContent(any(), any(), any(), eq("changelog.json"), any()) } returns
            Response.success(GitHubPutResponse(content = null, commit = null))

        val result = repository.publish {}

        assertTrue(result is PublishResult.Success)
        coVerify(exactly = 0) { discordApi.sendWebhook(any(), any()) }
    }

    @Test
    fun `PAT not configured fails fast before any network call`() = runTest {
        coEvery { publishSettingsRepository.getConfig() } returns defaultConfig.copy(githubPat = "")

        val result = repository.publish {}

        assertTrue(result is PublishResult.Failure)
        assertEquals(PublishStep.FetchingCurrent, (result as PublishResult.Failure).step)
        coVerify(exactly = 0) { gitHubApi.getContent(any(), any(), any(), any(), any()) }
    }

    // --- computeDiff matrix tests ---

    private fun slot(
        id: String,
        cardId: String?,
        name: String = "Slot$id",
        set: String? = "Set",
        owned: Boolean = cardId != null,   // matches corrected production defaults
        isLocked: Boolean = false
    ) = SnapshotSlot(
        dexNumber = 1, slotName = name, slotType = "BASE", slotId = id,
        cardId = cardId, cardName = cardId?.let { name }, cardSet = cardId?.let { set }, imageUrl = null,
        owned = owned, isLocked = isLocked
    )

    private fun binderWith(vararg slots: SnapshotSlot) = BinderSnapshot(
        publishedAt = "2026-07-04T12:00:00+08:00",
        binders = listOf(SnapshotBinder("pokedex", "Pokédex", listOf(SnapshotSection("Generation I", slots.toList()))))
    )

    @Test
    fun `computeDiff baseline null card next present is ADDED`() {
        val baseline = binderWith(slot("s1", cardId = null))
        val next = binderWith(slot("s1", cardId = "c1"))

        val diff = repository.computeDiff(baseline, next)

        assertEquals(1, diff.deltas.size)
        assertEquals(ChangeType.ADDED, diff.deltas[0].type)
    }

    @Test
    fun `computeDiff baseline present next null is REMOVED`() {
        val baseline = binderWith(slot("s1", cardId = "c1"))
        val next = binderWith(slot("s1", cardId = null))

        val diff = repository.computeDiff(baseline, next)

        assertEquals(1, diff.deltas.size)
        assertEquals(ChangeType.REMOVED, diff.deltas[0].type)
    }

    @Test
    fun `computeDiff both present but different card is REPLACED`() {
        val baseline = binderWith(slot("s1", cardId = "c1"))
        val next = binderWith(slot("s1", cardId = "c2"))

        val diff = repository.computeDiff(baseline, next)

        assertEquals(1, diff.deltas.size)
        assertEquals(ChangeType.REPLACED, diff.deltas[0].type)
    }

    @Test
    fun `computeDiff both present same card is no change`() {
        val baseline = binderWith(slot("s1", cardId = "c1"))
        val next = binderWith(slot("s1", cardId = "c1"))

        val diff = repository.computeDiff(baseline, next)

        assertTrue(diff.deltas.isEmpty())
        assertFalse(diff.hasChanges)
    }

    @Test
    fun `computeDiff both null is no change`() {
        val baseline = binderWith(slot("s1", cardId = null))
        val next = binderWith(slot("s1", cardId = null))

        val diff = repository.computeDiff(baseline, next)

        assertTrue(diff.deltas.isEmpty())
    }

    @Test
    fun `computeDiff null baseline is first publish and lists only occupied slots as ADDED`() {
        val next = binderWith(slot("s1", cardId = "c1"), slot("s2", cardId = null))

        val diff = repository.computeDiff(null, next)

        assertTrue(diff.isFirstPublish)
        assertEquals(1, diff.deltas.size)
        assertEquals("s1", diff.deltas[0].slotId)
    }

    @Test
    fun `computeDiff ADDED falls back to slotName when cardName is null`() {
        val baseline = binderWith(slot("s1", cardId = null))
        val nextSlot = SnapshotSlot(
            dexNumber = 1, slotName = "Bulbasaur", slotType = "BASE", slotId = "s1",
            cardId = "c1", cardName = null, cardSet = null, imageUrl = null
        )
        val next = binderWith(nextSlot)

        val diff = repository.computeDiff(baseline, next)

        assertEquals(1, diff.deltas.size)
        assertEquals("Bulbasaur", diff.deltas[0].displayName)
    }

    @Test
    fun `computeDiff REMOVED falls back to baseline slotName when baseline cardName is null`() {
        val baselineSlot = SnapshotSlot(
            dexNumber = 1, slotName = "Ivysaur", slotType = "BASE", slotId = "s1",
            cardId = "c1", cardName = null, cardSet = null, imageUrl = null
        )
        val baseline = binderWith(baselineSlot)
        val next = binderWith(slot("s1", cardId = null))

        val diff = repository.computeDiff(baseline, next)

        assertEquals(1, diff.deltas.size)
        assertEquals(ChangeType.REMOVED, diff.deltas[0].type)
        assertEquals("Ivysaur", diff.deltas[0].displayName)
    }

    @Test
    fun `computeDiff pokedexComplete only counts BASE slots with a card`() {
        val next = BinderSnapshot(
            publishedAt = "2026-07-04T12:00:00+08:00",
            binders = listOf(
                SnapshotBinder(
                    "pokedex", "Pokédex",
                    listOf(
                        SnapshotSection(
                            "Generation I",
                            listOf(
                                slot("s1", cardId = "c1"),
                                SnapshotSlot(1, "MegaX", "MEGA", "s2", cardId = "c2", cardName = "MegaX", cardSet = null, imageUrl = null)
                            )
                        )
                    )
                )
            )
        )

        val diff = repository.computeDiff(null, next)

        // Only the BASE slot with a card counts toward pokedexComplete; the MEGA slot doesn't.
        assertEquals(1, diff.pokedexComplete)
        assertEquals(1025, diff.pokedexTotal)
    }

    @Test
    fun `buildSnapshot groups entries into correct sections and sorts within a section`() {
        val entries = listOf(
            MainBinderEntry(pokemonId = "venusaur", pokemonName = "Venusaur", dexNumber = 3, dexOrder = 1, slotType = "BASE"),
            MainBinderEntry(pokemonId = "bulbasaur", pokemonName = "Bulbasaur", dexNumber = 1, dexOrder = 1, slotType = "BASE"),
            MainBinderEntry(pokemonId = "alolan-raichu", pokemonName = "Alolan Raichu", dexNumber = 26, dexOrder = 1, slotType = "REGIONAL"),
            MainBinderEntry(pokemonId = "mega-venusaur", pokemonName = "Mega Venusaur", dexNumber = 3, dexOrder = 2, slotType = "MEGA"),
            MainBinderEntry(pokemonId = "vmax-charizard", pokemonName = "VMax Charizard", dexNumber = 6, dexOrder = 1, slotType = "GMAX")
        )

        val snapshot = repository.buildSnapshot(entries, emptyList(), defaultConfig)

        val pokedexBinder = snapshot.binders.single { it.id == "pokedex" }
        val genI = pokedexBinder.sections.single { it.name == "Generation I" }
        assertEquals(listOf("bulbasaur", "venusaur"), genI.slots.map { it.slotId })

        assertTrue(pokedexBinder.sections.any { it.name == "Regional Variants" })
        assertTrue(pokedexBinder.sections.any { it.name == "Mega Evolutions" })
        assertTrue(pokedexBinder.sections.any { it.name == "VMax" })
    }

    @Test
    fun `buildSnapshot omits pokedex binder when publishPokedex is off`() {
        val entries = listOf(entry("bulbasaur", "Bulbasaur", 1, cardId = "xy1-1"))
        val config = defaultConfig.copy(publishPokedex = false)

        val snapshot = repository.buildSnapshot(entries, emptyList(), config)

        assertTrue(snapshot.binders.none { it.id == "pokedex" })
    }

    @Test
    fun `buildSnapshot pokedex slot owned is true when assigned and false when empty`() {
        val entries = listOf(
            entry("bulbasaur", "Bulbasaur", 1, cardId = "xy1-1"),
            entry("ivysaur", "Ivysaur", 2, cardId = null)
        )

        val snapshot = repository.buildSnapshot(entries, emptyList(), defaultConfig)

        val slots = snapshot.binders.single { it.id == "pokedex" }.sections.flatMap { it.slots }
        assertTrue(slots.single { it.slotId == "bulbasaur" }.owned)
        assertFalse(slots.single { it.slotId == "ivysaur" }.owned)
    }

    @Test
    fun `buildSnapshot includes cardHistory binder when toggle is on`() {
        val secondaryEntries = listOf(
            SecondaryBinderEntry(id = 1, pokemonId = "pikachu", pokemonName = "Pikachu", cardId = "sv1-1", cardImageUrl = "https://img.url/sv1-1")
        )
        val config = defaultConfig.copy(publishCardHistory = true)

        val snapshot = repository.buildSnapshot(emptyList(), secondaryEntries, config)

        val cardHistory = snapshot.binders.single { it.id == "cardHistory" }
        assertEquals(1, cardHistory.sections.single().slots.size)
        assertEquals("sv1-1", cardHistory.sections.single().slots[0].cardId)
    }

    @Test
    fun `buildSnapshot cardHistory slot owned is always true`() {
        val secondaryEntries = listOf(
            SecondaryBinderEntry(id = 1, pokemonId = "pikachu", pokemonName = "Pikachu", cardId = "sv1-1", cardImageUrl = "https://img.url/sv1-1")
        )
        val config = defaultConfig.copy(publishCardHistory = true)

        val snapshot = repository.buildSnapshot(emptyList(), secondaryEntries, config)

        val slot = snapshot.binders.single { it.id == "cardHistory" }.sections.single().slots.single()
        assertTrue(slot.owned)
    }

    @Test
    fun `changelog is trimmed to CHANGELOG_MAX_ENTRIES on publish`() = runTest {
        val entries = listOf(entry("bulbasaur", "Bulbasaur", 1, cardId = "xy1-1"))
        coEvery { publishSettingsRepository.getConfig() } returns defaultConfig
        coEvery { binderRepository.getAllEntries() } returns entries
        coEvery { gitHubApi.getContent(any(), any(), any(), eq("binder.json"), any()) } returns notFound()
        coEvery { gitHubApi.putContent(any(), any(), any(), eq("binder.json"), any()) } returns
            Response.success(GitHubPutResponse(content = null, commit = null))

        // Existing changelog already has 50 entries — after prepending the new one it must
        // still be capped at 50 (oldest entry dropped).
        val existingEntries = (1..50).map { i ->
            ChangelogEntry(
                publishedAt = "2026-01-0${i % 9 + 1}T00:00:00+08:00",
                summary = PublishSummaryCounts(added = 1, replaced = 0, removed = 0, pokedexComplete = i, pokedexTotal = 1025),
                changes = listOf(SlotChange(type = "ADDED", slotId = "old-$i", slotName = "Old$i", cardSet = null))
            )
        }
        val existingChangelogJson = moshi.adapter(Changelog::class.java).toJson(Changelog(existingEntries))
        val existingChangelogEncoded = java.util.Base64.getEncoder().encodeToString(existingChangelogJson.toByteArray())
        coEvery { gitHubApi.getContent(any(), any(), any(), eq("changelog.json"), any()) } returns
            Response.success(GitHubContentDto(content = existingChangelogEncoded, sha = "changelog-sha", encoding = "base64"))

        var capturedRequest: GitHubPutRequest? = null
        coEvery { gitHubApi.putContent(any(), any(), any(), eq("changelog.json"), any()) } answers {
            capturedRequest = it.invocation.args[4] as GitHubPutRequest
            Response.success(GitHubPutResponse(content = null, commit = null))
        }

        val result = repository.publish {}

        assertTrue(result is PublishResult.Success)
        val decodedJson = String(java.util.Base64.getDecoder().decode(capturedRequest!!.content))
        val putChangelog = moshi.adapter(Changelog::class.java).fromJson(decodedJson)!!
        assertEquals(CHANGELOG_MAX_ENTRIES, putChangelog.entries.size)
        // Newest entry (this publish) occupies slot 0, pushing out the oldest pre-existing
        // entry (old-50, which was last in the pre-existing list) so the cap of 50 holds.
        assertEquals("old-49", putChangelog.entries.last().changes.first().slotId)
        assertFalse(putChangelog.entries.any { it.changes.any { c -> c.slotId == "old-50" } })
        assertTrue(putChangelog.entries.any { it.changes.any { c -> c.slotId == "old-1" } })
    }

    @Test
    fun `changelog PUT failure is a hard failure without webhook even though binder json already succeeded`() = runTest {
        val entries = listOf(entry("bulbasaur", "Bulbasaur", 1, cardId = "xy1-1"))
        coEvery { publishSettingsRepository.getConfig() } returns defaultConfig.copy(discordWebhookUrl = "https://discord.com/api/webhooks/x/y")
        coEvery { binderRepository.getAllEntries() } returns entries
        coEvery { gitHubApi.getContent(any(), any(), any(), eq("binder.json"), any()) } returns notFound()
        coEvery { gitHubApi.putContent(any(), any(), any(), eq("binder.json"), any()) } returns
            Response.success(GitHubPutResponse(content = null, commit = null))
        coEvery { gitHubApi.getContent(any(), any(), any(), eq("changelog.json"), any()) } returns notFound()
        coEvery { gitHubApi.putContent(any(), any(), any(), eq("changelog.json"), any()) } returns
            Response.error(500, "server error".toResponseBody("text/plain".toMediaType()))

        val result = repository.publish {}

        assertTrue(result is PublishResult.Failure)
        assertEquals(PublishStep.Uploading, (result as PublishResult.Failure).step)
        coVerify(exactly = 0) { discordApi.sendWebhook(any(), any()) }
    }

    // --- Connecting Art / Personal Collection buildSnapshot + computeDiff tests ---

    @Test
    fun `buildSnapshot includes connectingArt binder when a group has an assigned slot`() {
        val groups = listOf(caGroup(1, "Group A"))
        val slots = listOf(
            caSlot(groupId = 1, slotIndex = 0, cardId = "c1", owned = true),
            caSlot(groupId = 1, slotIndex = 1, cardId = null)
        )

        val snapshot = repository.buildSnapshot(
            emptyList(), emptyList(), defaultConfig,
            connectingArtGroups = groups, connectingArtSlots = slots
        )

        val binder = snapshot.binders.single { it.id == "connectingArt" }
        val section = binder.sections.single()
        assertEquals("Group A", section.name)
        assertEquals(2, section.slots.size)
        assertEquals("ca-1-0", section.slots[0].slotId)
        assertEquals("c1", section.slots[0].cardId)
        assertTrue(section.slots[0].owned)
    }

    @Test
    fun `buildSnapshot omits connectingArt binder when no slots assigned`() {
        val groups = listOf(caGroup(1, "Group A"))
        val slots = listOf(
            caSlot(groupId = 1, slotIndex = 0, cardId = null),
            caSlot(groupId = 1, slotIndex = 1, cardId = null)
        )

        val snapshot = repository.buildSnapshot(
            emptyList(), emptyList(), defaultConfig,
            connectingArtGroups = groups, connectingArtSlots = slots
        )

        assertTrue(snapshot.binders.none { it.id == "connectingArt" })
    }

    @Test
    fun `buildSnapshot skips empty connectingArt group but keeps a non-empty one`() {
        val groups = listOf(caGroup(1, "Group A"), caGroup(2, "Group B"))
        val slots = listOf(
            caSlot(groupId = 1, slotIndex = 0, cardId = null),
            caSlot(groupId = 2, slotIndex = 0, cardId = "c1")
        )

        val snapshot = repository.buildSnapshot(
            emptyList(), emptyList(), defaultConfig,
            connectingArtGroups = groups, connectingArtSlots = slots
        )

        val binder = snapshot.binders.single { it.id == "connectingArt" }
        assertEquals(1, binder.sections.size)
        assertEquals("Group B", binder.sections[0].name)
    }

    @Test
    fun `buildSnapshot connectingArt slotId encodes group id and slot index`() {
        val groups = listOf(caGroup(2, "Group B"))
        val slots = listOf(
            caSlot(groupId = 2, slotIndex = 0, cardId = null),
            caSlot(groupId = 2, slotIndex = 1, cardId = "c1")
        )

        val snapshot = repository.buildSnapshot(
            emptyList(), emptyList(), defaultConfig,
            connectingArtGroups = groups, connectingArtSlots = slots
        )

        val binder = snapshot.binders.single { it.id == "connectingArt" }
        assertEquals("ca-2-1", binder.sections[0].slots[1].slotId)
    }

    @Test
    fun `buildSnapshot includes personalCollection binder with all cached cards and owned flags`() {
        val cache = listOf(
            pcCache("c1", "charizard", release = "2020-01-01"),
            pcCache("c2", "charizard", release = "2021-01-01")
        )
        val entries = listOf(pcEntry("c1", owned = true))

        val snapshot = repository.buildSnapshot(
            emptyList(), emptyList(), defaultConfig,
            personalCache = cache, personalEntries = entries
        )

        val binder = snapshot.binders.single { it.id == "personalCollection" }
        val section = binder.sections.single { it.name == "Charizard" }
        assertEquals(2, section.slots.size)
        assertTrue(section.slots.all { it.cardId != null })
        assertEquals(1, section.slots.count { it.owned })
        assertEquals(1, section.slots.count { !it.owned })
    }

    @Test
    fun `buildSnapshot omits personalCollection binder when cache empty`() {
        val snapshot = repository.buildSnapshot(
            emptyList(), emptyList(), defaultConfig,
            personalCache = emptyList(), personalEntries = emptyList()
        )

        assertTrue(snapshot.binders.none { it.id == "personalCollection" })
    }

    @Test
    fun `buildSnapshot skips empty personalCollection section`() {
        val cache = listOf(pcCache("c1", "charizard"))

        val snapshot = repository.buildSnapshot(
            emptyList(), emptyList(), defaultConfig,
            personalCache = cache, personalEntries = emptyList()
        )

        val binder = snapshot.binders.single { it.id == "personalCollection" }
        assertEquals(1, binder.sections.size)
        assertEquals("Charizard", binder.sections[0].name)
    }

    @Test
    fun `buildSnapshot personalCollection section name uses display title`() {
        val cache = listOf(pcCache("c1", "minccino_cinccino"))

        val snapshot = repository.buildSnapshot(
            emptyList(), emptyList(), defaultConfig,
            personalCache = cache, personalEntries = emptyList()
        )

        val binder = snapshot.binders.single { it.id == "personalCollection" }
        assertEquals("Minccino & Cinccino", binder.sections.single().name)
    }

    @Test
    fun `computeDiff owned flip false to true on same card is ADDED`() {
        val baseline = binderWith(slot("c1", cardId = "c1", owned = false))
        val next = binderWith(slot("c1", cardId = "c1", owned = true))

        val diff = repository.computeDiff(baseline, next)

        assertEquals(1, diff.deltas.size)
        assertEquals(ChangeType.ADDED, diff.deltas[0].type)
    }

    @Test
    fun `computeDiff owned flip true to false on same card is REMOVED`() {
        val baseline = binderWith(slot("c1", cardId = "c1", owned = true))
        val next = binderWith(slot("c1", cardId = "c1", owned = false))

        val diff = repository.computeDiff(baseline, next)

        assertEquals(1, diff.deltas.size)
        assertEquals(ChangeType.REMOVED, diff.deltas[0].type)
    }

    @Test
    fun `computeDiff first publish excludes owned false slots even when cardId is present`() {
        val next = binderWith(slot("s1", cardId = "c1", owned = true), slot("s2", cardId = "c2", owned = false))

        val diff = repository.computeDiff(null, next)

        assertEquals(1, diff.deltas.size)
        assertEquals("s1", diff.deltas[0].slotId)
        assertEquals(ChangeType.ADDED, diff.deltas[0].type)
    }

    @Test
    fun `computeDiff owned unchanged same card is no change`() {
        val baseline = binderWith(slot("c1", cardId = "c1", owned = true))
        val next = binderWith(slot("c1", cardId = "c1", owned = true))

        val diff = repository.computeDiff(baseline, next)

        assertTrue(diff.deltas.isEmpty())
    }

    @Test
    fun `buildSnapshot connectingArt and personalCollection slots do not inflate pokedexComplete`() {
        val entries = listOf(entry("bulbasaur", "Bulbasaur", 1, cardId = "xy1-1"))
        val groups = listOf(caGroup(1, "Group A"))
        val caSlots = listOf(caSlot(groupId = 1, slotIndex = 0, cardId = "c1", owned = true))
        val cache = listOf(pcCache("pc1", "charizard"))

        val next = repository.buildSnapshot(
            entries, emptyList(), defaultConfig,
            connectingArtGroups = groups, connectingArtSlots = caSlots,
            personalCache = cache, personalEntries = emptyList()
        )
        val diff = repository.computeDiff(null, next)

        assertEquals(1, diff.pokedexComplete)
    }

    // --- language/remarks/isLocked buildSnapshot + computeDiff tests ---

    @Test
    fun `buildSnapshot pokedex slot carries language remarks and isLocked from entity`() {
        val entries = listOf(
            entry("bulbasaur", "Bulbasaur", 1, cardId = "xy1-1", language = "JA", remarks = "mint", isLocked = true)
        )

        val snapshot = repository.buildSnapshot(entries, emptyList(), defaultConfig)

        val slot = snapshot.binders.single { it.id == "pokedex" }.sections.single().slots.single()
        assertEquals("JA", slot.language)
        assertEquals("mint", slot.remarks)
        assertTrue(slot.isLocked)
    }

    @Test
    fun `buildSnapshot unown slot carries language remarks and isLocked from entity`() {
        val unownEntries = listOf(
            UnownBinderEntry(
                letterId = "A", position = 0, assignedCardId = "c1", assignedCardImageUrl = "https://img/c1",
                assignedCardName = "Unown A", assignedCardSetName = "Set",
                language = "KO", remarks = "signed", isLocked = true
            )
        )

        val snapshot = repository.buildSnapshot(emptyList(), emptyList(), defaultConfig, unownEntries = unownEntries)

        val slot = snapshot.binders.single { it.id == "unown" }.sections.single().slots.single()
        assertEquals("KO", slot.language)
        assertEquals("signed", slot.remarks)
        assertTrue(slot.isLocked)
    }

    @Test
    fun `buildSnapshot unown slot owned is true when assigned and false when unassigned`() {
        val unownEntries = listOf(
            UnownBinderEntry(letterId = "A", position = 0, assignedCardId = "c1"),
            UnownBinderEntry(letterId = "B", position = 1, assignedCardId = null)
        )

        val snapshot = repository.buildSnapshot(emptyList(), emptyList(), defaultConfig, unownEntries = unownEntries)

        val slots = snapshot.binders.single { it.id == "unown" }.sections.single().slots
        assertTrue(slots.single { it.slotId == "unown-A" }.owned)
        assertFalse(slots.single { it.slotId == "unown-B" }.owned)
    }

    @Test
    fun `buildSnapshot cardHistory slot carries language but defaults remarks and isLocked`() {
        val secondaryEntries = listOf(
            SecondaryBinderEntry(
                id = 1, pokemonId = "pikachu", pokemonName = "Pikachu", cardId = "sv1-1",
                cardImageUrl = "https://img.url/sv1-1", language = "FR"
            )
        )
        val config = defaultConfig.copy(publishCardHistory = true)

        val snapshot = repository.buildSnapshot(emptyList(), secondaryEntries, config)

        val slot = snapshot.binders.single { it.id == "cardHistory" }.sections.single().slots.single()
        assertEquals("FR", slot.language)
        assertEquals(null, slot.remarks)
        assertFalse(slot.isLocked)
    }

    @Test
    fun `buildSnapshot connectingArt slot carries language but defaults remarks and isLocked`() {
        val groups = listOf(caGroup(1, "Group A"))
        val slots = listOf(
            ConnectingArtSlot(
                id = 100, groupId = 1, slotIndex = 0, cardId = "c1", cardName = "Card c1",
                cardImageUrl = "https://img/c1", owned = true, language = "DE"
            )
        )

        val snapshot = repository.buildSnapshot(
            emptyList(), emptyList(), defaultConfig,
            connectingArtGroups = groups, connectingArtSlots = slots
        )

        val slot = snapshot.binders.single { it.id == "connectingArt" }.sections.single().slots.single()
        assertEquals("DE", slot.language)
        assertEquals(null, slot.remarks)
        assertFalse(slot.isLocked)
    }

    @Test
    fun `buildSnapshot personalCollection slot looks up language by cardId and defaults remarks and isLocked`() {
        val cache = listOf(pcCache("c1", "charizard"))
        val entries = listOf(PersonalCollectionEntry(cardId = "c1", owned = true, language = "ES"))

        val snapshot = repository.buildSnapshot(
            emptyList(), emptyList(), defaultConfig,
            personalCache = cache, personalEntries = entries
        )

        val slot = snapshot.binders.single { it.id == "personalCollection" }.sections.single().slots.single()
        assertEquals("ES", slot.language)
        assertEquals(null, slot.remarks)
        assertFalse(slot.isLocked)
    }

    @Test
    fun `buildSnapshot personalCollection slot falls back to EN when cache row has no entry row`() {
        val cache = listOf(pcCache("c1", "charizard"))

        val snapshot = repository.buildSnapshot(
            emptyList(), emptyList(), defaultConfig,
            personalCache = cache, personalEntries = emptyList()
        )

        val slot = snapshot.binders.single { it.id == "personalCollection" }.sections.single().slots.single()
        assertEquals("EN", slot.language)
    }

    @Test
    fun `computeDiff personalCollection cache refresh with new unowned cards produces no ADDED deltas`() {
        val ownedCache = pcCache("c1", "charizard")
        val baseline = repository.buildSnapshot(
            emptyList(), emptyList(), defaultConfig,
            personalCache = listOf(ownedCache), personalEntries = listOf(pcEntry("c1", owned = true))
        )
        // Simulates a fresh cache repopulation: the previously-owned card is still cached,
        // plus 3 new candidate cardIds surface with no corresponding pcEntry (never owned).
        val refreshedCache = listOf(
            ownedCache,
            pcCache("c2", "charizard"),
            pcCache("c3", "charizard"),
            pcCache("c4", "charizard")
        )
        val next = repository.buildSnapshot(
            emptyList(), emptyList(), defaultConfig,
            personalCache = refreshedCache, personalEntries = listOf(pcEntry("c1", owned = true))
        )

        val diff = repository.computeDiff(baseline, next)

        assertTrue(diff.deltas.isEmpty())
    }

    @Test
    fun `computeDiff personalCollection cache refresh with one newly owned card among unowned candidates produces exactly one ADDED`() {
        val ownedCache = pcCache("c1", "charizard")
        val baseline = repository.buildSnapshot(
            emptyList(), emptyList(), defaultConfig,
            personalCache = listOf(ownedCache), personalEntries = listOf(pcEntry("c1", owned = true))
        )
        val refreshedCache = listOf(ownedCache, pcCache("c2", "charizard"), pcCache("c3", "charizard"))
        // Among the newly-surfaced candidates, c2 also gets marked owned in the same session.
        val next = repository.buildSnapshot(
            emptyList(), emptyList(), defaultConfig,
            personalCache = refreshedCache,
            personalEntries = listOf(pcEntry("c1", owned = true), pcEntry("c2", owned = true))
        )

        val diff = repository.computeDiff(baseline, next)

        assertEquals(1, diff.deltas.size)
        assertEquals(ChangeType.ADDED, diff.deltas[0].type)
        assertEquals("c2", diff.deltas[0].slotId)
    }

    @Test
    fun `computeDiff isLocked flip on same card is REPLACED`() {
        val baseline = binderWith(slot("c1", cardId = "c1", isLocked = false))
        val next = binderWith(slot("c1", cardId = "c1", isLocked = true))

        val diff = repository.computeDiff(baseline, next)

        assertEquals(1, diff.deltas.size)
        assertEquals(ChangeType.REPLACED, diff.deltas[0].type)
    }

    @Test
    fun `computeDiff language or remarks only change on same card is no change`() {
        val baseline = BinderSnapshot(
            publishedAt = "2026-07-04T12:00:00+08:00",
            binders = listOf(
                SnapshotBinder(
                    "pokedex", "Pokédex",
                    listOf(
                        SnapshotSection(
                            "Generation I",
                            listOf(
                                SnapshotSlot(
                                    1, "Slotc1", "BASE", "c1", cardId = "c1", cardName = "Slotc1", cardSet = "Set",
                                    imageUrl = null, owned = true, language = "EN", remarks = "old note", isLocked = false
                                )
                            )
                        )
                    )
                )
            )
        )
        val next = BinderSnapshot(
            publishedAt = "2026-07-04T12:00:00+08:00",
            binders = listOf(
                SnapshotBinder(
                    "pokedex", "Pokédex",
                    listOf(
                        SnapshotSection(
                            "Generation I",
                            listOf(
                                SnapshotSlot(
                                    1, "Slotc1", "BASE", "c1", cardId = "c1", cardName = "Slotc1", cardSet = "Set",
                                    imageUrl = null, owned = true, language = "JA", remarks = "new note", isLocked = false
                                )
                            )
                        )
                    )
                )
            )
        )

        val diff = repository.computeDiff(baseline, next)

        assertTrue(diff.deltas.isEmpty())
    }

    // --- backward compatibility: old binder.json predating language/remarks/isLocked ---

    @Test
    fun `SnapshotSlot parses old JSON missing owned language remarks and isLocked with safe defaults`() {
        // Simulates a binder.json published before the `owned` field existed (pre-R3) AND before
        // this change's language/remarks/isLocked fields existed — the oldest possible shape still
        // in the wild. Moshi's generated adapter must fall back to the Kotlin default values for
        // every missing key rather than failing to parse or nulling out non-nullable fields.
        val oldJson = """
            {
              "dexNumber": 1,
              "slotName": "Bulbasaur",
              "slotType": "BASE",
              "slotId": "bulbasaur",
              "cardId": "xy1-1",
              "cardName": "Bulbasaur",
              "cardSet": "Base Set",
              "imageUrl": "https://img.url/xy1-1"
            }
        """.trimIndent()

        val slot = moshi.adapter(SnapshotSlot::class.java).fromJson(oldJson)

        assertEquals(true, slot?.owned)
        assertEquals("EN", slot?.language)
        assertEquals(null, slot?.remarks)
        assertEquals(false, slot?.isLocked)
    }

    @Test
    fun `BinderSnapshot round-trips a full old-shape JSON without the 3 new fields`() {
        val oldBinderJson = """
            {
              "schemaVersion": 1,
              "publishedAt": "2026-01-01T00:00:00+08:00",
              "binders": [
                {
                  "id": "pokedex",
                  "name": "Pokédex",
                  "sections": [
                    {
                      "name": "Generation I",
                      "slots": [
                        {
                          "dexNumber": 1,
                          "slotName": "Bulbasaur",
                          "slotType": "BASE",
                          "slotId": "bulbasaur",
                          "cardId": "xy1-1",
                          "cardName": "Bulbasaur",
                          "cardSet": "Base Set",
                          "imageUrl": "https://img.url/xy1-1",
                          "owned": true
                        }
                      ]
                    }
                  ]
                }
              ]
            }
        """.trimIndent()

        val parsed = moshi.adapter(BinderSnapshot::class.java).fromJson(oldBinderJson)

        assertEquals(1, parsed?.schemaVersion)
        val slot = parsed?.binders?.single()?.sections?.single()?.slots?.single()
        assertEquals(true, slot?.owned)
        assertEquals("EN", slot?.language)
        assertEquals(null, slot?.remarks)
        assertEquals(false, slot?.isLocked)
    }
}
