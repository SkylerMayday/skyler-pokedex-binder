package com.skyler.pokedexbinder.repository

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Covers only the pure migration gate — no Robolectric/real-Keystore harness exists in this
 * project to exercise the rest of [SettingsRepository] (same gap as [PublishSettingsRepository],
 * which has no test file for the identical EncryptedSharedPreferences pattern either).
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
}
