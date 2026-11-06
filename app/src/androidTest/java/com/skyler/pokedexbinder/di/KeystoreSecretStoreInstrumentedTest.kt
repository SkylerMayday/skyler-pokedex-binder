package com.skyler.pokedexbinder.di

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.security.KeyStore
import java.util.Base64
import java.util.UUID

/**
 * Real AndroidKeyStore coverage for [KeystoreSecretStore]. Uses a unique throwaway alias and prefs
 * file per test; NEVER touches `_androidx_security_master_key_` or [SECRETS_KEY_ALIAS].
 * Emulator only (ANDROID_SERIAL=emulator-5554).
 */
@RunWith(AndroidJUnit4::class)
class KeystoreSecretStoreInstrumentedTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val id = UUID.randomUUID().toString()
    private val alias = "test_secrets_$id"
    private val prefsName = "test_secrets_prefs_$id"

    private fun newStore() = KeystoreSecretStore(
        context.getSharedPreferences(prefsName, Context.MODE_PRIVATE),
        prefsName,
        { androidKeystoreAesKey(alias) },
        log = {}
    )

    private fun rawPrefs() = context.getSharedPreferences(prefsName, Context.MODE_PRIVATE)

    @Before
    fun setUp() {
        context.deleteSharedPreferences(prefsName)
    }

    @After
    fun tearDown() {
        KeyStore.getInstance("AndroidKeyStore").apply { load(null) }.deleteEntry(alias)
        context.deleteSharedPreferences(prefsName)
    }

    @Test
    fun roundTripAgainstRealKeystore() {
        val store = newStore()
        assertEquals(true, store.edit().putString("k", "secret").commit())
        assertEquals("secret", store.getString("k", null))
        assertNotEquals("secret", rawPrefs().getString("k", null))
    }

    @Test
    fun secondInstanceOnSameAliasReadsValue() {
        newStore().edit().putString("k", "secret").commit()
        assertEquals("secret", newStore().getString("k", null))
    }

    @Test
    fun tamperedCiphertextReturnsDefault() {
        newStore().edit().putString("k", "secret").commit()
        val blob = Base64.getDecoder().decode(rawPrefs().getString("k", null))
        blob[blob.size - 1] = (blob[blob.size - 1].toInt() xor 1).toByte()
        rawPrefs().edit().putString("k", Base64.getEncoder().encodeToString(blob)).commit()
        assertEquals("def", newStore().getString("k", "def"))
    }

    @Test
    fun ivIsTwelveBytes() {
        newStore().edit().putString("k", "secret").commit()
        val blob = Base64.getDecoder().decode(rawPrefs().getString("k", null))
        // 12-byte IV + 6-byte plaintext + 16-byte tag
        assertEquals(12 + 6 + 16, blob.size)
    }
}
