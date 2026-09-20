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
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

data class AppSettings(
    val showRegional: Boolean = false,
    val showAlternateForms: Boolean = false,
    val showMega: Boolean = false,
    val showGmax: Boolean = false,
    val useCameraScanner: Boolean = false,
    val darkMode: Boolean = false,
    val geminiApiKey: String = ""
)

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "app_settings")

/**
 * Gate for [SettingsRepository.migrateGeminiKeyIfNeeded]: whether the legacy plaintext Gemini API
 * key should even be checked. Pure/no Android types so it's unit-testable without a real Keystore
 * or Robolectric (this codebase has neither) — the actual copy is additionally gated on the legacy
 * value being non-empty, checked separately by the caller.
 */
internal fun shouldMigrateGeminiKey(encryptedValue: String?): Boolean = encryptedValue.isNullOrEmpty()

@Singleton
class SettingsRepository @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private object Keys {
        val SHOW_REGIONAL = booleanPreferencesKey("show_regional")
        val SHOW_ALTERNATE_FORMS = booleanPreferencesKey("show_alternate_forms")
        val SHOW_MEGA = booleanPreferencesKey("show_mega")
        val SHOW_GMAX = booleanPreferencesKey("show_gmax")
        val USE_CAMERA_SCANNER = booleanPreferencesKey("use_camera_scanner")
        val DARK_MODE = booleanPreferencesKey("dark_mode")
        val GEMINI_API_KEY = stringPreferencesKey("gemini_api_key")
    }

    private object SecureKeys {
        const val GEMINI_API_KEY = "gemini_api_key"
    }

    // EncryptedSharedPreferences for the Gemini API key — same pattern as
    // PublishSettingsRepository's securePrefs, but its own file so the two repositories stay
    // decoupled. Synchronous disk I/O, so build lazily and only touch from IO-dispatched suspend
    // functions (see getGeminiApiKey/setGeminiApiKey) except the one documented read in `settings`.
    private val securePrefs: SharedPreferences by lazy {
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build()
        EncryptedSharedPreferences.create(
            context, "settings_secrets", masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    }

    private val migrationMutex = Mutex()
    @Volatile private var geminiKeyMigrated = false

    /**
     * One-time copy of the Gemini API key from plaintext DataStore into [securePrefs]. Order
     * matters: read the legacy value, write it encrypted, THEN clear the plaintext copy — never
     * clear first, or a crash mid-migration would silently lose the key.
     */
    private suspend fun migrateGeminiKeyIfNeeded() {
        if (geminiKeyMigrated) return
        migrationMutex.withLock {
            if (geminiKeyMigrated) return@withLock
            if (shouldMigrateGeminiKey(securePrefs.getString(SecureKeys.GEMINI_API_KEY, null))) {
                val legacy = context.dataStore.data.map { it[Keys.GEMINI_API_KEY] ?: "" }.first()
                if (legacy.isNotEmpty()) {
                    withContext(Dispatchers.IO) {
                        // commit(), not apply(): the plaintext clear right below suspends until
                        // its own write is durable, so an async apply() here could still be
                        // in-flight when that clear lands — a process death in that window would
                        // lose the key from both stores. Already on Dispatchers.IO, so blocking
                        // here is free. Same reasoning as BackupImporter.kt's PendingImportError.persist().
                        securePrefs.edit().putString(SecureKeys.GEMINI_API_KEY, legacy).commit()
                    }
                    context.dataStore.edit { it.remove(Keys.GEMINI_API_KEY) }
                }
            }
            geminiKeyMigrated = true
        }
    }

    val settings: Flow<AppSettings> = context.dataStore.data.map { prefs ->
        migrateGeminiKeyIfNeeded()
        AppSettings(
            showRegional = prefs[Keys.SHOW_REGIONAL] ?: false,
            showAlternateForms = prefs[Keys.SHOW_ALTERNATE_FORMS] ?: false,
            showMega = prefs[Keys.SHOW_MEGA] ?: false,
            showGmax = prefs[Keys.SHOW_GMAX] ?: false,
            useCameraScanner = prefs[Keys.USE_CAMERA_SCANNER] ?: false,
            darkMode = prefs[Keys.DARK_MODE] ?: false,
            geminiApiKey = securePrefs.getString(SecureKeys.GEMINI_API_KEY, "") ?: ""
        )
    }

    suspend fun setShowRegional(enabled: Boolean) {
        context.dataStore.edit { it[Keys.SHOW_REGIONAL] = enabled }
    }

    suspend fun setShowAlternateForms(enabled: Boolean) {
        context.dataStore.edit { it[Keys.SHOW_ALTERNATE_FORMS] = enabled }
    }

    suspend fun setShowMega(enabled: Boolean) {
        context.dataStore.edit { it[Keys.SHOW_MEGA] = enabled }
    }

    suspend fun setShowGmax(enabled: Boolean) {
        context.dataStore.edit { it[Keys.SHOW_GMAX] = enabled }
    }

    suspend fun setUseCameraScanner(enabled: Boolean) {
        context.dataStore.edit { it[Keys.USE_CAMERA_SCANNER] = enabled }
    }

    suspend fun setDarkMode(enabled: Boolean) {
        context.dataStore.edit { it[Keys.DARK_MODE] = enabled }
    }

    suspend fun getGeminiApiKey(): String {
        migrateGeminiKeyIfNeeded()
        return withContext(Dispatchers.IO) {
            securePrefs.getString(SecureKeys.GEMINI_API_KEY, "") ?: ""
        }
    }

    suspend fun setGeminiApiKey(key: String) {
        migrateGeminiKeyIfNeeded()
        withContext(Dispatchers.IO) {
            securePrefs.edit().putString(SecureKeys.GEMINI_API_KEY, key).apply()
        }
    }
}
