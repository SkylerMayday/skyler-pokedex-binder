package com.skyler.pokedexbinder.publish

import android.util.Base64
import android.util.Log
import com.skyler.pokedexbinder.data.local.MainBinderEntry
import com.skyler.pokedexbinder.data.local.SecondaryBinderDao
import com.skyler.pokedexbinder.data.local.SecondaryBinderEntry
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
import com.skyler.pokedexbinder.repository.PublishConfig
import com.skyler.pokedexbinder.repository.PublishSettingsRepository
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
            gitHubApi, discordApi, moshi, publishSettingsRepository, binderRepository, secondaryBinderDao
        )
    }

    private fun entry(id: String, name: String, dex: Int, cardId: String? = null) = MainBinderEntry(
        pokemonId = id,
        pokemonName = name,
        dexNumber = dex,
        dexOrder = dex,
        slotType = "BASE",
        assignedCardId = cardId,
        assignedCardImageUrl = cardId?.let { "https://img.url/$it" },
        assignedCardName = cardId?.let { name },
        assignedCardSetName = cardId?.let { "Base Set" }
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

    private fun slot(id: String, cardId: String?, name: String = "Slot$id", set: String? = "Set") = SnapshotSlot(
        dexNumber = 1, slotName = name, slotType = "BASE", slotId = id,
        cardId = cardId, cardName = cardId?.let { name }, cardSet = cardId?.let { set }, imageUrl = null
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
}
