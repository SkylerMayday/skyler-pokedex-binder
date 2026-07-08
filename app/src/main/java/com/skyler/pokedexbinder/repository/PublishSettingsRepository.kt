package com.skyler.pokedexbinder.repository

import android.content.Context
import android.content.SharedPreferences
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

data class PublishConfig(
    val githubOwner: String = "",
    val githubRepo: String = "",
    val githubPat: String = "",
    val discordWebhookUrl: String = "",
    val publishPokedex: Boolean = true,      // default ON
    val publishCardHistory: Boolean = false  // default OFF
)

private val Context.publishDataStore: DataStore<Preferences> by preferencesDataStore(name = "publish_settings")

@Singleton
class PublishSettingsRepository @Inject constructor(
    @ApplicationContext private val context: Context
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

    // EncryptedSharedPreferences for PAT + webhook — synchronous disk I/O, so build lazily
    // and only touch from IO-dispatched suspend functions (see getConfig/setGithubPat/etc.).
    private val securePrefs: SharedPreferences by lazy {
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build()
        EncryptedSharedPreferences.create(
            context, "publish_secrets", masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    }

    val config: Flow<PublishConfig> = context.publishDataStore.data.map { prefs ->
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
        context.publishDataStore.edit { it[Keys.GITHUB_OWNER] = v }
    }

    suspend fun setGithubRepo(v: String) {
        context.publishDataStore.edit { it[Keys.GITHUB_REPO] = v }
    }

    suspend fun setGithubPat(v: String) = withContext(Dispatchers.IO) {
        securePrefs.edit().putString(SecureKeys.GITHUB_PAT, v).apply()
    }

    suspend fun setDiscordWebhookUrl(v: String) = withContext(Dispatchers.IO) {
        securePrefs.edit().putString(SecureKeys.DISCORD_WEBHOOK_URL, v).apply()
    }

    suspend fun setPublishPokedex(v: Boolean) {
        context.publishDataStore.edit { it[Keys.PUBLISH_POKEDEX] = v }
    }

    suspend fun setPublishCardHistory(v: Boolean) {
        context.publishDataStore.edit { it[Keys.PUBLISH_CARD_HISTORY] = v }
    }
}
