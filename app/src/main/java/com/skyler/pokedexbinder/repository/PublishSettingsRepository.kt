package com.skyler.pokedexbinder.repository

import android.content.SharedPreferences
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Named
import javax.inject.Singleton

data class PublishConfig(
    val githubOwner: String = "",
    val githubRepo: String = "",
    val githubPat: String = "",
    val discordWebhookUrl: String = "",
    val publishPokedex: Boolean = true,      // default ON
    val publishCardHistory: Boolean = false  // default OFF
)

@Singleton
class PublishSettingsRepository @Inject constructor(
    @Named("publishDataStore") private val dataStore: DataStore<Preferences>,
    @Named("publishSecrets") private val securePrefs: SharedPreferences
) {
    private object Keys {
        val GITHUB_OWNER = stringPreferencesKey("github_owner")
        val GITHUB_REPO = stringPreferencesKey("github_repo")
        val PUBLISH_POKEDEX = booleanPreferencesKey("publish_pokedex")
        val PUBLISH_CARD_HISTORY = booleanPreferencesKey("publish_card_history")
    }

    private object SecureKeys {
        const val GITHUB_PAT = "github_pat"
        const val DISCORD_WEBHOOK_URL = "discord_webhook_url"
    }

    val config: Flow<PublishConfig> = dataStore.data.map { prefs ->
        PublishConfig(
            githubOwner = prefs[Keys.GITHUB_OWNER] ?: "",
            githubRepo = prefs[Keys.GITHUB_REPO] ?: "",
            githubPat = securePrefs.getString(SecureKeys.GITHUB_PAT, "") ?: "",
            discordWebhookUrl = securePrefs.getString(SecureKeys.DISCORD_WEBHOOK_URL, "") ?: "",
            publishPokedex = prefs[Keys.PUBLISH_POKEDEX] ?: true,
            publishCardHistory = prefs[Keys.PUBLISH_CARD_HISTORY] ?: false
        )
    }

    suspend fun getConfig(): PublishConfig = withContext(Dispatchers.IO) {
        config.first()
    }

    suspend fun setGithubOwner(v: String) {
        dataStore.edit { it[Keys.GITHUB_OWNER] = v }
    }

    suspend fun setGithubRepo(v: String) {
        dataStore.edit { it[Keys.GITHUB_REPO] = v }
    }

    suspend fun setGithubPat(v: String) = withContext(Dispatchers.IO) {
        securePrefs.edit().putString(SecureKeys.GITHUB_PAT, v).apply()
    }

    suspend fun setDiscordWebhookUrl(v: String) = withContext(Dispatchers.IO) {
        securePrefs.edit().putString(SecureKeys.DISCORD_WEBHOOK_URL, v).apply()
    }

    suspend fun setPublishPokedex(v: Boolean) {
        dataStore.edit { it[Keys.PUBLISH_POKEDEX] = v }
    }

    suspend fun setPublishCardHistory(v: Boolean) {
        dataStore.edit { it[Keys.PUBLISH_CARD_HISTORY] = v }
    }
}
