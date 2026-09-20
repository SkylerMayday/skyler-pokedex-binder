package com.skyler.pokedexbinder.repository

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Covers the pure migration gate plus, since [SettingsRepository] takes its `DataStore`/
 * `SharedPreferences` via constructor injection, real behavioral coverage of the migration
 * algorithm and get/set round-trips using [InMemoryPreferencesDataStore] and
 * [FakeSharedPreferences] — no Robolectric/real-Keystore harness needed (same gap noted for
 * [PublishSettingsRepository] before its own test file was added).
 */
class SettingsRepositoryTest {

    @Test
    fun `null encrypted value means migration should run`() {
        assertTrue(shouldMigrateGeminiKey(null))
    }

    @Test
    fun `empty encrypted value means migration should run`() {
        assertTrue(shouldMigrateGeminiKey(""))
    }

    @Test
    fun `non-empty encrypted value means migration should not run`() {
        assertFalse(shouldMigrateGeminiKey("already-set-key"))
    }

    private lateinit var dataStore: DataStore<Preferences>
    private lateinit var securePrefs: FakeSharedPreferences
    private lateinit var repository: SettingsRepository
    private val legacyKey = stringPreferencesKey("gemini_api_key")

    @Before
    fun setUp() {
        dataStore = InMemoryPreferencesDataStore()
        securePrefs = FakeSharedPreferences()
        repository = SettingsRepository(dataStore, securePrefs)
    }

    @Test
    fun `fresh install returns empty gemini key with no crash`() = runTest {
        assertEquals("", repository.getGeminiApiKey())
    }

    @Test
    fun `upgrade migrates legacy plaintext key into encrypted prefs and clears the legacy copy`() = runTest {
        dataStore.edit { it[legacyKey] = "legacy-key-123" }

        assertEquals("legacy-key-123", repository.getGeminiApiKey())
        assertEquals("legacy-key-123", securePrefs.getString("gemini_api_key", null))
        assertNull(dataStore.data.first()[legacyKey])
    }

    @Test
    fun `repeated post-migration reads both return the correct value`() = runTest {
        dataStore.edit { it[legacyKey] = "legacy-key-123" }

        assertEquals("legacy-key-123", repository.getGeminiApiKey())
        assertEquals("legacy-key-123", repository.getGeminiApiKey())
    }

    @Test
    fun `setGeminiApiKey stores in secure prefs and leaves no legacy datastore key`() = runTest {
        repository.setGeminiApiKey("new-key")

        assertEquals("new-key", securePrefs.getString("gemini_api_key", null))
        assertNull(dataStore.data.first()[legacyKey])
    }

    @Test
    fun `settings flow surfaces migrated legacy key and default fields`() = runTest {
        dataStore.edit { it[legacyKey] = "legacy-key-123" }

        val settings = repository.settings.first()

        assertEquals("legacy-key-123", settings.geminiApiKey)
        assertFalse(settings.showRegional)
        assertFalse(settings.showAlternateForms)
        assertFalse(settings.showMega)
        assertFalse(settings.showGmax)
        assertFalse(settings.useCameraScanner)
        assertFalse(settings.darkMode)
    }
}
