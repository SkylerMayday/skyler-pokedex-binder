package com.skyler.pokedexbinder.repository

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

data class AppSettings(
    val showRegional: Boolean = false,
    val showMega: Boolean = false,
    val showGmax: Boolean = false,
    val showSecondaryBinder: Boolean = false,
    val useCameraScanner: Boolean = false,
    val darkMode: Boolean = false,
    val geminiApiKey: String = ""
)

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "app_settings")

@Singleton
class SettingsRepository @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private object Keys {
        val SHOW_REGIONAL = booleanPreferencesKey("show_regional")
        val SHOW_MEGA = booleanPreferencesKey("show_mega")
        val SHOW_GMAX = booleanPreferencesKey("show_gmax")
        val SHOW_SECONDARY_BINDER = booleanPreferencesKey("show_secondary_binder")
        val USE_CAMERA_SCANNER = booleanPreferencesKey("use_camera_scanner")
        val DARK_MODE = booleanPreferencesKey("dark_mode")
        val GEMINI_API_KEY = stringPreferencesKey("gemini_api_key")
    }

    val settings: Flow<AppSettings> = context.dataStore.data.map { prefs ->
        AppSettings(
            showRegional = prefs[Keys.SHOW_REGIONAL] ?: false,
            showMega = prefs[Keys.SHOW_MEGA] ?: false,
            showGmax = prefs[Keys.SHOW_GMAX] ?: false,
            showSecondaryBinder = prefs[Keys.SHOW_SECONDARY_BINDER] ?: false,
            useCameraScanner = prefs[Keys.USE_CAMERA_SCANNER] ?: false,
            darkMode = prefs[Keys.DARK_MODE] ?: false,
            geminiApiKey = prefs[Keys.GEMINI_API_KEY] ?: ""
        )
    }

    suspend fun setShowRegional(enabled: Boolean) {
        context.dataStore.edit { it[Keys.SHOW_REGIONAL] = enabled }
    }

    suspend fun setShowMega(enabled: Boolean) {
        context.dataStore.edit { it[Keys.SHOW_MEGA] = enabled }
    }

    suspend fun setShowGmax(enabled: Boolean) {
        context.dataStore.edit { it[Keys.SHOW_GMAX] = enabled }
    }

    suspend fun setShowSecondaryBinder(enabled: Boolean) {
        context.dataStore.edit { it[Keys.SHOW_SECONDARY_BINDER] = enabled }
    }

    suspend fun setUseCameraScanner(enabled: Boolean) {
        context.dataStore.edit { it[Keys.USE_CAMERA_SCANNER] = enabled }
    }

    suspend fun setDarkMode(enabled: Boolean) {
        context.dataStore.edit { it[Keys.DARK_MODE] = enabled }
    }

    suspend fun getGeminiApiKey(): String =
        context.dataStore.data.map { it[Keys.GEMINI_API_KEY] ?: "" }.first()

    suspend fun setGeminiApiKey(key: String) {
        context.dataStore.edit { it[Keys.GEMINI_API_KEY] = key }
    }
}
