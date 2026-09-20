package com.skyler.pokedexbinder.repository

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

/**
 * Real behavioral coverage for [PublishSettingsRepository] using [InMemoryPreferencesDataStore]
 * and [FakeSharedPreferences] — no migration logic here, so only defaults and setter round-trips
 * need covering.
 */
class PublishSettingsRepositoryTest {

    private lateinit var dataStore: DataStore<Preferences>
    private lateinit var securePrefs: FakeSharedPreferences
    private lateinit var repository: PublishSettingsRepository

    @Before
    fun setUp() {
        dataStore = InMemoryPreferencesDataStore()
        securePrefs = FakeSharedPreferences()
        repository = PublishSettingsRepository(dataStore, securePrefs)
    }

    @Test
    fun `getConfig on empty stores returns defaults`() = runTest {
        val config = repository.getConfig()

        assertEquals("", config.githubOwner)
        assertEquals("", config.githubRepo)
        assertEquals("", config.githubPat)
        assertEquals("", config.discordWebhookUrl)
        assertEquals(true, config.publishPokedex)
        assertEquals(false, config.publishCardHistory)
    }

    @Test
    fun `setGithubOwner and setGithubRepo round-trip through getConfig`() = runTest {
        repository.setGithubOwner("skyler")
        repository.setGithubRepo("pokedex-binder")

        val config = repository.getConfig()

        assertEquals("skyler", config.githubOwner)
        assertEquals("pokedex-binder", config.githubRepo)
    }

    @Test
    fun `setGithubPat and setDiscordWebhookUrl round-trip and land in secure prefs under their real keys`() = runTest {
        repository.setGithubPat("ghp_abc123")
        repository.setDiscordWebhookUrl("https://discord.com/api/webhooks/1/abc")

        val config = repository.getConfig()

        assertEquals("ghp_abc123", config.githubPat)
        assertEquals("https://discord.com/api/webhooks/1/abc", config.discordWebhookUrl)
        assertEquals("ghp_abc123", securePrefs.getString("github_pat", null))
        assertEquals(
            "https://discord.com/api/webhooks/1/abc",
            securePrefs.getString("discord_webhook_url", null)
        )
    }

    @Test
    fun `setPublishPokedex and setPublishCardHistory round-trip through getConfig`() = runTest {
        repository.setPublishPokedex(false)
        repository.setPublishCardHistory(true)

        val config = repository.getConfig()

        assertEquals(false, config.publishPokedex)
        assertEquals(true, config.publishCardHistory)
    }
}
